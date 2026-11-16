package me.branduzzo.checkHacks.managers;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.CommandRule;
import me.branduzzo.checkHacks.HackDefinition;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class WebServerManager {

    private final CheckHacksPlugin plugin;
    private final ConfigWriter configWriter;
    private final Gson gson = new Gson();
    private HttpServer server;
    private ExecutorService executor;
    private volatile List<Map<String, Object>> cachedPlayers = new ArrayList<>();

    public WebServerManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
        this.configWriter = new ConfigWriter(plugin);
        start();
        FoliaScheduler.runGlobalTimer(plugin, this::updatePlayerCache, 1L, 40L);
    }

    private void start() {
        int port = plugin.getConfigManager().getWebPort();
        String host = plugin.getConfigManager().getWebHost();
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/", this::handle);
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "CheckHacks-Web");
                thread.setDaemon(true);
                return thread;
            };
            executor = Executors.newFixedThreadPool(4, factory);
            server.setExecutor(executor);
            server.start();
            plugin.getLogger().info("Web editor running on " + host + ":" + port);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to start web editor: " + e.getMessage());
        }
    }

    private void updatePlayerCache() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", player.getName());
            entry.put("uuid", player.getUniqueId().toString());
            list.add(entry);
        }
        cachedPlayers = list;
    }

    private void handle(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            addCorsHeaders(exchange);

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (path.equals("/") || path.equals("/editor")) {
                serveEditor(exchange, params);
                return;
            }

            if (path.startsWith("/api/")) {
                handleApi(exchange, path, params);
                return;
            }

            sendJson(exchange, 404, Map.of("error", "not found"));
        } catch (Exception e) {
            try {
                sendJson(exchange, 500, Map.of("error", e.getMessage()));
            } catch (Exception ignored) {
            }
        }
    }

    private void serveEditor(HttpExchange exchange, Map<String, String> params) throws IOException {
        String token = params.get("token");
        if (token == null || plugin.getDatabaseManager().validateToken(token) == null) {
            sendHtml(exchange, 403, "<html><body style='font-family:system-ui;background:#1b1b1f;color:#e6e1e5;display:flex;align-items:center;justify-content:center;height:100vh;margin:0'><div>Invalid or expired token.<br>Run <b>/cheditor</b> in Minecraft.</div></body></html>");
            return;
        }
        try (InputStream in = plugin.getResource("web/editor.html")) {
            if (in == null) {
                sendHtml(exchange, 500, "<h1>editor.html missing</h1>");
                return;
            }
            sendHtml(exchange, 200, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void handleApi(HttpExchange exchange, String path, Map<String, String> params) throws IOException {
        String token = params.get("token");
        Map<String, String> playerInfo = token != null ? plugin.getDatabaseManager().validateToken(token) : null;
        if (playerInfo == null) {
            sendJson(exchange, 401, Map.of("error", "unauthorized"));
            return;
        }

        String method = exchange.getRequestMethod();

        if (path.equals("/api/validate")) {
            sendJson(exchange, 200, playerInfo);
            return;
        }

        if (path.equals("/api/players/online")) {
            sendJson(exchange, 200, cachedPlayers);
            return;
        }

        if (path.equals("/api/scans")) {
            String type = params.get("type");
            int limit = parseInt(params.get("limit"), 50);
            String validType = ("hack".equals(type) || "lang".equals(type)) ? type : null;
            sendJson(exchange, 200, plugin.getDatabaseManager().getRecentScans(validType, limit));
            return;
        }

        if (path.equals("/api/hacks")) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (HackDefinition hack : plugin.getConfigManager().getHacks().values()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", hack.getId());
                entry.put("name", hack.getDisplayName());
                entry.put("mode", hack.getMode().name());
                entry.put("key", hack.getKey());

                list.add(entry);
            }
            sendJson(exchange, 200, list);
            return;
        }

        if (path.equals("/api/rules")) {
            if ("POST".equals(method)) {
                Map<?, ?> request = readJson(exchange);
                String mod = string(request, "mod");
                String result = string(request, "result");
                String command = string(request, "command");
                String name = string(request, "name");
                if (name.isBlank()) name = mod + "-" + (result.isBlank() ? "any" : result.toLowerCase());
                if (!configWriter.saveRule(name, mod, result, command)) {
                    sendJson(exchange, 400, Map.of("error", "invalid rule"));
                    return;
                }
                reload();
                sendJson(exchange, 200, Map.of("success", true));
                return;
            }
            sendJson(exchange, 200, rules());
            return;
        }

        if (path.startsWith("/api/rules/")) {
            String name = URLDecoder.decode(path.substring("/api/rules/".length()), StandardCharsets.UTF_8);
            if ("DELETE".equals(method)) {
                boolean deleted = configWriter.deleteRule(name);
                if (deleted) reload();
                sendJson(exchange, deleted ? 200 : 404, Map.of("success", deleted));
                return;
            }
            sendJson(exchange, 405, Map.of("error", "method not allowed"));
            return;
        }

        if (path.equals("/api/settings")) {
            if ("POST".equals(method)) {
                Map<?, ?> request = readJson(exchange);
                Map<String, String> values = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : request.entrySet()) {
                    values.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
                if (!configWriter.saveSettings(values)) {
                    sendJson(exchange, 400, Map.of("error", "invalid settings"));
                    return;
                }
                reload();
                sendJson(exchange, 200, configWriter.settingsMap());
                return;
            }
            sendJson(exchange, 200, configWriter.settingsMap());
            return;
        }

        if (path.equals("/api/scan/run") && "POST".equals(method)) {
            Map<?, ?> request = readJson(exchange);
            String targetName = string(request, "player");
            String type = string(request, "type");
            if (targetName.isBlank()) {
                sendJson(exchange, 400, Map.of("error", "player is required"));
                return;
            }
            final String checkerName = playerInfo.get("player_name");
            final String checkType = type.isBlank() ? "hack" : type;
            FoliaScheduler.runGlobal(plugin, () -> {
                Player target = Bukkit.getPlayerExact(targetName);
                if (target == null) return;
                FoliaScheduler.runAtEntity(plugin, target, () -> {
                    if ("lang".equals(checkType)) {
                        Map<String, String> languages = plugin.getConfigManager().getLanguages();
                        if (!languages.isEmpty()) plugin.getLangCheckManager().startCheck(target, null, languages);
                    } else {
                        List<HackDefinition> hacks = plugin.getConfigManager().getDefaultCheckHacks();
                        if (!hacks.isEmpty()) {
                            plugin.getCheckManager().startCheck(target, null, hacks, false,
                                    "Web editor check by " + checkerName);
                        }
                    }
                });
            });
            sendJson(exchange, 200, Map.of("success", true));
            return;
        }

        if (path.startsWith("/api/scan/")) {
            String idValue = path.substring("/api/scan/".length());
            try {
                long id = Long.parseLong(idValue);
                if ("DELETE".equals(method)) {
                    boolean deleted = plugin.getDatabaseManager().deleteScan(id);
                    sendJson(exchange, 200, Map.of("success", deleted));
                } else {
                    Map<String, Object> scan = plugin.getDatabaseManager().getScan(id);
                    if (scan == null) sendJson(exchange, 404, Map.of("error", "scan not found"));
                    else sendJson(exchange, 200, scan);
                }
            } catch (NumberFormatException e) {
                sendJson(exchange, 400, Map.of("error", "invalid id: " + idValue));
            }
            return;
        }

        if (path.startsWith("/api/player/")) {
            String name = URLDecoder.decode(path.substring("/api/player/".length()), StandardCharsets.UTF_8);
            boolean online = false;
            String uuid = "";
            for (Map<String, Object> player : cachedPlayers) {
                if (name.equalsIgnoreCase((String) player.get("name"))) {
                    uuid = (String) player.get("uuid");
                    online = true;
                    break;
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("uuid", uuid);
            result.put("online", online);
            result.put("scans", plugin.getDatabaseManager().getPlayerScans(name));
            sendJson(exchange, 200, result);
            return;
        }

        sendJson(exchange, 404, Map.of("error", "endpoint not found"));
    }

    private List<Map<String, Object>> rules() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (CommandRule rule : plugin.getConfigManager().getCommandRules()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", rule.getName());
            entry.put("mod", rule.getMod());
            entry.put("result", rule.getResult() == null ? "ANY" : rule.getResult().name());
            entry.put("command", rule.getCommand());
            list.add(entry);
        }
        return list;
    }

    private void reload() {
        plugin.getConfigManager().reload();
        plugin.getMessageManager().load();
    }

    private Map<?, ?> readJson(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) return Map.of();
        Map<?, ?> parsed = gson.fromJson(body, Map.class);
        return parsed == null ? Map.of() : parsed;
    }

    private String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private void sendJson(HttpExchange exchange, int code, Object value) throws IOException {
        byte[] bytes = gson.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendHtml(HttpExchange exchange, int code, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2) params.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return params;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }
}
