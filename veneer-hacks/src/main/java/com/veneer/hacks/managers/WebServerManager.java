package com.veneer.hacks.managers;

import com.google.gson.JsonParser;
import com.veneer.hacks.HacksModule;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.veneer.VeneerPlugin;
import com.veneer.hacks.HackDefinition;
import com.veneer.hacks.commands.HackEditorCommand;
import com.veneer.core.FoliaScheduler;
import com.veneer.core.IpDatabase;
import com.veneer.core.SelfSignedTls;
import com.veneer.core.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebServerManager {

    private volatile String tlsFingerprint;

    private final VeneerPlugin plugin;
    private HttpServer server;
    private ExecutorService executor;
    private static final int MAX_BODY = 4096;
    private final Gson gson = new Gson();
    private volatile List<Map<String, Object>> cachedPlayers = new ArrayList<>();
    private final IpDatabase ipdb;
    /** Open /api/events streams; each has its own thread, so they cannot starve the request pool. */
    private static final int MAX_STREAMS = 16;
    private final Set<java.util.concurrent.BlockingQueue<String>> streams = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ExecutorService streamThreads = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Veneer-panel-events");
        t.setDaemon(true);
        return t;
    });

    public WebServerManager(VeneerPlugin plugin) {
        this.plugin = plugin;
        var links = links();
        if (links.getBoolean("ip-database", true)) {
            ipdb = new IpDatabase(plugin, links.getInt("ip-database-refresh-days", 7),
                    links.getIntegerList("weak-provider-asns"), links.getStringList("weak-provider-keywords"));
            ipdb.start();
        } else {
            ipdb = null;
        }
        start();
        FoliaScheduler.runGlobalTimer(plugin, this::updatePlayerCache, 1L, 100L);
        FoliaScheduler.runGlobalTimer(plugin, this::checkAccess, 1200L, 6000L);
        if (plugin.module(HacksModule.class).config().isPanelExposed())
            plugin.getLogger().warning("The web panel is on a public address without HTTPS: its login cookie can be read on the network. "
                    + "Put an HTTPS proxy in front and set hacks.web-editor.host to the https:// address.");
    }

    private void start() {
        int port = plugin.module(HacksModule.class).config().getWebPort();
        try {
            String bind = plugin.module(HacksModule.class).config().getWebBind();
            InetSocketAddress address = new InetSocketAddress(java.net.InetAddress.getByName(bind), port);
            // A failure here leaves the panel off: it must not quietly fall back to plain HTTP
            if (plugin.module(HacksModule.class).config().isWebHttps()) {
                SelfSignedTls tls = SelfSignedTls.load(plugin.getDataFolder().toPath().resolve("web-https.p12"), tlsNames(bind));
                HttpsServer secure = HttpsServer.create(address, 0);
                secure.setHttpsConfigurator(new HttpsConfigurator(tls.context()));
                server = secure;
                tlsFingerprint = tls.fingerprint();
                plugin.getLogger().info("Web panel HTTPS certificate SHA-256: " + tlsFingerprint);
            } else {
                server = HttpServer.create(address, 0);
                tlsFingerprint = null;
            }
            server.createContext("/", this::handle);
            executor = Executors.newFixedThreadPool(4);
            server.setExecutor(executor);
            server.start();
            plugin.getLogger().info("Web panel listening on " + bind + ":" + port);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to start the web panel: " + e.getMessage());
        }
    }

    /** The addresses the panel can be opened at: the certificate lists them. */
    private List<String> tlsNames(String bind) {
        Set<String> names = new LinkedHashSet<>(List.of("localhost", "127.0.0.1"));
        String host = java.net.URI.create(settings().getPanelUrl()).getHost();
        if (host != null) names.add(host.replaceAll("[\\[\\]]", ""));
        if (!bind.equals("0.0.0.0") && !bind.equals("::")) names.add(bind);
        return new ArrayList<>(names);
    }

    /** SHA-256 of the panel's own certificate, or null when it does not serve HTTPS itself. */
    public String tlsFingerprint() { return tlsFingerprint; }

    private org.bukkit.configuration.ConfigurationSection links() {
        return plugin.config().section("hacks.web-editor.links");
    }

    /** Adds provider, ASN and country of {@code ip} to {@code row}; true when it is a VPN/WARP/hosting address. */
    private boolean describeIp(String ip, Map<String, Object> row) {
        IpDatabase.Info info = ipdb != null ? ipdb.lookup(ip) : null;
        if (info == null) return false;
        row.put("org", info.org());
        row.put("asn", info.asn());
        row.put("country", info.country());
        row.put("weak", info.weak());
        return info.weak();
    }

    private void updatePlayerCache() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("uuid", p.getUniqueId().toString());
            m.put("ping", p.getPing());
            m.put("locale", p.getLocale());
            list.add(m);
        }
        cachedPlayers = list;
    }

    private ConfigManager settings() { return plugin.module(HacksModule.class).config(); }

    /** The cookie is per port too: browsers share cookies between ports of one host. */
    private String cookieName() { return "veneer_session_" + settings().getWebPort(); }

    private String sessionOf(HttpExchange ex) {
        String name = cookieName() + "=";
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", List.of()))
            for (String part : header.split(";")) {
                String cookie = part.strip();
                if (cookie.startsWith(name)) return cookie.substring(name.length());
            }
        return null;
    }

    private static String ipOf(HttpExchange ex) {
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private Map<String, String> authenticate(HttpExchange ex) {
        return plugin.database().tokens().validateSession(sessionOf(ex), ipOf(ex), settings().getSessionExpireDays());
    }

    /** JavaScript cannot read the cookie, other sites do not send it on requests that change something. */
    private void setCookie(HttpExchange ex, String session) {
        ex.getResponseHeaders().add("Set-Cookie", cookieName() + "=" + session + "; Path=/; HttpOnly; SameSite=Lax; Max-Age="
                + settings().getSessionExpireDays() * 86_400L + (settings().isPanelHttps() ? "; Secure" : ""));
    }

    /** A cookie is sent whoever made the page, so a change must come from the panel itself. */
    private boolean sameOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return false;
        if (origin.equalsIgnoreCase(settings().getPanelUrl())) return true;
        try {
            return String.valueOf(java.net.URI.create(origin).getAuthority()).equalsIgnoreCase(ex.getRequestHeaders().getFirst("Host"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** One-time link: starts this browser's session and leaves the secret out of the address and history. */
    private void connect(HttpExchange ex, Map<String, String> params) throws IOException {
        var redeemed = plugin.database().tokens().redeemLink(params.get("code"), ipOf(ex), settings().getSessionExpireDays());
        if (redeemed == null) {
            sendHtml(ex, 403, messagePage("This link is invalid, expired or already used.", "Run <b>/hackeditor new</b> in Minecraft for a new one. If you did not open it yourself, someone else may have: check <b>/hackeditor links</b>."));
            return;
        }
        String ip = ipOf(ex), name = redeemed.playerName();
        plugin.getLogger().info("[Hacks] Web panel: a new browser was connected to " + name + " from " + ip);
        UUID owner = UUID.fromString(redeemed.playerUuid());
        FoliaScheduler.runGlobal(plugin, () -> plugin.alerts().notifyOwner(owner,
                m -> m.get("editor-browser-connected", Map.of("player", name, "ip", ip, "id", redeemed.id()))));
        setCookie(ex, redeemed.session());
        ex.getResponseHeaders().set("Location", "/connected");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(303, -1);
    }

    /** Page texts are keyed by their English wording in web/lang/<language>.json, like the panel itself. */
    private String messagePage(String title, String detail) {
        var lang = JsonParser.parseString(translations()).getAsJsonObject();
        if (lang.has(title)) title = lang.get(title).getAsString();
        if (lang.has(detail)) detail = lang.get(detail).getAsString();
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Veneer</title></head>"
                + "<body style='font-family:system-ui,sans-serif;background:#090d16;color:#f1f5f9;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;padding:16px;box-sizing:border-box'>"
                + "<div style='max-width:460px'><h1 style='font-size:20px'>" + title + "</h1><p style='color:#94a3b8;line-height:1.5'>" + detail + "</p></div></body></html>";
    }

    /** Rights can change while a browser stays connected: every few minutes they are read again, and lost access ends the sessions. */
    private void checkAccess() {
        Map<String, String> online = new HashMap<>();
        Set<String> allowed = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            String id = p.getUniqueId().toString();
            online.put(id, HackEditorCommand.webPerms(p));
            if (p.hasPermission("veneer.hacks.editor")) allowed.add(id);
        }
        FoliaScheduler.runAsync(plugin, () -> {
            var tokens = plugin.database().tokens();
            for (String owner : tokens.owners()) {
                if (online.containsKey(owner)) {
                    applyAccess(owner, allowed.contains(owner), online.get(owner));
                    continue;
                }
                List<String> nodes = new ArrayList<>(List.of("veneer.hacks.editor"));
                for (String perm : HackEditorCommand.WEB_PERMS) nodes.add("veneer.web." + perm);
                var lookup = plugin.permissions().offlinePermissions(UUID.fromString(owner), nodes);
                if (lookup == null) continue;
                lookup.thenAccept(granted -> applyAccess(owner, granted.contains("veneer.hacks.editor"),
                        String.join(",", HackEditorCommand.WEB_PERMS.stream().filter(p -> granted.contains("veneer.web." + p)).toList())));
            }
        });
    }

    private void applyAccess(String owner, boolean allowed, String perms) {
        var tokens = plugin.database().tokens();
        if (allowed) {
            tokens.updatePerms(owner, perms);
        } else if (tokens.revokeByUuid(owner) > 0) {
            plugin.getLogger().info("[Hacks] Web panel: " + owner + " lost veneer.hacks.editor, browsers disconnected.");
        }
    }

    /** Sends a scan event to every open panel. */
    public void publishScan(String player, String type, String state, int count) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("player", player);
        event.put("type", type);
        event.put("state", state);
        event.put("count", count);
        String json = gson.toJson(event);
        for (var queue : streams) queue.offer(json);
    }

    /** Server-sent events: one way is all the panel needs, and the JDK server has no WebSocket support. */
    private void openStream(HttpExchange ex) throws IOException {
        if (authenticate(ex) == null) {
            sendJson(ex, 401, Map.of("error", "unauthorized"));
            ex.close();
            return;
        }
        if (streams.size() >= MAX_STREAMS) {
            sendJson(ex, 429, Map.of("error", "too many open panels"));
            ex.close();
            return;
        }
        var queue = new java.util.concurrent.LinkedBlockingQueue<String>(256);
        streams.add(queue);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);
        streamThreads.execute(() -> {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                while (!Thread.currentThread().isInterrupted()) {
                    String event = queue.poll(15, java.util.concurrent.TimeUnit.SECONDS);
                    if (event == null && authenticate(ex) == null) break;
                    os.write((event != null ? "event: scan\ndata: " + event + "\n\n" : ": alive\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (IOException | InterruptedException ignored) {
                // The panel was closed, or the server is stopping
            } finally {
                streams.remove(queue);
                ex.close();
            }
        });
    }

    private void handle(HttpExchange ex) {
        if (plugin.database().tokens().isBlocked(ipOf(ex))) {
            try {
                sendHtml(ex, 403, messagePage("Access denied", "This address is blocked from the panel."));
            } catch (IOException ignored) {
                // The client went away
            } finally {
                ex.close();
            }
            return;
        }
        if (ex.getRequestURI().getPath().equals("/api/events")) {
            try {
                addSecurityHeaders(ex);
                openStream(ex);
            } catch (Exception e) {
                ex.close();
            }
            return;
        }
        try {
            String path   = ex.getRequestURI().getPath();
            String query  = ex.getRequestURI().getRawQuery(); // raw: parseQuery decodes each value once
            Map<String, String> params = parseQuery(query);

            addSecurityHeaders(ex);

            if (path.equals("/connect") && "GET".equals(ex.getRequestMethod())) {
                connect(ex, params);
                return;
            }

            if (path.equals("/connected")) {
                boolean linked = authenticate(ex) != null;
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                sendHtml(ex, linked ? 200 : 401, linked
                        ? messagePage("Browser linked successfully", "You can close this window. To open the panel, run <b>/hackeditor</b> in Minecraft.")
                        : messagePage("You are not authenticated", "Run <b>/hackeditor new</b> in Minecraft and open the link in this browser."));
                return;
            }

            if (path.equals("/") || path.equals("/editor")) {
                serveEditor(ex, params);
                return;
            }

            if (path.equals("/3d-force-graph.min.js") || path.equals("/three.min.js")) {
                serveLibrary(ex, "web" + path, "public, max-age=86400");
                return;
            }

            // Not cached: the page must not run an old script after the plugin is updated
            if (path.equals("/panel.js")) {
                serveLibrary(ex, "web/panel.js", "no-store");
                return;
            }

            if (path.equals("/i18n.js")) {
                byte[] bytes = ("window.VENEER_I18N = " + translations() + ";").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/javascript; charset=utf-8");
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
                return;
            }

            if (path.startsWith("/api/")) {
                handleApi(ex, path, params);
                return;
            }

            sendJson(ex, 404, Map.of("error", "not found"));
        } catch (com.google.gson.JsonParseException | ClassCastException | IllegalArgumentException e) {
            // Malformed JSON, a field of the wrong type or a bad %-escape: the request is at fault
            try { sendJson(ex, 400, Map.of("error", "bad request")); } catch (Exception ignored) {}
        } catch (Exception e) {
            // Logged with the details; the client gets no exception text
            String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            plugin.getLogger().warning("[Hacks] Web panel request " + ex.getRequestURI().getPath() + " failed: " + error);
            try { sendJson(ex, 500, Map.of("error", "internal error")); } catch (Exception ignored) {}
        } finally {
            ex.close();
        }
    }

    private void serveEditor(HttpExchange ex, Map<String, String> params) throws IOException {
        if (authenticate(ex) == null) {
            sendHtml(ex, 401, messagePage("You are not authenticated", "Run <b>/hackeditor new</b> in Minecraft and open the link in this browser."));
            return;
        }
        // Each visit extends the cookie as the server extends the session
        setCookie(ex, sessionOf(ex));
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        try (InputStream is = plugin.getResource("web/editor.html")) {
            if (is == null) { sendHtml(ex, 500, "<h1>editor.html missing</h1>"); return; }
            sendHtml(ex, 200, new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** The panel texts for general.language from web/lang/<language>.json, or {} (English, as written in the page). */
    private String translations() {
        try (InputStream is = plugin.getResource("web/lang/" + plugin.config().language() + ".json")) {
            return is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "{}";
        } catch (IOException e) {
            return "{}";
        }
    }

    /** Bundled scripts; they hold no data, so no session is needed. */
    private void serveLibrary(HttpExchange ex, String resource, String cache) throws IOException {
        try (InputStream is = plugin.getResource(resource)) {
            if (is == null) { sendJson(ex, 404, Map.of("error", "not found")); return; }
            byte[] bytes = is.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/javascript; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", cache);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    private void handleApi(HttpExchange ex, String path, Map<String, String> params) throws IOException {
        Map<String, String> playerInfo = authenticate(ex);

        if (playerInfo == null) {
            sendJson(ex, 401, Map.of("error", "unauthorized"));
            return;
        }

        String method = ex.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method) && !sameOrigin(ex)) {
            sendJson(ex, 403, Map.of("error", "cross-origin request refused"));
            return;
        }

        if (path.equals("/api/validate")) {
            Map<String, Object> info = new LinkedHashMap<>(playerInfo);
            info.put("exposed", settings().isPanelExposed());
            sendJson(ex, 200, info);
            return;
        }

        if (path.equals("/api/players/online")) {
            sendJson(ex, 200, cachedPlayers);
            return;
        }

        if (path.equals("/api/stats")) {
            Map<String, Object> stats = plugin.database().scans().getStats();
            stats.put("online", cachedPlayers.size());
            sendJson(ex, 200, stats);
            return;
        }

        if (path.equals("/api/players/search")) {
            String q = params.getOrDefault("q", "").trim();
            sendJson(ex, 200, q.isEmpty() || q.length() > 16 ? List.of() : plugin.database().players().searchPlayers(q, 12));
            return;
        }

        if (path.equals("/api/mods")) {
            sendJson(ex, 200, plugin.database().scans().getDetectedMods());
            return;
        }

        if (path.startsWith("/api/links") && !can(playerInfo, "links")) {
            sendJson(ex, 403, Map.of("error", "no permission: veneer.web.links"));
            return;
        }

        if (path.startsWith("/api/links/")) {
            // getPath() is already decoded
            Map<String, Object> details = plugin.database().links().getLinkDetails(path.substring("/api/links/".length()));
            if (!can(playerInfo, "ips")) details.put("ips", List.of());
            for (Object r : (List<?>) details.get("ips")) {
                @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) r;
                describeIp((String) row.get("ip"), row);
            }
            sendJson(ex, 200, details);
            return;
        }

        if (path.equals("/api/links")) {
            sendJson(ex, 200, linkGraph(playerInfo));
            return;
        }

        if (path.equals("/api/scans")) {
            String type  = params.get("type");
            // Clamped: limit=-1 means "no limit" to SQLite and would read the whole history under the DB lock
            int    limit = Math.max(1, Math.min(500, parseInt(params.get("limit"), 50)));
            String validType = ("hack".equals(type) || "lang".equals(type)) ? type : null;
            sendJson(ex, 200, plugin.database().scans().getRecentScans(validType, limit,
                    Math.max(0, parseInt(params.get("offset"), 0)), "1".equals(params.get("detected")) ? true : null, null));
            return;
        }

        if (path.equals("/api/scan/run") && "POST".equals(method)) {
            if (!can(playerInfo, "scan")) {
                sendJson(ex, 403, Map.of("error", "no permission: veneer.web.scan"));
                return;
            }
            // The body is a player name and a type; anything much bigger is not from the panel
            byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            if (raw.length > MAX_BODY) {
                sendJson(ex, 413, Map.of("error", "request too large"));
                return;
            }
            String body = new String(raw, StandardCharsets.UTF_8);
            Map<?, ?> req = gson.fromJson(body, Map.class);
            String targetName = req != null ? (String) req.get("player") : null;
            String type       = req != null && req.get("type") != null ? (String) req.get("type") : "hack";
            if (!List.of("hack", "lang", "version", "all").contains(type)) {
                sendJson(ex, 400, Map.of("error", "type must be hack, lang, version or all"));
                return;
            }

            if (targetName == null || targetName.isBlank()) {
                sendJson(ex, 400, Map.of("error", "player is required"));
                return;
            }

            final String checkerName = playerInfo.get("player_name");
            final String finalType   = type;
            final String finalTarget = targetName;
            // From the cache, not Bukkit: this runs on an HTTP thread
            if (cachedPlayers.stream().noneMatch(p -> finalTarget.equalsIgnoreCase((String) p.get("name")))) {
                sendJson(ex, 404, Map.of("error", "player is not online"));
                return;
            }
            boolean nothing = "lang".equals(type) ? plugin.module(HacksModule.class).config().getLanguages().isEmpty()
                    : "version".equals(type) ? false : plugin.module(HacksModule.class).config().getDefaultCheckHacks().isEmpty();
            if (nothing) {
                sendJson(ex, 409, Map.of("error", "lang".equals(type) ? "no languages configured" : "no hacks configured"));
                return;
            }
            plugin.getLogger().info("[Hacks] Web panel: " + checkerName + " started a " + type + " scan of " + targetName);

            FoliaScheduler.runGlobal(plugin, () -> {
                Player target = Bukkit.getPlayerExact(finalTarget);
                if (target == null) return;
                FoliaScheduler.runAtEntity(plugin, target, () -> {
                    Map<String, String> langs = plugin.module(HacksModule.class).config().getLanguages();
                    List<HackDefinition> hacks = plugin.module(HacksModule.class).config().getDefaultCheckHacks();
                    String reason = "Web editor check by " + checkerName;
                    switch (finalType) {
                        case "lang" -> { if (!langs.isEmpty()) plugin.module(HacksModule.class).langChecks().startCheck(target, null, langs, true, false); }
                        case "version" -> plugin.module(HacksModule.class).langChecks().startCheck(target, null, langs, false, true);
                        case "all" -> {
                            plugin.module(HacksModule.class).langChecks().startCheck(target, null, langs, !langs.isEmpty(), true);
                            if (!hacks.isEmpty()) plugin.module(HacksModule.class).checks().startCheckAfterLang(target, null, hacks, reason);
                        }
                        default -> { if (!hacks.isEmpty()) plugin.module(HacksModule.class).checks().startCheck(target, null, hacks, false, reason); }
                    }
                });
            });

            sendJson(ex, 200, Map.of("success", true));
            return;
        }

        if (path.startsWith("/api/scan/")) {
            String idStr = path.substring("/api/scan/".length());
            try {
                long id = Long.parseLong(idStr);
                if ("DELETE".equals(method)) {
                    if (!can(playerInfo, "delete")) {
                        sendJson(ex, 403, Map.of("error", "no permission: veneer.web.delete"));
                        return;
                    }
                    boolean ok = plugin.database().scans().deleteScan(id);
                    if (ok) plugin.getLogger().info("[Hacks] Web panel: " + playerInfo.get("player_name") + " deleted scan #" + id);
                    sendJson(ex, 200, Map.of("success", ok));
                } else {
                    Map<String, Object> scan = plugin.database().scans().getScan(id);
                    if (scan == null) sendJson(ex, 404, Map.of("error", "scan not found"));
                    else              sendJson(ex, 200, scan);
                }
            } catch (NumberFormatException e) {
                sendJson(ex, 400, Map.of("error", "invalid id: " + idStr));
            }
            return;
        }

        if (path.startsWith("/api/player/")) {
            String name = path.substring("/api/player/".length()); // getPath() is already decoded
            if ("DELETE".equals(method)) {
                if (!can(playerInfo, "delete")) {
                    sendJson(ex, 403, Map.of("error", "no permission: veneer.web.delete"));
                    return;
                }
                String stored = (String) plugin.database().players().getPlayerRecord(name).get("uuid");
                if (stored == null) {
                    sendJson(ex, 404, Map.of("error", "player not found"));
                    return;
                }
                plugin.database().players().deletePlayers(List.of(stored));
                graphCache.clear();
                plugin.getLogger().info("[Hacks] Web panel: " + playerInfo.get("player_name") + " deleted player " + name + " (" + stored + ")");
                sendJson(ex, 200, Map.of("success", true));
                return;
            }
            // From the cache, not Bukkit: this runs on an HTTP thread
            boolean online = false;
            String uuid = "";
            for (Map<String, Object> p : cachedPlayers)
                if (name.equalsIgnoreCase((String) p.get("name"))) { uuid = (String) p.get("uuid"); online = true; break; }
            Map<String, Object> record = plugin.database().players().getPlayerRecord(name);
            if (uuid.isEmpty()) uuid = (String) record.getOrDefault("uuid", "");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name",   name);
            result.put("uuid",   uuid);
            result.put("online", online);
            result.put("known",  !uuid.isEmpty());
            result.put("first_seen", record.get("first_seen"));
            result.put("last_seen",  record.get("last_seen"));
            result.put("joins",      record.get("joins"));
            result.put("client",     online ? liveClient(uuid) : record.get("client"));
            if (!uuid.isEmpty()) {
                Map<String, Object> details = plugin.database().links().getLinkDetails(uuid);
                result.put("mods", details.get("mods"));
                result.put("fingerprint", details.get("fingerprint"));
                if (can(playerInfo, "ips")) {
                    for (Object r : (List<?>) details.get("ips")) {
                        @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) r;
                        describeIp((String) row.get("ip"), row);
                    }
                    result.put("ips", details.get("ips"));
                }
                if (can(playerInfo, "links")) result.put("alts", alts(linkGraph(playerInfo), uuid));
            }
            result.put("scans",  plugin.database().scans().getPlayerScans(uuid, name));
            sendJson(ex, 200, result);
            return;
        }

        sendJson(ex, 404, Map.of("error", "endpoint not found"));
    }

    /**
     * The account graph as the token may see it: IP links weakened for VPN/hosting providers, and addresses
     * hidden without {@code veneer.web.ips}.
     */
    private Map<String, Object> linkGraph(Map<String, String> playerInfo) {
        boolean ips = can(playerInfo, "ips");
        // The player page asks for it on every refresh (every 2.5 s while a scan runs): build it at most every 15 s
        CachedGraph cached = graphCache.get(ips);
        if (cached != null && System.currentTimeMillis() - cached.built() < GRAPH_TTL_MS) return cached.graph();
        Map<String, Object> graph = buildLinkGraph(ips);
        graphCache.put(ips, new CachedGraph(graph, System.currentTimeMillis()));
        return graph;
    }

    private static final int MAX_UNLINKED_NODES = 300;
    private record CachedGraph(Map<String, Object> graph, long built) {}
    private static final long GRAPH_TTL_MS = 15_000;
    private final Map<Boolean, CachedGraph> graphCache = new java.util.concurrent.ConcurrentHashMap<>();

    private Map<String, Object> buildLinkGraph(boolean ips) {
        var links = links();
        var rp = plugin.config().section("rptrack");
        int match = rp != null ? rp.getInt("match", 5) : 5;
        Map<String, Object> graph = plugin.database().links().getLinkGraph(links.getInt("max-accounts-per-ip", 8), match,
                links.getInt("max-accounts-per-client", 5), links.getInt("client-min-channels", 3), MAX_UNLINKED_NODES);
        double weakFactor = Math.max(0, Math.min(1, links.getDouble("weak-provider-factor", 0.3)));
        for (Object e : (List<?>) graph.get("edges")) {
            @SuppressWarnings("unchecked") Map<String, Object> edge = (Map<String, Object>) e;
            if (!"ip".equals(edge.get("type"))) continue;
            // Strangers share the exit addresses of VPNs, WARP and hosting: such a shared address proves little.
            // Without the ips right the provider, ASN and country stay out too: they narrow the address down.
            if (describeIp((String) edge.get("label"), ips ? edge : new HashMap<>()))
                edge.put("p", ((Number) edge.get("p")).doubleValue() * weakFactor);
            if (!ips) edge.put("label", "(hidden)");
        }
        if (!ips)
            for (Object n : (List<?>) graph.get("nodes")) {
                @SuppressWarnings("unchecked") Map<String, Object> node = (Map<String, Object>) n;
                node.put("ips", List.of());
            }
        return graph;
    }

    /**
     * Accounts linked to {@code uuid} in the graph, strongest first: name, uuid, pct (reasons merged the way the
     * graph merges them, 1 - product of (1 - p)) and the link types.
     */
    private static List<Map<String, Object>> alts(Map<String, Object> graph, String uuid) {
        Map<String, String> names = new HashMap<>();
        for (Object n : (List<?>) graph.get("nodes")) {
            Map<?, ?> node = (Map<?, ?>) n;
            names.put((String) node.get("id"), (String) node.get("name"));
        }
        Map<String, double[]> miss = new LinkedHashMap<>();
        Map<String, Set<String>> types = new HashMap<>();
        for (Object e : (List<?>) graph.get("edges")) {
            Map<?, ?> edge = (Map<?, ?>) e;
            String other = uuid.equals(edge.get("source")) ? (String) edge.get("target")
                    : uuid.equals(edge.get("target")) ? (String) edge.get("source") : null;
            if (other == null) continue;
            miss.computeIfAbsent(other, k -> new double[]{1})[0] *= 1 - ((Number) edge.get("p")).doubleValue();
            types.computeIfAbsent(other, k -> new TreeSet<>()).add((String) edge.get("type"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, double[]> m : miss.entrySet()) {
            Map<String, Object> alt = new LinkedHashMap<>();
            alt.put("uuid", m.getKey());
            alt.put("name", names.getOrDefault(m.getKey(), m.getKey()));
            alt.put("pct", 1 - m.getValue()[0]);
            alt.put("types", types.get(m.getKey()));
            out.add(alt);
        }
        out.sort((a, b) -> Double.compare((Double) b.get("pct"), (Double) a.get("pct")));
        return out;
    }

    /** Brand, channels, loader and version probe of an online player, from the live profile. */
    private Map<String, Object> liveClient(String uuid) {
        Map<String, Object> client = new LinkedHashMap<>();
        PlayerProfile profile = plugin.profileOf(UUID.fromString(uuid));
        if (profile == null) return client;
        client.put("brand", profile.getBrand());
        client.put("channels", new TreeSet<>(profile.getChannels()));
        client.put("type", profile.clientType().name());
        client.put("version_real", profile.getVersionReal());
        client.put("version_claimed", profile.getVersionClaimed());
        client.put("version_mismatch", profile.isVersionMismatch());
        client.put("fake_player", profile.isFakePlayer());
        client.put("exploit_preventer", profile.isExploitPreventer());
        client.put("live", true);
        client.put("locale", cachedPlayers.stream().filter(p -> uuid.equals(p.get("uuid")))
                .map(p -> p.get("locale")).findFirst().orElse(null));
        client.put("language", profile.getLanguage());
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("exploit_preventer", profile.exploitPreventerVerdict().name());
        verdicts.put("fake_player", profile.fakePlayerVerdict().name());
        verdicts.put("brand", profile.brandVerdict().name());
        verdicts.put("language", profile.languageVerdict().name());
        verdicts.put("protocol", profile.protocolVerdict().name());
        client.put("verdicts", verdicts);
        return client;
    }

    /** Whether the token was issued to a player with {@code veneer.web.<perm>} (rights are fixed at issue time). */
    private static boolean can(Map<String, String> playerInfo, String perm) {
        return Arrays.asList(playerInfo.getOrDefault("perms", "").split(",")).contains(perm);
    }

    /**
     * A second line behind the page's escaping: only the panel's own script files run, so text that slips into the
     * page as markup stays inert. Inline styles stay allowed, the page uses them throughout.
     */
    private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; "
            + "img-src 'self' data: blob: https://mc-heads.net; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    /** The panel is same-origin, so no CORS; no referrer to the head images either. */
    private void addSecurityHeaders(HttpExchange ex) {
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    }

    private void sendJson(HttpExchange ex, int code, Object obj) throws IOException {
        byte[] bytes = gson.toJson(obj).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private void sendHtml(HttpExchange ex, int code, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2)
                params.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return params;
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        streamThreads.shutdownNow();
    }
}
