package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.AlertMode;
import me.branduzzo.checkHacks.CheckHacksPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class ConfigWriter {

    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    private final CheckHacksPlugin plugin;
    private final Object lock = new Object();

    public ConfigWriter(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean saveRule(String name, String mod, String result, String command) {
        String ruleName = slug(name);
        if (ruleName.isEmpty() || mod == null || mod.isBlank() || command == null || command.isBlank()) return false;
        if (command.length() > 256) return false;
        String normalizedResult = normalizeResult(result);
        synchronized (lock) {
            File file = new File(plugin.getDataFolder(), "checkhacks.yml");
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            String path = "command-rules." + ruleName;
            cfg.set(path + ".mod", mod.trim());
            cfg.set(path + ".result", normalizedResult);
            cfg.set(path + ".command", command.trim());
            return save(file, cfg);
        }
    }

    public boolean deleteRule(String name) {
        String ruleName = slug(name);
        if (ruleName.isEmpty()) return false;
        synchronized (lock) {
            File file = new File(plugin.getDataFolder(), "checkhacks.yml");
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            if (!cfg.contains("command-rules." + ruleName)) return false;
            cfg.set("command-rules." + ruleName, null);
            return save(file, cfg);
        }
    }

    public boolean saveSettings(Map<String, String> values) {
        synchronized (lock) {
            File configFile = new File(plugin.getDataFolder(), "config.yml");
            File hacksFile = new File(plugin.getDataFolder(), "checkhacks.yml");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
            YamlConfiguration hacks = YamlConfiguration.loadConfiguration(hacksFile);
            boolean configChanged = false;
            boolean hacksChanged = false;

            for (Map.Entry<String, String> entry : values.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue() == null ? "" : entry.getValue().trim();
                switch (key) {
                    case "alerts.default-mode" -> {
                        AlertMode mode = AlertMode.parse(value, AlertMode.ALL);
                        config.set(key, modeName(mode));
                        configChanged = true;
                    }
                    case "discord.enabled", "discord.use-components-v2" -> {
                        config.set(key, parseBoolean(value));
                        configChanged = true;
                    }
                    case "discord.embed-color" -> {
                        config.set(key, clamp(parseInt(value, 16776960), 0, 16777215));
                        configChanged = true;
                    }
                    case "discord.webhook-url" -> {
                        if (!value.isEmpty() && !value.startsWith("http://") && !value.startsWith("https://")) return false;
                        config.set(key, value);
                        configChanged = true;
                    }
                    case "discord.message" -> {
                        config.set(key, entry.getValue());
                        configChanged = true;
                    }
                    case "doublecheck", "auto-check-on-join.enabled" -> {
                        hacks.set(key, parseBoolean(value));
                        hacksChanged = true;
                    }
                    case "timeout-ticks" -> {
                        hacks.set(key, clamp(parseInt(value, 200), 20, 1200));
                        hacksChanged = true;
                    }
                    case "between-sign-ticks" -> {
                        hacks.set(key, clamp(parseInt(value, 1), 0, 100));
                        hacksChanged = true;
                    }
                    default -> {
                    }
                }
            }

            boolean ok = true;
            if (configChanged) ok = save(configFile, config);
            if (hacksChanged) ok = save(hacksFile, hacks) && ok;
            return ok;
        }
    }

    public Map<String, Object> settingsMap() {
        ConfigManager cfg = plugin.getConfigManager();
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("alerts.default-mode", modeName(cfg.getAlertDefaultMode()));
        map.put("discord.enabled", cfg.isDiscordEnabled());
        map.put("discord.webhook-url", cfg.getWebhookUrl());
        map.put("discord.embed-color", cfg.getEmbedColor());
        map.put("discord.use-components-v2", cfg.isDiscordUseComponentsV2());
        map.put("discord.message", cfg.getDiscordMessage());
        map.put("doublecheck", cfg.isDoubleCheckEnabled());
        map.put("timeout-ticks", cfg.getTimeoutTicks());
        map.put("between-sign-ticks", cfg.getBetweenSignTicks());
        map.put("auto-check-on-join.enabled", cfg.isJoinCheckEnabled());
        return map;
    }

    private boolean save(File file, YamlConfiguration cfg) {
        try {
            File backup = new File(file.getParentFile(), file.getName() + ".bak");
            if (file.exists()) Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            cfg.save(tmp);
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Config write failed for " + file.getName() + ": " + e.getMessage());
            return false;
        }
    }

    private static String slug(String input) {
        if (input == null) return "";
        String slug = input.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replaceAll("[^a-z0-9_-]", "");
        return VALID_NAME.matcher(slug).matches() ? slug : "";
    }

    private static String normalizeResult(String raw) {
        if (raw == null) return "DETECTED";
        String value = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (value.equals("ANY") || value.equals("*") || value.isEmpty()) return "ANY";
        return switch (value) {
            case "DETECTED", "NOT_DETECTED", "PROTECTED", "SKIPPED" -> value;
            default -> "DETECTED";
        };
    }

    private static String modeName(AlertMode mode) {
        return switch (mode) {
            case ALL -> "all";
            case ONLY_DETECTED -> "only-detected";
            case OFF -> "off";
        };
    }

    private static boolean parseBoolean(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.equals("true") || lower.equals("on") || lower.equals("1") || lower.equals("yes");
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
