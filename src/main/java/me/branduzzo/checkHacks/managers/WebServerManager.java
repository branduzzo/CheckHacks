package me.branduzzo.checkHacks.managers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.HackDefinition;
import me.branduzzo.checkHacks.model.EditorTokenInfo;
import me.branduzzo.checkHacks.model.ScanRecord;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class WebServerManager {

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private final CheckHacksPlugin plugin;
    private HttpServer server;
    private final Gson gson = new Gson();
    private volatile List<Map<String, Object>> cachedPlayers = List.of();

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
        cachedPlayers = List.copyOf(list);
    }

    private void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
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
            try {
                sendJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
            } catch (Exception ignored) {}
        }
    }

    private void serveEditor(HttpExchange ex, Map<String, String> params) throws IOException {
        String token = params.get("token");
        if (token == null || plugin.getDatabaseManager().validateToken(token) == null) {
            sendHtml(ex, 403,
                    "<html><body style='font-family:monospace;background:#0d1117;color:#e6edf3;"
                            + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>"
                            + "<div>Invalid or expired token.<br>Run <b>/cheditor</b> in Minecraft.</div>"
                            + "</body></html>");
            return;
        }
        try (InputStream is = plugin.getResource("web/editor.html")) {
            if (is == null) {
                sendHtml(ex, 500, "<h1>editor.html missing</h1>");
                return;
            }
            sendHtml(ex, 200, new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void handleApi(HttpExchange ex, String path, Map<String, String> params) throws IOException {
        String token = params.get("token");
        EditorTokenInfo playerInfo = token != null
                ? plugin.getDatabaseManager().validateToken(token) : null;

        if (playerInfo == null) {
            sendJson(ex, 401, Map.of("error", "unauthorized"));
            return;
        }

        String method = ex.getRequestMethod();

        if (path.equals("/api/validate")) {
            sendJson(ex, 200, playerInfo.toMap());
            return;
        }

        if (path.equals("/api/players/online")) {
            sendJson(ex, 200, cachedPlayers);
            return;
        }

        if (path.equals("/api/scans")) {
            handleListScans(ex, params);
            return;
        }

        if (path.equals("/api/scan/run") && "POST".equals(method)) {
            handleRunScan(ex, playerInfo);
            return;
        }

        if (path.startsWith("/api/scan/")) {
            handleScanById(ex, path, method);
            return;
        }

        if (path.startsWith("/api/player/")) {
            handlePlayer(ex, path);
            return;
        }

        sendJson(ex, 404, Map.of("error", "endpoint not found"));
    }

    private void handleListScans(HttpExchange ex, Map<String, String> params) throws IOException {
        String type = params.get("type");
        int limit = parseInt(params.get("limit"), 50);
        String validType = ("hack".equals(type) || "lang".equals(type)) ? type : null;
        List<Map<String, Object>> body = plugin.getDatabaseManager()
                .getRecentScans(validType, limit).stream()
                .map(ScanRecord::toMap)
                .toList();
        sendJson(ex, 200, body);
    }

    private void handleRunScan(HttpExchange ex, EditorTokenInfo playerInfo) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> req = gson.fromJson(body, MAP_TYPE);
        String targetName = req != null && req.get("player") instanceof String s ? s : null;
        String type = req != null && req.get("type") instanceof String s ? s : "hack";

        if (targetName == null || targetName.isBlank()) {
            sendJson(ex, 400, Map.of("error", "player is required"));
            return;
        }

        final String checkerName = playerInfo.playerName();
        final String finalType = type;
        final String finalTarget = targetName;

        AtomicReference<String> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        FoliaScheduler.runGlobal(plugin, () -> {
            try {
                Player target = Bukkit.getPlayerExact(finalTarget);
                if (target == null) {
                    error.set("player offline");
                    return;
                }
                if (plugin.hasActiveSignSession(target.getUniqueId())) {
                    error.set("player already being checked");
                    return;
                }
                if ("lang".equals(finalType)) {
                    Map<String, String> langs = plugin.getConfigManager().getLanguages();
                    if (langs.isEmpty()) {
                        error.set("no languages configured");
                        return;
                    }
                    plugin.getLangCheckManager().startCheck(target, null, langs);
                } else {
                    List<HackDefinition> hacks = plugin.getConfigManager().getDefaultCheckHacks();
                    if (hacks.isEmpty()) {
                        error.set("no hacks configured");
                        return;
                    }
                    plugin.getCheckManager().startCheck(target, null, hacks, false,
                            "Web editor check by " + checkerName);
                }
            } finally {
                done.countDown();
            }
        });

        try {
            if (!done.await(5, TimeUnit.SECONDS)) {
                sendJson(ex, 504, Map.of("error", "timed out starting scan"));
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJson(ex, 500, Map.of("error", "interrupted"));
            return;
        }

        if (error.get() != null) {
            sendJson(ex, 400, Map.of("error", error.get(), "success", false));
            return;
        }
        sendJson(ex, 200, Map.of("success", true));
    }

    private void handleScanById(HttpExchange ex, String path, String method) throws IOException {
        String idStr = path.substring("/api/scan/".length());
        try {
            long id = Long.parseLong(idStr);
            if ("DELETE".equals(method)) {
                boolean ok = plugin.getDatabaseManager().deleteScan(id);
                sendJson(ex, 200, Map.of("success", ok));
            } else {
                ScanRecord scan = plugin.getDatabaseManager().getScan(id);
                if (scan == null) sendJson(ex, 404, Map.of("error", "scan not found"));
                else sendJson(ex, 200, scan.toMap());
            }
        } catch (NumberFormatException e) {
            sendJson(ex, 400, Map.of("error", "invalid id: " + idStr));
        }
    }

    private void handlePlayer(HttpExchange ex, String path) throws IOException {
        String name = URLDecoder.decode(path.substring("/api/player/".length()), StandardCharsets.UTF_8);
        boolean online = Bukkit.getPlayerExact(name) != null;
        String uuid = "";
        for (Map<String, Object> p : cachedPlayers) {
            if (name.equalsIgnoreCase(String.valueOf(p.get("name")))) {
                uuid = String.valueOf(p.get("uuid"));
                break;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("uuid", uuid);
        result.put("online", online);
        result.put("scans", plugin.getDatabaseManager().getPlayerScans(name).stream()
                .map(ScanRecord::toMap).toList());
        sendJson(ex, 200, result);
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
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendHtml(HttpExchange ex, int code, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null) return params;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
    }
}
