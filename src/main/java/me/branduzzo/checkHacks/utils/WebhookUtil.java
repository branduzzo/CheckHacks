package me.branduzzo.checkHacks.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebhookUtil {

    private static final int COMPONENTS_V2_FLAG = 1 << 15;
    private static final int MAX_TEXT_CHARS = 3900;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CheckHacks-Webhook");
        thread.setDaemon(true);
        return thread;
    });

    public static void sendResult(String webhookUrl, int color, String messageTemplate,
                                  String playerName, String checkerName, String reason,
                                  String hacksChecked, String resultText) {
        sendResult(webhookUrl, color, messageTemplate, playerName, checkerName, reason, hacksChecked, resultText, false);
    }

    public static void sendResult(String webhookUrl, int color, String messageTemplate,
                                  String playerName, String checkerName, String reason,
                                  String hacksChecked, String resultText, boolean useComponentsV2) {
        if (!isValid(webhookUrl)) return;
        String description = messageTemplate
                .replace("&name&", playerName)
                .replace("&checker&", checkerName)
                .replace("&reason&", reason)
                .replace("&hacks&", hacksChecked)
                .replace("&results&", resultText);
        sendRaw(webhookUrl, color, description, useComponentsV2);
    }

    public static void sendRaw(String webhookUrl, int color, String description) {
        sendRaw(webhookUrl, color, description, false);
    }

    public static void sendRaw(String webhookUrl, int color, String description, boolean useComponentsV2) {
        if (!isValid(webhookUrl)) return;
        String body = useComponentsV2 ? buildComponentsV2(color, description) : buildEmbed(color, description);
        sendJson(webhookUrl, body);
    }

    private static String buildEmbed(int color, String description) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "CheckHacks Report");
        embed.addProperty("description", description == null ? "" : description);
        embed.addProperty("color", color);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "CheckHacks - Sign Translation Exploit");
        embed.add("footer", footer);
        embed.addProperty("timestamp", Instant.now().toString());
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        JsonObject root = new JsonObject();
        root.add("embeds", embeds);
        return root.toString();
    }

    private static String buildComponentsV2(int color, String description) {
        String content = description == null || description.isBlank() ? "No data" : description;
        JsonObject container = new JsonObject();
        container.addProperty("type", 17);
        container.addProperty("accent_color", color);
        JsonArray components = new JsonArray();
        for (String chunk : chunks(content)) {
            JsonObject text = new JsonObject();
            text.addProperty("type", 10);
            text.addProperty("content", chunk);
            components.add(text);
        }
        JsonObject separator = new JsonObject();
        separator.addProperty("type", 14);
        separator.addProperty("divider", true);
        separator.addProperty("spacing", 1);
        components.add(separator);
        JsonObject footer = new JsonObject();
        footer.addProperty("type", 10);
        footer.addProperty("content", "-# CheckHacks - Sign Translation Exploit - <t:" + Instant.now().getEpochSecond() + ":F>");
        components.add(footer);
        container.add("components", components);
        JsonArray top = new JsonArray();
        top.add(container);
        JsonObject root = new JsonObject();
        root.addProperty("flags", COMPONENTS_V2_FLAG);
        root.add("components", top);
        return root.toString();
    }

    private static List<String> chunks(String text) {
        List<String> chunks = new ArrayList<>();
        String remaining = text.length() > 36000 ? text.substring(0, 36000) : text;
        while (!remaining.isEmpty()) {
            if (remaining.length() <= MAX_TEXT_CHARS) {
                chunks.add(remaining);
                break;
            }
            int cut = remaining.lastIndexOf('\n', MAX_TEXT_CHARS);
            if (cut < MAX_TEXT_CHARS / 2) cut = MAX_TEXT_CHARS;
            chunks.add(remaining.substring(0, cut));
            remaining = remaining.substring(cut);
            if (remaining.startsWith("\n")) remaining = remaining.substring(1);
        }
        if (chunks.isEmpty()) chunks.add("No data");
        return chunks;
    }

    private static boolean isValid(String url) {
        return url != null && !url.isBlank() && !url.contains("CHANGE_ME")
                && (url.startsWith("https://") || url.startsWith("http://"));
    }

    private static void sendJson(String webhookUrl, String json) {
        EXECUTOR.execute(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(withQuery(webhookUrl)))
                        .timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .header("User-Agent", "CheckHacks/1.3.2")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    System.err.println("[CheckHacks] Webhook HTTP " + response.statusCode() + ": " + response.body());
                }
            } catch (Exception e) {
                System.err.println("[CheckHacks] Webhook error: " + e.getMessage());
            }
        });
    }

    private static String withQuery(String url) {
        String query = "with_components=true&wait=true";
        if (url.contains("?")) {
            if (url.contains("with_components")) return url;
            return url + "&" + query;
        }
        return url + "?" + query;
    }
}
