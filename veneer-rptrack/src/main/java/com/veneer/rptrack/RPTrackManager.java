package com.veneer.rptrack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import com.veneer.core.Messages;
import com.veneer.core.db.FingerprintStore;
import com.veneer.VeneerPlugin;
import com.veneer.core.FoliaScheduler;
import com.veneer.core.VeneerModule;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.util.function.Function;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Resource-pack cache fingerprinting (technique described at
 * https://alaggydev.github.io/posts/cytooxien/).
 *
 * <p>The client keeps downloaded packs in {@code .minecraft/downloads}, keyed by SHA-1, across accounts.
 * On join we offer N probe packs with a known hash but an unreachable URL: a pack answers
 * SUCCESSFULLY_LOADED only when it is already cached. A fresh client gets a random subset of packs
 * downloaded for real; later logins on any account/IP reproduce that subset.
 */
public class RPTrackManager extends VeneerModule implements Listener {

    private static final String DEAD_URL = "http://127.0.0.1:0"; // Tracker RP Tester
    private static final String LB_URL = "http://127.0.0.1:15000/default/img/steve.png"; //For old LiquidBounce NextGen clients without ExploitPreventer
    private static final UUID HIDE_ID = UUID.nameUUIDFromBytes("veneer-hide".getBytes(StandardCharsets.UTF_8));
    private static final String[] LANGS = ("af_za ar_sa ast_es az_az bar ba_ru be_by be_latn bg_bg brb br_fr bs_ba ca_es cs_cz cv_cu "
            + "cy_gb da_dk de_at de_ch de_de el_gr en_au en_ca en_gb en_nz enp en_pt en_ud en_us enws eo_uy esan es_ar es_cl es_ec "
            + "es_es es_mx es_uy es_ve et_ee eu_es fa_ir fi_fi fil_ph fo_fo fra_de fr_ca fr_ch fr_fr fur_it fy_nl ga_ie gd_gb gl_es "
            + "go_fr got_de hal_ua haw_us he_il hi_in hn_no hr_hr hu_hu hy_am id_id ig_ng io_en is_is isv it_it ja_jp jbo_en ka_ge "
            + "kk_kz kn_in ko_kr ksh kw_gb ky_kg la_la lb_lu li_li lmo lo_la lol_us lt_lt lv_lv lzh mk_mk mn_mn ms_my mt_mt nah "
            + "nds_de nl_be nl_nl nn_no no_no oc_fr ovd pl_pl pls pt_br pt_pt qcb_es qid qya_aa ro_ro rpr ru_ru ry_ua sah_sah "
            + "se_no sk_sk sl_si so_so sq_al sr_cs sr_sp sv_se sxu szl ta_in th_th tlh_aa tl_ph tok tr_tr tt_ru tzo_mx uk_ua "
            + "uz_uz val_es vec_it vi_vn vp_vl vro yi_de yo_ng zh_cn zh_hk zh_tw zlm_arab").split(" ");
    private static final UUID LB_ID = UUID.nameUUIDFromBytes("veneer-lb".getBytes(StandardCharsets.UTF_8));

    private enum Phase { HIDE, PROBE, ASSIGN }

    private static final class Session {
        Phase phase = Phase.HIDE;
        final Map<UUID, Integer> idToProbe = new HashMap<>();
        final Set<Integer> pending = ConcurrentHashMap.newKeySet();
        final Set<Integer> ok = ConcurrentHashMap.newKeySet();
        volatile boolean lbPending, lbHit, declined, done;
        String base = "";
        /** Unique mode: the set picked for this client, held in {@link #reserved} until it is stored or dropped. */
        volatile long reservedMask;
    }

    /**
     * Gap, in ticks, between one resource-pack request settling and the next one going out. A client
     * that gets a new request too soon after the previous one leaves it unanswered; packs within the
     * same request are unaffected; verified on 1.21.6 (via ViaBackwards) and 1.21.11.
     */
    private static final long GRACE = 10L;

    private final ConfigurationSection cfg;
    private final boolean enabled, debug;
    /** public-url pointed at localhost: Veneer serves the packs itself on {@link #localPort}. */
    private final boolean local;
    private final int localPort;
    private final String scheme;
    private final int probes, assign, minMatch, delay, timeout;
    private final boolean lbProbe, alert;
    private final String publicUrl;
    private final byte[][] packs;
    private final byte[][] hashes;
    private final UUID[] ids;
    private byte[] hidePack, hideHash;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /**
     * Experimental, on while {@code debug} is: every client gets a set no stored set shares more than
     * {@code send - 2} packs with, so a client that still has a whole stored set is that account's machine (direct
     * link) and one with all but one pack of it most likely is (indirect link). {@code match} is not used.
     */
    private final boolean unique;
    /** Unique mode: sets handed out but not stored yet, so two clients joining together cannot get clashing sets. */
    private final Set<Long> reserved = ConcurrentHashMap.newKeySet();
    private HttpServer http;
    private final Map<UUID, Result> results = new ConcurrentHashMap<>();

    /** Last RPTrack outcome for an online player; used by placeholders. */
    public record Result(int packs, int linked, boolean liquidBounce) { }

    public Result getResult(UUID uuid) { return results.get(uuid); }

    public RPTrackManager(VeneerPlugin plugin) {
        super(plugin);
        this.cfg = plugin.config().section("rptrack");
        this.enabled = cfg.getBoolean("enabled", false);
        this.debug = cfg.getBoolean("debug", false);
        this.unique = debug;
        this.probes = Math.max(1, Math.min(62, cfg.getInt("track", 28)));
        this.assign = Math.max(1, Math.min(probes, cfg.getInt("send", 6)));
        this.minMatch = Math.max(1, Math.min(assign, cfg.getInt("match", 5)));
        if (cfg.getInt("send", 6) > probes || cfg.getInt("match", 5) > assign)
            plugin.getLogger().warning("[RPTrack] send must be <= track and match <= send; values were clamped to "
                    + "track=" + probes + ", send=" + assign + ", match=" + minMatch + ".");
        this.delay = cfg.getInt("delay-ticks", 40);
        this.timeout = cfg.getInt("timeout-ticks", 100);
        this.lbProbe = cfg.getBoolean("liquidbounce-probe", true);
        this.alert = cfg.getBoolean("alert", true);
        String url = cfg.getString("public-url", "").trim();
        if (!url.isEmpty() && !url.contains("://")) url = "http://" + url;
        boolean isLocal = false;
        int port = 8123;
        String sch = "http";
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            isLocal = host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1"));
            if (uri.getPort() != -1) port = uri.getPort();
            if (uri.getScheme() != null) sch = uri.getScheme();
        } catch (java.net.URISyntaxException e) {
            plugin.getLogger().warning("[RPTrack] invalid public-url: " + e.getMessage());
            url = "";
        }
        this.local = isLocal;
        this.localPort = port;
        this.scheme = sch;
        this.publicUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;

        packs = new byte[probes][];
        hashes = new byte[probes][];
        ids = new UUID[probes];
    }

    @Override public String id() { return "rptrack"; }

    @Override public boolean wanted() { return enabled; }

    @Override public List<String> commandNames() { return List.of("rptrack"); }

    @Override
    protected void enable() {
        command("rptrack", new RPTrackCommand(plugin));
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            hidePack = buildHidePack();
            hideHash = sha1.digest(hidePack);
            for (int i = 0; i < probes; i++) {
                packs[i] = buildPack(i + 1);
                hashes[i] = sha1.digest(packs[i]);
                ids[i] = UUID.nameUUIDFromBytes(("veneer-probe-" + (i + 1)).getBytes(StandardCharsets.UTF_8));
            }
            exportPacks();
            if (local) startHttp();
        } catch (Exception e) {
            plugin.getLogger().severe("[RPTrack] init failed: " + e.getMessage());
            if (http != null) http.stop(0);
            return;
        }
        if (publicUrl.isEmpty())
            plugin.getLogger().warning("[RPTrack] public-url is empty: packs cannot be sent to new clients.");
        else if (local)
            plugin.getLogger().info("[RPTrack] public-url is localhost: serving packs on port " + localPort
                    + ", clients are pointed at the host they joined with.");
        listen(this);
        plugin.getLogger().info("[RPTrack] enabled (" + probes + " tracked, " + assign + " sent, "
                + (unique ? "unique sets (experimental, debug mode)" : "match " + minMatch)
                + (local ? ", serving on port " + localPort : ", packs exported to plugins/" + plugin.getName() + "/rptrack") + ")");
    }

    /** Base URL the given player downloads packs from, or "" when none is available. */
    private String baseUrl(Player player) {
        if (!local) return publicUrl;
        java.net.InetSocketAddress vh = player.getVirtualHost();
        String host = vh != null ? vh.getHostString() : (Bukkit.getIp().isEmpty() ? null : Bukkit.getIp());
        return host == null ? "" : scheme + "://" + host + ":" + localPort;
    }

    private void debug(String msg) {
        if (debug) plugin.getLogger().info("[RPTrack/debug] " + msg);
    }

    /** Whether probing is actually active (the built-in server, when needed, came up). */
    public boolean isEnabled() { return running() && (!local || http != null); }

    @Override
    protected void disable() {
        // Mark live sessions done first: the queued probe/assign tasks check s.done and stop sending packs
        for (Session s : sessions.values()) s.done = true;
        if (http != null) http.stop(0);
        http = null;
        sessions.clear();
        results.clear();
        reserved.clear();
    }

    @Override
    protected void reload() {
        plugin.getLogger().info("[RPTrack] settings are read at startup: restart the server to apply changes.");
    }

    /** Stored-in-zip, fixed timestamps: bytes (and so the SHA-1) never change between runs. */
    private static byte[] buildPack(int n) throws Exception {
        byte[] meta = ("{\"pack\":{\"pack_format\":46,\"description\":\"" + n + "\"}}").getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(meta);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            ZipEntry e = new ZipEntry("pack.mcmeta");
            e.setMethod(ZipEntry.STORED);
            e.setSize(meta.length);
            e.setCompressedSize(meta.length);
            e.setCrc(crc.getValue());
            e.setTime(946684800000L);
            zos.putNextEntry(e);
            zos.write(meta);
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    /**
     * Blanks the "failed to download" toast: empty text for every language plus a transparent toast
     * background, so failed probes are invisible to the player.
     */
    private static byte[] buildHidePack() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("pack.mcmeta", "{\"pack\":{\"pack_format\":46,\"supported_formats\":[22,1000],\"description\":\"\"}}"
                .getBytes(StandardCharsets.UTF_8));
        byte[] lang = "{\"download.pack.failed\":\"\",\"download.pack.title\":\"\"}".getBytes(StandardCharsets.UTF_8);
        for (String l : LANGS) files.put("assets/minecraft/lang/" + l + ".json", lang);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(160, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", png);
        files.put("assets/minecraft/textures/gui/sprites/toast/system.png", png.toByteArray());

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                CRC32 crc = new CRC32();
                crc.update(f.getValue());
                ZipEntry e = new ZipEntry(f.getKey());
                e.setMethod(ZipEntry.STORED);
                e.setSize(f.getValue().length);
                e.setCompressedSize(f.getValue().length);
                e.setCrc(crc.getValue());
                e.setTime(946684800000L);
                zos.putNextEntry(e);
                zos.write(f.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** Writes the packs to plugins/Veneer/rptrack/fp/ so they can be hosted elsewhere under public-url. */
    private void exportPacks() throws java.io.IOException {
        java.io.File dir = new java.io.File(plugin.getDataFolder(), "rptrack/fp");
        dir.mkdirs();
        java.nio.file.Files.write(new java.io.File(dir, "hide.zip").toPath(), hidePack);
        for (int i = 0; i < probes; i++)
            java.nio.file.Files.write(new java.io.File(dir, (i + 1) + ".zip").toPath(), packs[i]);
    }

    private void startHttp() throws Exception {
        http = HttpServer.create(new InetSocketAddress(localPort), 0);
        http.createContext("/fp/", ex -> {
            try (ex) {
                String path = ex.getRequestURI().getPath().substring("/fp/".length());
                if (path.equals("hide.zip")) {
                    ex.getResponseHeaders().add("Content-Type", "application/zip");
                    ex.sendResponseHeaders(200, hidePack.length);
                    ex.getResponseBody().write(hidePack);
                    return;
                }
                int n = -1;
                try { n = Integer.parseInt(path.replace(".zip", "")); } catch (NumberFormatException ignored) { }
                if (n < 1 || n > probes) { ex.sendResponseHeaders(404, -1); return; }
                byte[] body = packs[n - 1];
                ex.getResponseHeaders().add("Content-Type", "application/zip");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
        });
        http.start();
    }

    // ---------------------------------------------------------------- flow

    @Override
    public void playerJoin(Player player) {
        if (plugin.bedrock().isBedrock(player) || plugin.bypasses(player, "rptrack", true)) return;
        FoliaScheduler.runAtEntityLater(plugin, player, () -> startHide(player), delay);
    }

    @Override
    public void playerQuit(java.util.UUID uuid) {
        Session s = sessions.remove(uuid);
        if (s != null) reserved.remove(s.reservedMask);
        results.remove(uuid);
    }

    private void startHide(Player player) {
        if (!player.isOnline()) return;
        Session s = new Session();
        sessions.put(player.getUniqueId(), s);
        String base = baseUrl(player);
        if (base.isEmpty()) {
            debug(player.getName() + ": no pack url, skipping toast-hiding pack");
            startProbe(player, s);
            return;
        }
        debug(player.getName() + ": sending toast-hiding pack from " + base);
        send(player, List.of(packInfo(HIDE_ID, base + "/fp/hide.zip", hideHash)));
        FoliaScheduler.runAtEntityLater(plugin, player, () -> startProbe(player, s), timeout);
    }

    private synchronized void startProbe(Player player, Session s) {
        if (!player.isOnline() || s.done || s.phase != Phase.HIDE) return;
        s.phase = Phase.PROBE;
        // A grace gap after the hide pack settling: see GRACE.
        FoliaScheduler.runAtEntityLater(plugin, player, () -> {
            if (!player.isOnline() || s.done) return;
            debug(player.getName() + ": probing " + probes + " packs" + (lbProbe ? " + liquidbounce" : ""));
            List<net.kyori.adventure.resource.ResourcePackInfo> infos = new ArrayList<>();
            for (int i = 0; i < probes; i++) {
                s.idToProbe.put(ids[i], i);
                s.pending.add(i);
                infos.add(packInfo(ids[i], DEAD_URL, hashes[i]));
            }
            s.lbPending = lbProbe;
            if (lbProbe) infos.add(packInfo(LB_ID, LB_URL, null));
            send(player, infos);
            FoliaScheduler.runAtEntityLater(plugin, player, () -> finishProbe(player, s), timeout);
        }, GRACE);
    }

    private static net.kyori.adventure.resource.ResourcePackInfo packInfo(UUID id, String url, byte[] hash) {
        String hex = hash == null ? "" : HexFormat.of().formatHex(hash);
        return net.kyori.adventure.resource.ResourcePackInfo.resourcePackInfo(id, java.net.URI.create(url), hex);
    }

    private static void send(Player player, List<net.kyori.adventure.resource.ResourcePackInfo> infos) {
        player.sendResourcePacks(net.kyori.adventure.resource.ResourcePackRequest.resourcePackRequest()
                .packs(infos).required(true).replace(false).build());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.done) return;
        UUID id = event.getID();
        if (id.equals(HIDE_ID)) {
            switch (event.getStatus()) {
                case ACCEPTED, DOWNLOADED -> { }
                default -> startProbe(player, s);
            }
            return;
        }
        if (s.phase == Phase.HIDE) return;
        boolean isLb = id.equals(LB_ID);
        Integer probe = s.idToProbe.get(id);
        if (!isLb && probe == null) return;
        if (!isLb && !s.pending.contains(probe)) return;   // late answer for a pack that is already settled

        debug(player.getName() + ": " + s.phase + " " + (isLb ? "liquidbounce" : "#" + (probe + 1)) + " -> " + event.getStatus());
        boolean success;
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> success = true;
            // The reference PoC (ALaggyDev/TrackPack) reads DISCARDED as "served from the cache".
            case DISCARDED -> { if (s.phase != Phase.PROBE) return; success = true; }
            // Observed on 1.21.11: a probe with a dead URL answers DOWNLOADED only when the pack is already
            // in the client's cache, and a freshly served pack answers DOWNLOADED once it is stored.
            case DOWNLOADED -> success = true;
            case ACCEPTED -> { return; }
            case DECLINED -> { s.declined = true; success = false; }
            default -> success = false;
        }

        if (isLb) {
            s.lbPending = false;
            s.lbHit = success;
        } else {
            s.pending.remove(probe);
            if (success) s.ok.add(probe);
        }
        if (s.pending.isEmpty() && !s.lbPending) {
            if (s.phase == Phase.PROBE) finishProbe(player, s);
            else finishAssign(player, s);
        }
    }

    private synchronized void finishProbe(Player player, Session s) {
        if (s.done || s.phase != Phase.PROBE || !player.isOnline()) return;
        removeAll(player, s);
        if (s.declined) { s.done = true; sessions.remove(player.getUniqueId()); return; }

        Set<Integer> cached = new TreeSet<>(s.ok);
        boolean lb = s.lbHit;
        if (debug) {
            Set<Integer> cachedOneBased = new TreeSet<>();
            for (int i : cached) cachedOneBased.add(i + 1);
            Set<Integer> silent = new TreeSet<>();
            for (int i : s.pending) silent.add(i + 1);
            debug(player.getName() + ": probe done, cached=" + cachedOneBased + ", no answer=" + silent
                    + ", liquidbounce=" + lb + ", declined=" + s.declined);
        }
        if (lb) report(player, "rptrack-text-liquidbounce", List.of());

        if (unique) {
            // Not PROBE any more, so the probe timeout cannot run this twice while the database is read
            s.phase = Phase.ASSIGN;
            s.lbHit = lb;
            if (cached.isEmpty()) assignUnique(player, s, "fresh client");
            else analyseUnique(player, s, maskOf(cached), lb);
            return;
        }

        // Too few cached packs to link anything (fresh client, or an earlier assign that only partly arrived):
        // hand out a set for real; the stored fingerprint is merged with it, not replaced
        if (cached.size() < minMatch) {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < probes; i++) all.add(i);
            Collections.shuffle(all);
            s.lbHit = lb;
            startAssign(player, s, all.subList(0, assign),
                    cached.isEmpty() ? "fresh client" : cached.size() + " cached only");
        } else {
            s.done = true;
            sessions.remove(player.getUniqueId());
            analyse(player, maskOf(cached), lb);
        }
    }

    /** Sends {@code set} for real; {@link #finishAssign} stores what the client loaded. */
    private void startAssign(Player player, Session s, List<Integer> set, String why) {
        String base = baseUrl(player);
        if (base.isEmpty()) {
            s.done = true;
            sessions.remove(player.getUniqueId());
            reserved.remove(s.reservedMask);
            debug(player.getName() + ": " + why + " but no pack url, nothing assigned");
            return;
        }
        s.phase = Phase.ASSIGN;
        s.ok.clear();
        s.pending.clear();
        s.lbPending = false;
        s.pending.addAll(set);
        s.base = base;
        FoliaScheduler.runAtEntityLater(plugin, player, () -> {
            if (!player.isOnline() || s.done) return;
            debug(player.getName() + ": " + why + ", sending " + set.size() + " packs");
            List<net.kyori.adventure.resource.ResourcePackInfo> infos = new ArrayList<>();
            for (int i : s.pending) infos.add(packInfo(ids[i], s.base + "/fp/" + (i + 1) + ".zip", hashes[i]));
            send(player, infos);
            FoliaScheduler.runAtEntityLater(plugin, player, () -> finishAssign(player, s), timeout);
        }, GRACE);
    }

    /** Unique mode: picks a set off the main thread, then hands it out on the player's thread. */
    private void assignUnique(Player player, Session s, String why) {
        FoliaScheduler.runAsync(plugin, () -> {
            List<Long> stored = new ArrayList<>();
            for (FingerprintStore.Fingerprint fp : plugin.database().fingerprints().masks())
                stored.add(fp.mask());
            long mask = pickUnique(stored);
            s.reservedMask = mask;
            List<Integer> set = new ArrayList<>();
            for (int i = 0; i < probes; i++) if ((mask >>> i & 1) != 0) set.add(i);
            FoliaScheduler.runAtEntity(plugin, player, () -> {
                if (!player.isOnline() || s.done) { reserved.remove(mask); return; }
                startAssign(player, s, set, why);
            });
        });
    }

    /** Attempts at a random set before settling for the least clashing one seen. */
    private static final int PICK_TRIES = 5000;

    /** A random {@code send}-pack set sharing at most {@code send - 2} packs with every stored and reserved set. */
    private long pickUnique(List<Long> stored) {
        int maxCommon = Math.max(0, assign - 2);
        Random rnd = java.util.concurrent.ThreadLocalRandom.current();
        synchronized (reserved) {
            List<Long> taken = new ArrayList<>(stored);
            taken.addAll(reserved);
            long best = 0;
            int bestWorst = Integer.MAX_VALUE;
            for (int attempt = 0; attempt < PICK_TRIES; attempt++) {
                long m = 0;
                while (Long.bitCount(m) < assign) m |= 1L << rnd.nextInt(probes);
                int worst = 0;
                for (long o : taken) {
                    worst = Math.max(worst, Long.bitCount(m & o));
                    if (worst > maxCommon) break;
                }
                if (worst <= maxCommon) { best = m; bestWorst = worst; break; }
                if (worst < bestWorst) { best = m; bestWorst = worst; }
            }
            if (bestWorst > maxCommon)
                plugin.getLogger().warning("[RPTrack] no set of " + assign + " packs out of " + probes + " is free any "
                        + "more (best shares " + bestWorst + "): raise track or send.");
            reserved.add(best);
            return best;
        }
    }

    /** Unique mode: stored sets fully (direct) or all but one pack (indirect) in a client's cache. */
    private record Links(long own, List<String> direct, List<String> indirect) { }

    private Links linksOf(String uuid, long cache) {
        long own = 0;
        List<String> direct = new ArrayList<>(), indirect = new ArrayList<>();
        for (FingerprintStore.Fingerprint fp : plugin.database().fingerprints().masks()) {
            long other = fp.mask();
            if (uuid.equals(fp.uuid())) { own = other; continue; }
            int size = Long.bitCount(other), common = Long.bitCount(cache & other);
            // Under three packs a set proves nothing, and one missing pack would leave too little
            if (size < 3) continue;
            if (common == size) direct.add(fp.name());
            else if (common == size - 1) indirect.add(fp.name());
        }
        return new Links(own, direct, indirect);
    }

    /** Unique mode: a client that has cached packs. */
    private void analyseUnique(Player player, Session s, long cache, boolean lb) {
        String uuid = player.getUniqueId().toString();
        FoliaScheduler.runAsync(plugin, () -> {
            Links l = linksOf(uuid, cache);
            debug(player.getName() + ": direct " + l.direct() + ", indirect " + l.indirect()
                    + (l.own() != 0 ? ", own set " + Long.bitCount(cache & l.own()) + "/" + Long.bitCount(l.own()) + " cached" : ", no own set"));
            int ownSize = Long.bitCount(l.own());
            boolean ownLost = l.own() == 0 || Long.bitCount(cache & l.own()) < ownSize - 1;
            if (ownLost && l.direct().isEmpty() && l.indirect().isEmpty()) {
                // Leftovers of an assign that did not fully arrive, or an own set mostly evicted: they match
                // nobody, so this client gets a new set (it replaces the stored one)
                FoliaScheduler.runAtEntity(plugin, player, () -> {
                    if (player.isOnline() && !s.done) assignUnique(player, s, "cached packs match nobody");
                });
                return;
            }
            s.done = true;
            sessions.remove(player.getUniqueId());
            // results.put on the entity thread: playerQuit may have cleaned up while this ran async,
            // and an entry added after it would stay forever
            Result result = new Result(Long.bitCount(l.own() != 0 ? l.own() : cache), l.direct().size(), lb);
            FoliaScheduler.runAtEntity(plugin, player, () -> {
                if (player.isOnline()) results.put(player.getUniqueId(), result);
            });
            // A known account keeps its own set (anything else in the cache is another account's); a new one on
            // a known machine is stored with that machine's packs
            plugin.database().fingerprints().save(uuid, player.getName(), l.own() != 0 ? l.own() : cache, lb, false);
            if (!l.direct().isEmpty() && alert)
                report(player, "rptrack-text-same-cache", l.direct());
            if (!l.indirect().isEmpty() && alert)
                report(player, "rptrack-text-probably-same", l.indirect());
        });
    }

    private synchronized void finishAssign(Player player, Session s) {
        if (s.done || s.phase != Phase.ASSIGN) return;
        s.done = true;
        sessions.remove(player.getUniqueId());
        if (player.isOnline()) removeAll(player, s);
        long held = s.reservedMask;
        // Unique mode stores whole sets only: a partial one could sit inside a set handed out later.
        // The packs that did arrive match nobody, so the next join hands out a new set.
        boolean store = unique ? s.ok.size() == assign : !s.ok.isEmpty();
        debug(player.getName() + ": assign done, loaded " + s.ok.size() + "/" + assign
                + (store ? ", stored" : ", not stored"));
        if (!store) { reserved.remove(held); return; }
        long mask = maskOf(s.ok);
        // On the entity thread here, like playerQuit: skip the put when the account already left, else its entry would linger
        if (player.isOnline()) results.put(player.getUniqueId(), new Result(s.ok.size(), 0, s.lbHit));
        // Unique mode replaces: an account whose cache was wiped gets a new set, and two merged sets would break the rule.
        // The reservation is dropped once the set is in the database, so no pick in between misses it.
        FoliaScheduler.runAsync(plugin, () -> {
            plugin.database().fingerprints().save(player.getUniqueId().toString(), player.getName(), mask, s.lbHit, unique);
            reserved.remove(held);
        });
    }

    private void removeAll(Player player, Session s) {
        for (UUID id : s.idToProbe.keySet()) player.removeResourcePack(id);
        player.removeResourcePack(LB_ID);
    }

    private static long maskOf(Collection<Integer> set) {
        long m = 0;
        for (int i : set) m |= 1L << i;
        return m;
    }

    /** Cached subset seen on a returning client: compare against known accounts. */
    private void analyse(Player player, long mask, boolean lb) {
        String uuid = player.getUniqueId().toString();
        FoliaScheduler.runAsync(plugin, () -> {
            List<String> linked = findLinked(uuid, mask);
            long own = getMask(uuid);
            if (own != 0)
                debug(player.getName() + ": stability vs own stored fingerprint: " + Long.bitCount(mask & own) + "/"
                        + Long.bitCount(own) + " packs still cached, " + Long.bitCount(mask & ~own) + " extra");
            debug(player.getName() + ": linked accounts " + linked);
            results.put(player.getUniqueId(), new Result(Long.bitCount(mask | own), linked.size(), lb));
            plugin.database().fingerprints().save(uuid, player.getName(), mask | own, lb, false);
            if (!linked.isEmpty() && alert)
                report(player, "rptrack-text-same-cache", linked);
        });
    }

    /**
     * Names of other accounts whose stored fingerprint overlaps this one by at least min-match packs. A fingerprint
     * with fewer packs never links: one or two shared packs are common among strangers.
     */
    public List<String> findLinked(String uuid, long mask) {
        if (unique) {
            Links l = linksOf(uuid, mask);
            List<String> out = new ArrayList<>(l.direct());
            for (String n : l.indirect()) out.add(n + " (indirect)");
            return out;
        }
        List<String> out = new ArrayList<>();
        for (FingerprintStore.Fingerprint fp : plugin.database().fingerprints().masks()) {
            if (uuid.equals(fp.uuid())) continue;
            if (Long.bitCount(mask & fp.mask()) >= minMatch)
                out.add(fp.name());
        }
        return out;
    }

    public long getMask(String uuid) {
        return plugin.database().fingerprints().maskOf(uuid);
    }

    /** Logs a finding in the server language and alerts staff, each in their own language ({@code key} takes {@code {players}}). */
    private void report(Player player, String key, Collection<String> players) {
        String names = String.join(", ", players);
        plugin.getLogger().info("[RPTrack] " + player.getName() + ": "
                + PlainTextComponentSerializer.plainText()
                        .serialize(plugin.messages().with(key, "players", names)));
        if (alert) plugin.alerts().sendToPlayers(m -> m.with("rptrack-report", "player", player.getName(),
                "text", new Messages.Raw(m.plain(key, "players", names))));
        if (cfg.getBoolean("discord.enabled", false)) {
            String text = PlainTextComponentSerializer.plainText().serialize(plugin.messages().with(key, "players", names));
            plugin.webhook().report(plugin.config().webhook(cfg.getString("discord.webhook-url", "")),
                    com.veneer.core.Webhook.color(cfg.getString("discord.embed-color"), 0x9B59B6),
                    cfg.getString("discord.title", "Veneer RPTrack"),
                    cfg.getString("discord.message", "**Player:** %player%\n**Finding:** %text%"),
                    cfg.getBoolean("discord.use-components-v2", true), player,
                    Map.of("player", player.getName(), "uuid", player.getUniqueId().toString(), "text", text,
                            "players", names, "version", com.veneer.core.ClientVersions.name(player)));
        }
    }
}
