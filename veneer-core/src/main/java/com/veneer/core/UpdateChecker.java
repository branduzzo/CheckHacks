package com.veneer.core;

import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.veneer.VeneerPlugin;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Looks up the latest GitHub release of Veneer and tells admins when a newer one exists. */
public final class UpdateChecker {

    public static final String REPO = "ReAndrew/veneer";
    public static final String PERMISSION = "veneer.admin";

    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final Pattern NUMBERS = Pattern.compile("\\d+");

    /** Outcome of a lookup; {@code latest} is null when the release could not be determined. */
    public record Status(String current, String latest, String url) {
        public boolean updateAvailable() {
            return latest != null && isNewer(latest, current);
        }

        /** A build newer than the latest release, e.g. one from dev or beta. */
        public boolean ahead() {
            return latest != null && isNewer(current, latest);
        }
    }

    private final VeneerPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile Status status;

    public UpdateChecker(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    /** The last lookup, or null before the first one finished. */
    public Status status() {
        return status;
    }

    /** Starts the first lookup; join notices come from {@link PlayerEvents}. */
    public void start() {
        start(null);
    }

    /** As {@link #start()}; {@code done} runs after the first lookup finished, whether or not it succeeded. */
    public void start(Runnable done) {
        lookup(true, done);
    }

    /** Queries GitHub off the main thread and, when {@code announce} is set, logs a newer release. */
    public void lookup(boolean announce) {
        lookup(announce, null);
    }

    private void lookup(boolean announce, Runnable done) {
        FoliaScheduler.runAsync(plugin, () -> {
            try {
                check(announce);
            } finally {
                if (done != null) done.run();
            }
        });
    }

    private void check(boolean announce) {
        String current = plugin.version();
        try {
            status = fetch(current);
        } catch (IOException | RuntimeException e) {
            plugin.debug("Update check failed: " + e.getMessage());
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (announce && status.updateAvailable()) {
            plugin.getLogger().info("Veneer " + status.latest() + " is available (running " + current + "): " + status.url());
            if (plugin.config().updateNotifyOnJoin())
                for (Player p : plugin.getServer().getOnlinePlayers())
                    if (p.hasPermission(PERMISSION))
                        FoliaScheduler.runAtEntity(plugin, p, () -> notify(p, status));
        }
    }

    private Status fetch(String current) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Veneer/" + current)
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return new Status(current, null, null);   // no release published yet
        if (response.statusCode() != 200) throw new IOException("GitHub answered HTTP " + response.statusCode());
        JsonObject json = new Gson().fromJson(response.body(), JsonObject.class);
        String tag = json.get("tag_name").getAsString();
        String url = json.has("html_url") ? json.get("html_url").getAsString() : "https://github.com/" + REPO + "/releases";
        return new Status(current, tag.startsWith("v") ? tag.substring(1) : tag, url);
    }

    /** Tells a permitted player about a newer release; called from {@link PlayerEvents}. */
    public void onJoin(Player player) {
        Status s = status;
        if (s == null || !s.updateAvailable() || !plugin.config().updateNotifyOnJoin()) return;
        if (!player.hasPermission(PERMISSION)) return;
        FoliaScheduler.runAtEntityLater(plugin, player, () -> notify(player, s), 40L);
    }

    private void notify(Player player, Status s) {
        if (!player.isOnline()) return;
        plugin.messages().send(player, "update-available", "latest", s.latest(), "current", s.current(), "url", s.url());
    }

    /** True when {@code latest} has a higher numeric version than {@code current} (1.10.0 &gt; 1.9.0). */
    static boolean isNewer(String latest, String current) {
        int[] a = parts(latest), b = parts(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String version) {
        Matcher m = NUMBERS.matcher(version.split("[-+]", 2)[0]);
        java.util.List<Integer> out = new java.util.ArrayList<>();
        while (m.find()) {
            try {
                out.add(Integer.parseInt(m.group()));
            } catch (NumberFormatException e) {
                out.add(Integer.MAX_VALUE);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }
}
