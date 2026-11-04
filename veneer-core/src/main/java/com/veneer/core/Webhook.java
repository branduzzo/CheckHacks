package com.veneer.core;

import com.veneer.VeneerPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/** Discord webhook transport shared by every module, plus the simple embed layout the scan modules use. */
public final class Webhook {

    private static final int FLAG_COMPONENTS_V2 = 32768;
    /** Discord allows 4096 characters in an embed description and 4000 in all text of a V2 message. */
    private static final int MAX_TEXT = 3800;
    /** {@code %name%}, or {@code &name&} as older configs wrote it. */
    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("([%&])([a-z_]+)\\1");

    private final VeneerPlugin plugin;

    public Webhook(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * POSTs a JSON body on the async scheduler. A blank or invalid URL is ignored. Mentions are switched off:
     * texts carry client data (sign answers, brands) that could otherwise ping {@code @everyone}.
     */
    public void post(String url, String body) {
        if (!VeneerConfig.isWebhook(url)) return;
        String trimmed = body.strip();
        String json = trimmed.startsWith("{") && trimmed.length() > 2 && !trimmed.contains("\"allowed_mentions\"")
                ? "{\"allowed_mentions\":{\"parse\":[]}," + trimmed.substring(1) : body;
        FoliaScheduler.runAsync(plugin, () -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) URI.create(url.trim()).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("User-Agent", "Veneer/" + plugin.version());
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    plugin.debug("Webhook sent (HTTP " + code + ")");
                } else {
                    plugin.getLogger().warning("Discord webhook failed with HTTP " + code + ": " + errorBody(conn));
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Discord webhook error: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /** A titled report: classic embed, or a Components V2 container when {@code v2} is set. */
    public void report(String url, int color, String title, String description, boolean v2) {
        post(url, v2 ? containerJson(color, title, cut(description)) : embedJson(color, title, cut(description)));
    }

    /**
     * Sends a report whose title and text come from config templates: PlaceholderAPI runs on the templates for
     * {@code player} (may be null), then each {@code %name%} (or the older {@code &name&}) is replaced from
     * {@code values}. Values go in last so a client brand like {@code %server_ip%} is never expanded.
     */
    public void report(String url, int color, String title, String message, boolean v2,
                       OfflinePlayer player, Map<String, String> values) {
        report(url, color, fill(title, player, values), fill(message, player, values), v2);
    }

    /** A template with PlaceholderAPI and then {@code values} filled in; see {@link #report(String, int, String, String, boolean, OfflinePlayer, Map)}. */
    public static String fill(String template, OfflinePlayer player, Map<String, String> values) {
        if (template == null) return "";
        // One pass, so a value that looks like a placeholder (a client brand "%uuid%") stays as it is
        java.util.regex.Matcher m = PLACEHOLDER.matcher(papi(template, player));
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(2));
            // A backtick in client text would end the code span the templates put it in
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    v == null ? m.group() : v.replace('`', '\u02cb')));
        }
        return m.appendTail(out).toString();
    }

    /** {@code "#2AB7CA"}, {@code "2AB7CA"} or a decimal number as an RGB colour; {@code fallback} when it is neither. */
    public static int color(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        String v = value.trim();
        try {
            return v.startsWith("#") ? Integer.parseInt(v.substring(1), 16) & 0xFFFFFF : Integer.parseInt(v) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            try { return Integer.parseInt(v, 16) & 0xFFFFFF; } catch (NumberFormatException ignored) { return fallback; }
        }
    }

    private static String papi(String text, OfflinePlayer player) {
        if (!text.contains("%") || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return text;
        try {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        } catch (Throwable t) {
            return text;
        }
    }

    private static String cut(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "\n…";
    }

    private static String embedJson(int color, String title, String description) {
        return "{\"embeds\":[{"
                + "\"title\":\"" + escape(title) + "\","
                + "\"description\":\"" + escape(description) + "\","
                + "\"color\":" + color + ","
                + "\"footer\":{\"text\":\"Veneer\"},"
                + "\"timestamp\":\"" + Instant.now() + "\""
                + "}]}";
    }

    private static String containerJson(int color, String title, String description) {
        String content = "## " + title + "\n" + description;
        String footer = "-# Veneer • <t:" + Instant.now().getEpochSecond() + ":F>";
        return "{\"flags\":" + FLAG_COMPONENTS_V2 + ",\"components\":[{"
                + "\"type\":17,"
                + "\"accent_color\":" + color + ","
                + "\"components\":["
                + "{\"type\":10,\"content\":\"" + escape(content) + "\"},"
                + "{\"type\":14,\"divider\":true,\"spacing\":1},"
                + "{\"type\":10,\"content\":\"" + escape(footer) + "\"}"
                + "]}]}";
    }

    private static String errorBody(HttpURLConnection conn) {
        try (InputStream in = conn.getErrorStream()) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
