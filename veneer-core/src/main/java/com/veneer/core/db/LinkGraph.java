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

    private record Rows3(List<String[]> joins, List<String[]> clients, Set<String> detected) {}

    /**
     * Graph of linked accounts: {@code nodes} (id, name, detected, links, skin) and {@code edges} (source, target,
     * type "ip", "rptrack" or "client", label, p = how likely the two accounts are one person, 0 to 1). An address
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
        return getLinkGraph(maxPerIp, minMatch, maxPerClient, minChannels, 0);
    }

    /** As above, plus up to {@code maxUnlinked} accounts without a link, so a lone account still shows in the graph. */
    public Map<String, Object> getLinkGraph(int maxPerIp, int minMatch, int maxPerClient, int minChannels, int maxUnlinked) {
        Rows3 rows = db.read("getLinkGraph", new Rows3(List.of(), List.of(), Set.of()), c -> {
            List<String[]> joinRows = new ArrayList<>(), clientRows = new ArrayList<>();
            try (Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT uuid, name, ip, last_seen FROM joins ORDER BY last_seen DESC")) {
                    while (rs.next()) joinRows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
                }
                try (ResultSet rs = st.executeQuery("SELECT uuid, name, brand, channels FROM clients")) {
                    while (rs.next()) clientRows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
                }
            }
            return new Rows3(joinRows, clientRows, scans.detectedAccounts(c));
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
        for (String[] r : rows.clients()) {
            if (r[3].isEmpty() || r[3].split(",").length < minChannels) continue;
            names.putIfAbsent(r[0], r[1]);
            byClient.computeIfAbsent(r[2] + "\n" + r[3], k -> new ArrayList<>()).add(r[0]);
        }
        for (Map.Entry<String, List<String>> e : byClient.entrySet()) {
            List<String> ids = e.getValue();
            if (ids.size() < 2 || ids.size() > maxPerClient) continue;
            String[] key = e.getKey().split("\n", 2);
            String label = key[0] + ", " + key[1].split(",").length + " channels";
            for (int i = 0; i < ids.size(); i++)
                for (int j = i + 1; j < ids.size(); j++)
                    edges.add(edge(ids.get(i), ids.get(j), "client", label, 0.3 / Math.max(1.0, (ids.size() - 1) / 2.0)));
        }

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
            n.put("detected", rows.detected().contains(d.getKey()));
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
