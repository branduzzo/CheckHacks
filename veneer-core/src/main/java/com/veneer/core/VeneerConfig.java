package com.veneer.core;

import com.veneer.VeneerPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The single config.yml. Loaded once per (re)load and shared by every module, which each read their own
 * top-level section ({@code general}, {@code spoof}, {@code hacks}, {@code rptrack}).
 *
 * <p>On load, keys that the bundled default config has but the file lacks are added (with their comments),
 * so an update never leaves a module reading a setting that does not exist. Entries inside the
 * {@link #USER_OWNED} sections are admin-defined lists and are never filled in or flagged.
 */
public final class VeneerConfig {

    /** Sections whose children the admin adds and removes freely. */
    private static final List<String> USER_OWNED = List.of(
            "spoof.client-brands.brands",
            "hacks.detection.hacks",
            "hacks.detection.command-rules",
            "hacks.language-check.languages");

    private static final Pattern WEBHOOK = Pattern.compile(
            "https://(?:[a-z]+\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/.+");

    private final VeneerPlugin plugin;
    private final File file;
    private YamlConfiguration cfg;

    public VeneerConfig(VeneerPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "config.yml");
        reload();
    }

    public void reload() {
        if (!file.exists()) plugin.saveResource("config.yml", false);
        cfg = YamlConfiguration.loadConfiguration(file);
        sync();
    }

    /** A top-level or nested section; an empty one (with a warning) when missing. */
    public ConfigurationSection section(String path) {
        ConfigurationSection s = cfg.getConfigurationSection(path);
        if (s == null) {
            plugin.getLogger().warning("config.yml: missing section '" + path + "', using defaults.");
            s = new YamlConfiguration();
        }
        return s;
    }

    // ------------------------------------------------------------ general

    public ConfigurationSection general() { return section("general"); }

    public boolean debug()          { return cfg.getBoolean("general.debug", false); }
    public String language()        { return cfg.getString("general.language", "en"); }
    public String prefix()          { return cfg.getString("general.prefix", "<gold>[Veneer]</gold> <gray>"); }
    public boolean alertsDefault()  { return cfg.getBoolean("general.alerts.default-enabled", true); }

    public boolean bedrockPrefixCheck()     { return cfg.getBoolean("general.bedrock.prefix-check", true); }
    public List<String> bedrockPrefixes()   { return cfg.getStringList("general.bedrock.prefixes"); }

    public boolean updateCheckEnabled()     { return cfg.getBoolean("general.update-checker.enabled", true); }
    public boolean updateNotifyOnJoin()     { return cfg.getBoolean("general.update-checker.notify-on-join", true); }

    /**
     * The webhook to use for a module: its own URL when set, else {@code general.discord.webhook}.
     * Empty when neither is a valid Discord webhook URL.
     */
    public String webhook(String own) {
        if (isWebhook(own)) return own.trim();
        String shared = cfg.getString("general.discord.webhook", "");
        return isWebhook(shared) ? shared.trim() : "";
    }

    public static boolean isWebhook(String url) {
        return url != null && WEBHOOK.matcher(url.trim()).matches();
    }

    // --------------------------------------------------------------- sync

    private void sync() {
        YamlConfiguration defaults;
        try (var in = plugin.getResource("config.yml")) {
            if (in == null) return;
            defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read bundled config.yml: " + e.getMessage());
            return;
        }

        List<String> created = new ArrayList<>();
        int added = 0;
        for (String key : defaults.getKeys(true)) {
            if (cfg.contains(key, true) || inUserOwned(key)) continue;
            created.add(key);
            Object value = defaults.get(key);
            if (value instanceof ConfigurationSection) continue;
            cfg.set(key, value);
            added++;
        }
        for (String key : created) {
            List<String> comments = defaults.getComments(key);
            if (!comments.isEmpty() && cfg.contains(key, true)) cfg.setComments(key, comments);
        }
        if (added > 0) {
            try {
                cfg.save(file);
                plugin.getLogger().info("config.yml: added " + added + " missing setting(s) from the defaults.");
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save config.yml: " + e.getMessage());
            }
        }

        for (String key : cfg.getKeys(true)) {
            if (inUserOwned(key) || defaults.contains(key, true)) continue;
            if (hasUnknownAncestor(defaults, key)) continue;
            plugin.getLogger().warning("config.yml: unknown setting '" + key + "' is ignored"
                    + " (renamed or removed in this version?).");
        }
    }

    /** True when a parent of {@code key} is already unknown, so only the top of an unknown branch is reported. */
    private static boolean hasUnknownAncestor(ConfigurationSection defaults, String key) {
        for (int dot = key.lastIndexOf('.'); dot > 0; dot = key.lastIndexOf('.', dot - 1))
            if (!defaults.contains(key.substring(0, dot), true)) return true;
        return false;
    }

    /** True for a user-owned section's children (the section itself is still merged and checked). */
    private static boolean inUserOwned(String path) {
        for (String owned : USER_OWNED)
            if (path.startsWith(owned + ".")) return true;
        return false;
    }
}
