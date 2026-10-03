package me.branduzzo.checkHacks.managers;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.CheckType;
import me.branduzzo.checkHacks.HackDefinition;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

public class WebServerManager {

    private final CheckHacksPlugin plugin;
    private HttpServer server;
    private final Gson gson = new Gson();
    private volatile List<Map<String, Object>> cachedPlayers = new ArrayList<>();

    public WebServerManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
        start();
        FoliaScheduler.runGlobalTimer(plugin, this::updatePlayerCache, 1L, 100L);
    }

    private void start() {
        int port = plugin.getConfigManager().getWebPort();
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", this::handle);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            plugin.getLogger().info("Web editor running on port " + port);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to start web editor: " + e.getMessage());
        }
    }

    private void updatePlayerCache() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("uuid", p.getUniqueId().toString());
            list.add(m);
        }
        cachedPlayers = list;
    }

    private void handle(HttpExchange ex) {
        try {
            String path   = ex.getRequestURI().getPath();
            String query  = ex.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);

            addCorsHeaders(ex);

            if ("OPTIONS".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                return;
            }

            if (path.equals("/") || path.equals("/editor")) {
                serveEditor(ex, params);
                return;
            }

            if (path.startsWith("/api/")) {
                handleApi(ex, path, params);
                return;
            }

            sendJson(ex, 404, Map.of("error", "not found"));
        } catch (Exception e) {
            try { sendJson(ex, 500, Map.of("error", e.getMessage())); } catch (Exception ignored) {}
        }
    }

    private void serveEditor(HttpExchange ex, Map<String, String> params) throws IOException {
        String token = params.get("token");
        if (token == null || plugin.getDatabaseManager().validateToken(token) == null) {
            sendHtml(ex, 403, "<html><body style='font-family:monospace;background:#0d1117;color:#e6edf3;display:flex;align-items:center;justify-content:center;height:100vh;margin:0'><div>Invalid or expired token.<br>Run <b>/cheditor</b> in Minecraft.</div></body></html>");
            return;
        }
        try (InputStream is = plugin.getResource("web/editor.html")) {
            if (is == null) { sendHtml(ex, 500, "<h1>editor.html missing</h1>"); return; }
            sendHtml(ex, 200, new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void handleApi(HttpExchange ex, String path, Map<String, String> params) throws IOException {
        String token = params.get("token");
        Map<String, String> playerInfo = token != null ? plugin.getDatabaseManager().validateToken(token) : null;

        if (playerInfo == null) {
            sendJson(ex, 401, Map.of("error", "unauthorized"));
            return;
        }

        String method = ex.getRequestMethod();

        if (path.equals("/api/validate")) {
            sendJson(ex, 200, playerInfo);
            return;
        }

        if (path.equals("/api/players/online")) {
            sendJson(ex, 200, cachedPlayers);
            return;
        }

        if (path.equals("/api/scans")) {
            String type  = params.get("type");
            int    limit = parseInt(params.get("limit"), 50);
            CheckType checkType = CheckType.byId(type);
            String validType = checkType != null ? checkType.getId() : null;
            sendJson(ex, 200, plugin.getDatabaseManager().getRecentScans(validType, limit));
            return;
        }

        if (path.equals("/api/scan/run") && "POST".equals(method)) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<?, ?> req = gson.fromJson(body, Map.class);
            String targetName = req != null ? (String) req.get("player") : null;
            String type       = req != null ? (String) req.get("type")   : "hack";

            if (targetName == null || targetName.isBlank()) {
                sendJson(ex, 400, Map.of("error", "player is required"));
                return;
            }

            final String checkerName = playerInfo.get("player_name");
            final String finalType   = type;
            final String finalTarget = targetName;
            CheckType requested = CheckType.byId(finalType);
            final CheckType checkType = requested != null ? requested : CheckType.HACK;

            FoliaScheduler.runGlobal(plugin, () -> {
                Player target = Bukkit.getPlayerExact(finalTarget);
                if (target == null) return;
                FoliaScheduler.runAtEntity(plugin, target, () -> {
                    String reason = "Web editor check by " + checkerName;
                    switch (checkType) {
                        case BRAND -> plugin.getClientCheckManager().runBrandCheck(target, null, reason);
                        case MODLIST -> plugin.getClientCheckManager().runModListCheck(target, null, reason);
                        case LANG -> {
                            Map<String, String> langs = plugin.getConfigManager().getLanguages();
                            if (!langs.isEmpty())
                                plugin.getLangCheckManager().startCheck(target, null, langs);
                        }
                        default -> {
                            List<HackDefinition> hacks = plugin.getConfigManager().getDefaultCheckHacks();
                            if (!hacks.isEmpty())
                                plugin.getCheckManager().startCheck(target, null, hacks, false, reason);
                        }
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
                    boolean ok = plugin.getDatabaseManager().deleteScan(id);
                    sendJson(ex, 200, Map.of("success", ok));
                } else {
                    Map<String, Object> scan = plugin.getDatabaseManager().getScan(id);
                    if (scan == null) sendJson(ex, 404, Map.of("error", "scan not found"));
                    else              sendJson(ex, 200, scan);
                }
            } catch (NumberFormatException e) {
                sendJson(ex, 400, Map.of("error", "invalid id: " + idStr));
            }
            return;
        }

        if (path.startsWith("/api/player/")) {
            String name = URLDecoder.decode(
                    path.substring("/api/player/".length()), StandardCharsets.UTF_8);
            boolean online = Bukkit.getPlayerExact(name) != null;
            String uuid = "";
            for (Map<String, Object> p : cachedPlayers)
                if (name.equalsIgnoreCase((String) p.get("name"))) { uuid = (String) p.get("uuid"); break; }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name",   name);
            result.put("uuid",   uuid);
            result.put("online", online);
            if (online && uuid != null && !uuid.isBlank()) {
                Map<String, Object> profile =
                        plugin.getClientCheckManager().cachedProfileToMap(java.util.UUID.fromString(uuid));
                if (profile != null) result.put("profile", profile);
            }
            result.put("scans",  plugin.getDatabaseManager().getPlayerScans(name));
            sendJson(ex, 200, result);
            return;
        }

        sendJson(ex, 404, Map.of("error", "endpoint not found"));
    }

    private void addCorsHeaders(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
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
    }
}