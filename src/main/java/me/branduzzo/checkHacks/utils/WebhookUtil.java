package me.branduzzo.checkHacks.utils;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public class WebhookUtil {

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
                .replace("&name&",    playerName)
                .replace("&checker&", checkerName)
                .replace("&reason&",  reason)
                .replace("&hacks&",   hacksChecked)
                .replace("&results&", resultText);
        sendRaw(webhookUrl, color, description, useComponentsV2);
    }

    public static void sendRaw(String webhookUrl, int color, String description) {
        sendRaw(webhookUrl, color, description, false);
    }

    public static void sendRaw(String webhookUrl, int color, String description, boolean useComponentsV2) {
        if (!isValid(webhookUrl)) return;
        String json = useComponentsV2 ? buildComponentsV2Json(color, description) : buildEmbedJson(color, description);
        sendJson(webhookUrl, json);
    }

    private static String buildEmbedJson(int color, String description) {
        return "{\"embeds\":[{"
                + "\"title\":\"CheckHacks Report\","
                + "\"description\":\"" + escapeJson(description) + "\","
                + "\"color\":" + color + ","
                + "\"footer\":{\"text\":\"CheckHacks - Sign Translation Exploit\"},"
                + "\"timestamp\":\"" + Instant.now() + "\""
                + "}]}";
    }

    private static String buildComponentsV2Json(int color, String description) {
        long epochSeconds = Instant.now().getEpochSecond();
        String content = "## CheckHacks Report\n" + description;
        String footer = "-# CheckHacks — Sign Translation Exploit • <t:" + epochSeconds + ":F>";
        return "{\"flags\":32768,\"components\":[{"
                + "\"type\":17,"
                + "\"accent_color\":" + color + ","
                + "\"components\":["
                + "{\"type\":10,\"content\":\"" + escapeJson(content) + "\"},"
                + "{\"type\":14,\"divider\":true,\"spacing\":1},"
                + "{\"type\":10,\"content\":\"" + escapeJson(footer) + "\"}"
                + "]}]}";
    }

    private static boolean isValid(String url) {
        return url != null && !url.isBlank() && !url.contains("CHANGE_ME");
    }

    private static void sendJson(String webhookUrl, String json) {
        new Thread(() -> {
            try {
                HttpURLConnection conn =
                        (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("User-Agent", "CheckHacks/1.1");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code < 200 || code >= 300)
                    System.err.println("[CheckHacks] Webhook HTTP " + code);
            } catch (Exception e) {
                System.err.println("[CheckHacks] Webhook error: " + e.getMessage());
            }
        }, "CheckHacks-Webhook").start();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
