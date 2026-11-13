package com.veneer.core.db;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The web panel's graph of accounts that are probably one person: shared addresses, fingerprints, clients. */
public final class LinkGraph {

    /** A name pattern an administrator knows a bot generator by. */
    public record BotPattern(String name, java.util.regex.Pattern regex, double chance) {}

    /**
     * What decides a link, as chances from 0 to 1: nearly equal names (only symbols differ, only digits differ, one
     * character or a prefix), bot batches (seen together, spread out, an account named like their fixed part), e-mail,
     * and what a pair's other links must reach before its same channels count.
     */
    public record Rules(boolean sameName, double symbols, double digits, double similar,
                        boolean batches, int minBatch, long batchWindowMs, double batchTogether, double batchSpread, double batchNamed,
                        double email, double clientSupport, List<BotPattern> patterns, List<java.util.regex.Pattern> ignoreNames) {
        public static final Rules DEFAULT = new Rules(true, 0.8, 0.5, 0.35, true, 4, 30 * 60_000L, 0.9, 0.6, 0.35, 0.9, 0.5, List.of(), List.of());
    }

    /** Shorter names and stems are too common to link by. */
    private static final int MIN_NAME_STEM = 5;
    private static final int MIN_NAME_PREFIX = 6;
    private static final int MAX_BATCH_AFFIX = 6;

    private final Database db;
    private final ScanStore scans;
    private final PlayerStore players;
    private final FingerprintStore fingerprints;

    LinkGraph(Database db, ScanStore scans, PlayerStore players, FingerprintStore fingerprints) {
        this.db = db;
        this.scans = scans;
        this.players = players;
        this.fingerprints = fingerprints;
    }

    private record Rows3(List<String[]> joins, List<String[]> clients, List<String[]> authMe, List<String[]> seen, Map<String, List<String>> scanMods) {}

    /**
     * Graph of linked accounts: {@code nodes} (id, name, detected, links, skin) and {@code edges} (source, target,
     * type "ip", "rptrack", "client", "email", "name" or "batch", label, p = how likely the two accounts are one person, 0 to 1). An address
     * shared by more than {@code maxPerIp} accounts is skipped (shared networks); fingerprints link when they overlap
     * by at least {@code minMatch} packs. A client link is the same brand with the same set of at least
     * {@code minChannels} plugin channels, shared by no more than {@code maxPerClient} accounts: a common client
     * (plain vanilla, a popular modpack) says nothing. Nodes also carry {@code last_seen} and their {@code ips}.
     * Accounts without any link are left out.
     * <p>
     * The rows are read on a pooled read connection and compared afterwards, so a large graph holds up neither
     * writes nor other readers.
     */
    public Map<String, Object> getLinkGraph(int maxPerIp, int minMatch, int maxPerClient, int minChannels) {
        return getLinkGraph(maxPerIp, minMatch, maxPerClient, minChannels, 0, Rules.DEFAULT);
    }

    /** As above, plus up to {@code maxUnlinked} accounts without a link, so a lone account still shows in the graph. */
    public Map<String, Object> getLinkGraph(int maxPerIp, int minMatch, int maxPerClient, int minChannels, int maxUnlinked) {
        return getLinkGraph(maxPerIp, minMatch, maxPerClient, minChannels, maxUnlinked, Rules.DEFAULT);
    }

    /** As above, with the chances and name rules of the server's configuration. */
    public Map<String, Object> getLinkGraph(int maxPerIp, int minMatch, int maxPerClient, int minChannels, int maxUnlinked, Rules rules) {
        Rows3 rows = db.read("getLinkGraph", new Rows3(List.of(), List.of(), List.of(), List.of(), Map.of()), c -> {
            List<String[]> joinRows = new ArrayList<>(), clientRows = new ArrayList<>(), authMeRows = new ArrayList<>(), seenRows = new ArrayList<>();
            try (Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT uuid, name, ip, last_seen FROM joins ORDER BY last_seen DESC")) {
                    while (rs.next()) joinRows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
                }
                try (ResultSet rs = st.executeQuery("SELECT uuid, name, brand, channels FROM clients")) {
                    while (rs.next()) clientRows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
                }
            }
            // Accounts linked only by fingerprint or client have no joins row, but were seen too
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, last_seen FROM rptrack UNION ALL SELECT uuid, updated FROM clients")) {
                while (rs.next()) seenRows.add(new String[]{rs.getString(1), rs.getString(2)});
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, name, reg_ip, last_ip, last_login, email_hash FROM authme_accounts")) {
                while (rs.next()) authMeRows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)});
            }
            return new Rows3(joinRows, clientRows, authMeRows, seenRows, scans.latestScanMods(c));
        });
        List<FingerprintStore.Fingerprint> tracks = fingerprints.masks();

        List<Map<String, Object>> edges = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, List<String>> byIp = new LinkedHashMap<>(), ipsOf = new HashMap<>();
        Map<String, Long> lastSeen = new HashMap<>();
        for (String[] r : rows.joins()) {
            names.putIfAbsent(r[0], r[1]);
            byIp.computeIfAbsent(r[2], k -> new ArrayList<>()).add(r[0]);
            ipsOf.computeIfAbsent(r[0], k -> new ArrayList<>()).add(r[2]);
            lastSeen.merge(r[0], Long.parseLong(r[3]), Math::max);
        }
        for (String[] r : rows.seen()) lastSeen.merge(r[0], Long.parseLong(r[1]), Math::max);
        // AuthMe's addresses reach back before Veneer was installed; they count like any other shared address
        Map<String, List<String>> byEmail = new LinkedHashMap<>();
        for (String[] r : rows.authMe()) {
            names.putIfAbsent(r[0], r[1]);
            lastSeen.merge(r[0], Long.parseLong(r[4]), Math::max);
            for (String ip : new String[]{r[2], r[3]}) {
                if (ip == null) continue;
                List<String> known = ipsOf.computeIfAbsent(r[0], k -> new ArrayList<>());
                if (known.contains(ip)) continue;
                known.add(ip);
                byIp.computeIfAbsent(ip, k -> new ArrayList<>()).add(r[0]);
            }
            if (r[5] != null) byEmail.computeIfAbsent(r[5], k -> new ArrayList<>()).add(r[0]);
        }
        for (List<String> ids : byEmail.values()) {
            if (ids.size() < 2 || ids.size() > maxPerIp) continue;
            for (int i = 0; i < ids.size(); i++)
                for (int j = i + 1; j < ids.size(); j++)
                    edges.add(edge(ids.get(i), ids.get(j), "email", "e-mail", rules.email() / Math.max(1.0, (ids.size() - 1) / 2.0)));
        }
        for (Map.Entry<String, List<String>> e : byIp.entrySet()) {
            List<String> ids = e.getValue();
            if (ids.size() < 2 || ids.size() > maxPerIp) continue;
            for (int i = 0; i < ids.size(); i++)
                for (int j = i + 1; j < ids.size(); j++)
                    edges.add(edge(ids.get(i), ids.get(j), "ip", e.getKey(), 0.55 / Math.max(1.0, (ids.size() - 1) / 2.0)));
        }
        int needed = Math.max(1, minMatch);
        for (int i = 0; i < tracks.size(); i++) {
            FingerprintStore.Fingerprint a = tracks.get(i);
            // Fewer packs than needed can never match anything
            if (Long.bitCount(a.mask()) < needed) continue;
            for (int j = i + 1; j < tracks.size(); j++) {
                FingerprintStore.Fingerprint b = tracks.get(j);
                int common = Long.bitCount(a.mask() & b.mask());
                if (common < needed) continue;
                names.putIfAbsent(a.uuid(), a.name());
                names.putIfAbsent(b.uuid(), b.name());
                edges.add(edge(a.uuid(), b.uuid(), "rptrack", common + " packs", Math.min(0.97, 0.8 + 0.03 * (common - needed))));
            }
        }

        Map<String, List<String>> byClient = new LinkedHashMap<>();
        Map<String, String> brands = new HashMap<>();
        for (String[] r : rows.clients()) {
            if (r[3].isEmpty() || r[3].split(",").length < minChannels) continue;
            names.putIfAbsent(r[0], r[1]);
            // The channels decide; a brand is easily changed, so it only names the link
            byClient.computeIfAbsent(r[3], k -> new ArrayList<>()).add(r[0]);
            brands.putIfAbsent(r[3], r[2]);
        }
        for (Map.Entry<String, List<String>> e : byClient.entrySet()) {
            List<String> ids = e.getValue();
            if (ids.size() < 2 || ids.size() > maxPerClient) continue;
            int channels = e.getKey().split(",").length;
            String label = brands.get(e.getKey()) + ", " + channels + " channels";
            // The more channels, the rarer the set; shared by many it says less
            double chance = Math.min(0.85, 0.4 + 0.03 * channels) / Math.max(1.0, (ids.size() - 1) / 2.0);
            for (int i = 0; i < ids.size(); i++)
                for (int j = i + 1; j < ids.size(); j++)
                    edges.add(edge(ids.get(i), ids.get(j), "client", label, chance));
        }
        Map<String, String> rest = new LinkedHashMap<>(names);
        rest.values().removeIf(name -> rules.ignoreNames().stream().anyMatch(p -> p.matcher(name).find()));
        rest.keySet().removeAll(addPatternEdges(rest, rules, edges));
        if (rules.batches()) rest.keySet().removeAll(addBatchEdges(rest, lastSeen, rules, edges));
        if (rules.sameName()) addNameEdges(rest, maxPerClient, rules, edges);
        dropUnsupportedClientEdges(edges, rules.clientSupport());

        Map<String, Integer> degree = new LinkedHashMap<>();
        for (Map<String, Object> e : edges) {
            degree.merge((String) e.get("source"), 1, Integer::sum);
            degree.merge((String) e.get("target"), 1, Integer::sum);
        }
        int unlinked = 0;
        for (String id : names.keySet()) {
            if (unlinked >= maxUnlinked) break;
            if (degree.putIfAbsent(id, 0) == null) unlinked++;
        }
        Map<String, String> skins = players.getSkins(degree.keySet());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map.Entry<String, Integer> d : degree.entrySet()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", d.getKey());
            n.put("name", names.getOrDefault(d.getKey(), d.getKey()));
            List<String> mods = rows.scanMods().get(d.getKey());
            n.put("detected", mods != null && !mods.isEmpty());
            n.put("scanned", mods != null);
            n.put("mod_ids", mods != null ? mods : List.of());
            n.put("links", d.getValue());
            n.put("skin", skins.get(d.getKey()));
            n.put("last_seen", lastSeen.get(d.getKey()));
            n.put("ips", ipsOf.getOrDefault(d.getKey(), List.of()));
            nodes.add(n);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("edges", edges);
        return out;
    }

    /** One account for the graph's detail card: addresses it joined from, mods found on it and its RPTrack fingerprint. */
    public Map<String, Object> getLinkDetails(String uuid) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ips", players.getAddresses(uuid));
        // Looked up by key; this used to read every stored fingerprint to find one
        out.put("fingerprint", fingerprints.details(uuid));
        out.put("mods", scans.getDetectedModsOf(uuid));
        return out;
    }

    /**
     * Same channels alone say little: the link stays only where the pair's other links together reach
     * {@code support}. Call it again after changing a link's chance.
     */
    public static void dropUnsupportedClientEdges(List<Map<String, Object>> edges, double support) {
        Map<String, Double> miss = new HashMap<>();
        for (Map<String, Object> e : edges)
            if (!"client".equals(e.get("type"))) miss.merge(pairKey(e), 1 - ((Number) e.get("p")).doubleValue(), (x, y) -> x * y);
        edges.removeIf(e -> "client".equals(e.get("type")) && 1 - miss.getOrDefault(pairKey(e), 1.0) < support);
    }

    private static String pairKey(Map<String, Object> edge) {
        String a = (String) edge.get("source"), b = (String) edge.get("target");
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    /**
     * Bot batches: a generator gives every account the same fixed part between random parts of the same length, such as
     * u726_once_oh_v0l. Names cannot show that pair by pair, but a whole group can: at least {@code minBatch} accounts
     * with one template and different random parts. Seen within half an hour it is almost surely one program; otherwise
     * only a long part with random text on both sides counts, since killer228, killerpro and the like are ordinary names.
     * <p>
     * Members link to one of them, not to each other: a batch can be hundreds of accounts. An account named just the
     * fixed part links weakly, because anyone can put someone else's name into their bots.
     *
     * @return the batch members, whose names need no other comparison
     */
    private static Set<String> addBatchEdges(Map<String, String> names, Map<String, Long> lastSeen, Rules rules, List<Map<String, Object>> edges) {
        Map<String, List<String>> templates = new HashMap<>();
        Map<String, List<String>> plain = new HashMap<>();
        names.forEach((id, name) -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            plain.computeIfAbsent(lower.replaceAll("[^a-z0-9]", ""), k -> new ArrayList<>()).add(id);
            for (int start = 0; start <= MAX_BATCH_AFFIX; start++)
                for (int end = 0; end <= MAX_BATCH_AFFIX; end++) {
                    if (start + end < 2 || lower.length() - start - end < 3) continue;
                    templates.computeIfAbsent(start + "|" + end + "|" + lower.substring(start, lower.length() - end), k -> new ArrayList<>()).add(id);
                }
        });
        List<String> keys = new ArrayList<>(templates.keySet());
        // The largest group first, or a batch would split up on digits its members happen to share; of templates
        // covering the same accounts the longest fixed part describes them best
        keys.removeIf(k -> templates.get(k).size() < rules.minBatch());
        keys.sort(java.util.Comparator.<String>comparingInt(k -> -templates.get(k).size())
                .thenComparingInt(k -> -k.length()).thenComparing(k -> k));
        Set<String> used = new java.util.HashSet<>();
        for (String key : keys) {
            String[] parts = key.split("\\|", 3);
            int start = Integer.parseInt(parts[0]), end = Integer.parseInt(parts[1]);
            String fixed = parts[2], core = fixed.replaceAll("[^a-z0-9]", "");
            if (core.length() < 3) continue;
            List<String> ids = new ArrayList<>(templates.get(key));
            ids.removeAll(used);
            if (ids.size() < rules.minBatch()) continue;
            Set<String> affixes = new java.util.HashSet<>();
            int generated = 0;
            for (String id : ids) {
                String lower = names.get(id).toLowerCase(java.util.Locale.ROOT);
                String affix = lower.substring(0, start) + "|" + lower.substring(lower.length() - end);
                affixes.add(affix);
                if (affix.matches(".*[0-9].*")) generated++;
            }
            // Different, and mostly with digits: Pro, Bob and Top around a word are people's names
            if (affixes.size() < ids.size() * 0.8 || generated < ids.size() * 0.6) continue;
            double chance;
            if (inOneBurst(ids, lastSeen, rules.batchWindowMs())) chance = rules.batchTogether();
            else if (start > 0 && end > 0 && core.length() >= MIN_NAME_STEM) chance = rules.batchSpread();
            else continue;
            ids.sort(null);
            String label = "*".repeat(start) + fixed + "*".repeat(end) + ", " + ids.size();
            for (String id : ids.subList(1, ids.size())) edges.add(edge(ids.get(0), id, "batch", label, chance));
            used.addAll(ids);
            for (String named : plain.getOrDefault(core, List.of()))
                if (!ids.contains(named)) edges.add(edge(ids.get(0), named, "batch", label, rules.batchNamed()));
        }
        return used;
    }

    /** Accounts of the configured patterns, linked as a batch whenever they were seen. */
    private static Set<String> addPatternEdges(Map<String, String> names, Rules rules, List<Map<String, Object>> edges) {
        Set<String> used = new java.util.HashSet<>();
        for (BotPattern pattern : rules.patterns()) {
            List<String> ids = new ArrayList<>();
            names.forEach((id, name) -> {
                if (!used.contains(id) && pattern.regex().matcher(name).find()) ids.add(id);
            });
            if (ids.size() < 2) continue;
            ids.sort(null);
            String label = pattern.name() + ", " + ids.size();
            for (String id : ids.subList(1, ids.size())) edges.add(edge(ids.get(0), id, "batch", label, pattern.chance()));
            used.addAll(ids);
        }
        return used;
    }

    private static boolean inOneBurst(List<String> ids, Map<String, Long> lastSeen, long windowMs) {
        List<Long> times = new ArrayList<>();
        for (String id : ids) if (lastSeen.get(id) != null) times.add(lastSeen.get(id));
        times.sort(null);
        int most = 0;
        for (int from = 0, to = 0; to < times.size(); to++) {
            while (times.get(to) - times.get(from) > windowMs) from++;
            most = Math.max(most, to - from + 1);
        }
        return most >= ids.size() * 0.6;
    }

    /**
     * Links nearly equal names: differing only in case and symbols, only in digits, by one character, or one being the
     * start of the other. Names are grouped by key instead of compared in pairs, so large servers stay fast; a key many
     * accounts share (Player1, Player2...) says nothing and is skipped.
     */
    private static void addNameEdges(Map<String, String> names, int maxGroup, Rules rules, List<Map<String, Object>> edges) {
        Map<String, List<String>> exact = new HashMap<>(), stems = new HashMap<>(), oneOff = new HashMap<>();
        Map<String, String> normal = new HashMap<>();
        for (Map.Entry<String, String> account : names.entrySet()) {
            String id = account.getKey(), norm = account.getValue().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
            normal.put(id, norm);
            exact.computeIfAbsent(norm, k -> new ArrayList<>()).add(id);
            String stem = norm.replaceAll("[0-9]", "");
            if (stem.length() >= MIN_NAME_STEM) stems.computeIfAbsent(stem, k -> new ArrayList<>()).add(id);
            // Between short stems one character is all the difference there is: Bot_23647, Bot_23667
            if (norm.length() > MIN_NAME_STEM && stem.length() >= MIN_NAME_STEM)
                for (int i = 0; i < norm.length(); i++)
                    oneOff.computeIfAbsent(norm.substring(0, i) + norm.substring(i + 1), k -> new ArrayList<>()).add(id);
        }
        Map<String, double[]> best = new LinkedHashMap<>();
        Map<String, String[]> pairs = new HashMap<>();
        addNameGroups(exact, rules.symbols(), maxGroup, best, pairs);
        addNameGroups(stems, rules.digits(), maxGroup, best, pairs);
        addNameGroups(oneOff, rules.similar(), maxGroup, best, pairs);
        // SteveAlt and Steve: a name with a longer one starting with it
        for (Map.Entry<String, String> account : normal.entrySet()) {
            String norm = account.getValue();
            for (int length = MIN_NAME_PREFIX; length < norm.length(); length++) {
                List<String> shorter = exact.get(norm.substring(0, length));
                if (shorter == null || shorter.size() > maxGroup) continue;
                for (String other : shorter) addNamePair(account.getKey(), other, rules.similar(), best, pairs);
            }
        }
        best.forEach((key, chance) -> {
            String[] pair = pairs.get(key);
            edges.add(edge(pair[0], pair[1], "name", names.get(pair[0]) + " / " + names.get(pair[1]), chance[0]));
        });
    }

    private static void addNameGroups(Map<String, List<String>> groups, double chance, int maxGroup,
                                      Map<String, double[]> best, Map<String, String[]> pairs) {
        for (List<String> ids : groups.values()) {
            if (ids.size() < 2 || ids.size() > maxGroup) continue;
            for (int i = 0; i < ids.size(); i++)
                for (int j = i + 1; j < ids.size(); j++) addNamePair(ids.get(i), ids.get(j), chance, best, pairs);
        }
    }

    private static void addNamePair(String a, String b, double chance, Map<String, double[]> best, Map<String, String[]> pairs) {
        if (a.equals(b)) return;
        String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
        pairs.putIfAbsent(key, new String[]{a, b});
        double[] known = best.computeIfAbsent(key, k -> new double[]{0});
        known[0] = Math.max(known[0], chance);
    }

    private static Map<String, Object> edge(String a, String b, String type, String label, double p) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("source", a);
        e.put("target", b);
        e.put("type", type);
        e.put("label", label);
        e.put("p", p);
        return e;
    }
}
