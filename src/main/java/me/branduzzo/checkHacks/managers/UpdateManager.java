package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateManager {

    private static final String API_URL = "https://api.github.com/repos/branduzzo/CheckHacks/releases/latest";

    private final CheckHacksPlugin plugin;
    private volatile String latestVersion;
    private volatile String downloadUrl;
    private volatile boolean updateAvailable;
    private volatile boolean downloading;

    public UpdateManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    public void checkForUpdates() {
        FoliaScheduler.runAsync(plugin, () -> {
            try {
                String json = fetchJson(API_URL);
                if (json == null) return;
                String tag = extractJsonString(json, "tag_name");
                String url = extractAssetUrl(json);
                if (tag == null || url == null) return;
                String current = plugin.getDescription().getVersion();
                if (isNewer(tag, current)) {
                    latestVersion = tag;
                    downloadUrl = url;
                    updateAvailable = true;
                    plugin.getLogger().info("Update available: " + tag + " (current: " + current + ")");
                } else {
                    updateAvailable = false;
                }
            } catch (Exception e) {
                plugin.getLogger().info("Update check skipped (no internet or error): " + e.getMessage());
            }
        });
    }

    public boolean isUpdateAvailable() {
        return updateAvailable && latestVersion != null && downloadUrl != null;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public boolean isDownloading() {
        return downloading;
    }

    public void downloadAndUpdate(CommandSender sender) {
        if (!isUpdateAvailable()) {
            sender.sendMessage(plugin.getMessageManager().get("update-no-update", Map.of()));
            return;
        }
        if (downloading) {
            sender.sendMessage(plugin.getMessageManager().get("update-downloading", Map.of("version", latestVersion)));
            return;
        }
        downloading = true;
        sender.sendMessage(plugin.getMessageManager().get("update-downloading", Map.of("version", latestVersion)));
        FoliaScheduler.runAsync(plugin, () -> {
            File pluginsDir = plugin.getDataFolder().getParentFile();
            String fileName = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1);
            if (!fileName.toLowerCase().endsWith(".jar")) fileName = "CheckHacks-" + latestVersion.replaceFirst("^v", "") + ".jar";
            File tempFile = new File(pluginsDir, fileName + ".tmp");
            File targetFile = new File(pluginsDir, fileName);
            try {
                URL url = URI.create(downloadUrl).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "CheckHacks-UpdateChecker");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new RuntimeException("HTTP " + code);
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tempFile)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                }
                conn.disconnect();
                if (targetFile.exists()) {
                    if (!targetFile.delete()) {
                        File backup = new File(pluginsDir, fileName + ".old");
                        if (backup.exists()) backup.delete();
                        targetFile.renameTo(backup);
                    }
                }
                if (!tempFile.renameTo(targetFile)) {
                    throw new RuntimeException("Failed to move temp file");
                }
                File currentFile = getPluginFile();
                if (currentFile != null && currentFile.exists() && !currentFile.getAbsolutePath().equals(targetFile.getAbsolutePath())) {
                    try {
                        if (!currentFile.delete()) {
                            currentFile.deleteOnExit();
                        }
                    } catch (Exception ignored) {}
                }
                FoliaScheduler.runGlobal(plugin, () -> {
                    sender.sendMessage(plugin.getMessageManager().get("update-complete", Map.of("version", latestVersion)));
                    if (sender instanceof Player p) {
                        p.sendMessage(plugin.getMessageManager().get("update-restart-required", Map.of()));
                    } else {
                        sender.sendMessage(plugin.getMessageManager().get("update-restart-required", Map.of()));
                    }
                });
                plugin.getLogger().info("Update downloaded: " + targetFile.getName());
            } catch (Exception e) {
                if (tempFile.exists()) tempFile.delete();
                FoliaScheduler.runGlobal(plugin, () -> {
                    sender.sendMessage(plugin.getMessageManager().get("update-failed", Map.of("error", e.getMessage())));
                });
                plugin.getLogger().warning("Update download failed: " + e.getMessage());
            } finally {
                downloading = false;
            }
        });
    }

    public void notifyIfAvailable(Player player) {
        if (!isUpdateAvailable()) return;
        if (!player.hasPermission("checkhacks.update")) return;
        FoliaScheduler.runAtEntityLater(plugin, player, () -> {
            if (!player.isOnline()) return;
            player.sendMessage(plugin.getMessageManager().get("update-available", Map.of("version", latestVersion.replaceFirst("^v", ""))));
        }, 20L);
    }

    private static String fetchJson(String urlStr) throws Exception {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "CheckHacks-UpdateChecker");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            return null;
        }
        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes());
        } finally {
            conn.disconnect();
        }
    }

    private static String extractJsonString(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String extractAssetUrl(String json) {
        Pattern p = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.jar)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        Pattern p2 = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m2 = p2.matcher(json);
        if (m2.find()) return m2.group(1);
        return null;
    }

    private boolean isNewer(String latestTag, String currentVer) {
        String l = latestTag.replaceFirst("^v", "").trim();
        String c = currentVer.replaceFirst("^v", "").trim();
        if (l.equalsIgnoreCase(c)) return false;
        String[] lp = l.split("[.\\-]");
        String[] cp = c.split("[.\\-]");
        int len = Math.max(lp.length, cp.length);
        for (int i = 0; i < len; i++) {
            String ls = i < lp.length ? lp[i] : "0";
            String cs = i < cp.length ? cp[i] : "0";
            boolean lAlpha = ls.matches(".*[A-Za-z].*");
            boolean cAlpha = cs.matches(".*[A-Za-z].*");
            String lNum = ls.replaceAll("[^0-9]", "");
            String cNum = cs.replaceAll("[^0-9]", "");
            int li = lNum.isEmpty() ? 0 : Integer.parseInt(lNum);
            int ci = cNum.isEmpty() ? 0 : Integer.parseInt(cNum);
            if (li != ci) return li > ci;
            if (lAlpha != cAlpha) {
                if (lAlpha && !cAlpha) return false;
                if (!lAlpha && cAlpha) return true;
            }
            if (!ls.equalsIgnoreCase(cs)) {
                int cmp = ls.compareToIgnoreCase(cs);
                if (cmp != 0) return cmp > 0;
            }
        }
        return l.compareToIgnoreCase(c) > 0;
    }

    private File getPluginFile() {
        try {
            URL loc = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            File f = new File(loc.toURI());
            if (f.isFile()) return f;
        } catch (Exception ignored) {}
        try {
            java.lang.reflect.Method m = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredMethod("getFile");
            m.setAccessible(true);
            Object o = m.invoke(plugin);
            if (o instanceof File f) return f;
        } catch (Exception ignored) {}
        return null;
    }
}
