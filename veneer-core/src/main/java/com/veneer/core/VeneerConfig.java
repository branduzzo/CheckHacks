package com.veneer.core;

import com.veneer.VeneerPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The plugin's config files: config.yml for the settings shared by every module, plus one file per
 * module (spoof.yml, hacks.yml, rptrack.yml). Loaded once per (re)load and shared by every module,
 * which each read their own file through {@link #section(String)}.
 *
 * <p>On load, keys that the bundled default of a file has but the file lacks are added (with their
 * comments), so an update never leaves a module reading a setting that does not exist. Entries inside
 * the {@link #USER_OWNED} sections are admin-defined lists and are never filled in or flagged.
 *
 * <p>An old single config.yml (with top-level spoof/hacks/rptrack sections) is split into the module
 * files on the first load, so no settings are lost when updating.
 */
public final class VeneerConfig {

    /** The module config files, one per top-level section of the old single config.yml. */
    private static final List<String> MODULES = List.of("spoof", "hacks", "rptrack");

    /** Sections whose children the admin adds and removes freely, as "file.section" paths. */
    private static final List<String> USER_OWNED = List.of(
            "spoof.client-brands.brands",
            "hacks.detection.hacks",
            "hacks.detection.command-rules",
            "hacks.language-check.languages");

    /** A top-level key line of a YAML file, i.e. a section boundary at column 0. */
    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("[A-Za-z][\\w-]*:");

    private static final Pattern WEBHOOK = Pattern.compile(
            "https://(?:[a-z]+\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/.+");

    private final VeneerPlugin plugin;
    private final File file;
    /** config.yml (general only). Replaced by {@link #reload()}; read from async threads, hence volatile. */
    private volatile YamlConfiguration cfg;
    /** Module file per module name, replaced wholesale by {@link #reload()}; read from async threads. */
    private volatile Map<String, YamlConfiguration> modules = Map.of();
    /** Sections already warned about as missing, so {@link #section(String)} logs once per key. */
    private final Set<String> warnedSections = ConcurrentHashMap.newKeySet();

    public VeneerConfig(VeneerPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "config.yml");
        reload();
    }

    public void reload() {
        splitLegacyConfig();
        cfg = load("config.yml");
        Map<String, YamlConfiguration> fresh = new LinkedHashMap<>();
        for (String module : MODULES) fresh.put(module, load(module + ".yml"));
        modules = fresh;
        warnedSections.clear();
        sync();
    }

    /** Loads one config file, saving the bundled default when it does not exist yet. */
    private YamlConfiguration load(String name) {
        File f = fileOf(name);
        if (!f.exists()) plugin.saveResource(name, false);
        return YamlConfiguration.loadConfiguration(f);
    }

    private File fileOf(String name) { return new File(plugin.getDataFolder(), name); }

    /**
     * A section of a module file ("spoof", "spoof.client-brands") or of config.yml ("general");
     * an empty one (with a one-time warning) when missing.
     */
    public ConfigurationSection section(String path) {
        int dot = path.indexOf('.');
        String head = dot < 0 ? path : path.substring(0, dot);
        String rest = dot < 0 ? "" : path.substring(dot + 1);
        boolean module = MODULES.contains(head);
        YamlConfiguration y = module ? modules.get(head) : cfg;
        String name = module ? head + ".yml" : "config.yml";
        ConfigurationSection s = y == null ? null : rest.isEmpty() ? y : y.getConfigurationSection(rest);
        if (s == null) {
            if (warnedSections.add(path))
                plugin.getLogger().warning(name + ": missing section '" + (rest.isEmpty() ? path : rest)
                        + "', using defaults.");
            s = new YamlConfiguration();
        }
        return s;
    }

    // ------------------------------------------------------------ general

    public ConfigurationSection general() { return section("general"); }

    public boolean debug()          { return cfg.getBoolean("general.debug", false); }
    public String language()        { return cfg.getString("general.language", "en"); }
    public String prefix()          { return cfg.getString("general.prefix", "<gradient:#FFBA00:#03E700>[Veneer]</gradient> <gray>"); }
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

    // ------------------------------------------------------------ legacy split

    /**
     * One-time migration: the module settings used to be top-level sections of config.yml. Every
     * section that has no module file yet is written out to it — as a textual cut, so comments and
     * the admin's own values survive — and removed from config.yml.
     */
    private void splitLegacyConfig() {
        if (!file.exists()) return;
        List<String> lines;
        try {
            lines = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read config.yml to split out the modules: " + e.getMessage());
            return;
        }

        boolean split = false;
        for (String module : MODULES) {
            int[] range = sectionRange(lines, module);
            if (range == null) continue;
            File target = fileOf(module + ".yml");
            if (!target.exists()) {
                List<String> out = new ArrayList<>();
                out.add("# Veneer " + module + " module settings, split out of the old single config.yml.");
                out.add("# Missing settings are filled in from the defaults on load.");
                out.add("");
                for (int i = range[0] + 1; i < range[1]; i++) out.add(dedent(lines.get(i)));
                try {
                    Files.write(target.toPath(), out, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not write " + target.getName() + ": " + e.getMessage()
                            + "; the old section stays in config.yml and is ignored.");
                    continue;
                }
            } else {
                plugin.getLogger().warning(target.getName() + " already exists; the old '" + module
                        + "' section in config.yml is ignored and dropped.");
            }
            lines.subList(range[0], range[1]).clear();
            split = true;
        }

        if (split) {
            try {
                Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
                plugin.getLogger().info("config.yml: moved the module sections into "
                        + String.join(", ", MODULES.stream().map(m -> m + ".yml").toList()) + ".");
            } catch (IOException e) {
                plugin.getLogger().warning("Could not rewrite config.yml after the module split: "
                        + e.getMessage());
            }
        }
    }

    /** Line range of a top-level "name:" section: its key line up to the next top-level key (comments
     *  above the next key belong to this section) or EOF; null when absent. */
    private static int[] sectionRange(List<String> lines, String name) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(name + ":")) start = i;
            else if (start >= 0 && TOP_LEVEL_KEY.matcher(line).lookingAt()) return new int[]{start, i};
        }
        return start >= 0 ? new int[]{start, lines.size()} : null;
    }

    private static String dedent(String line) {
        return line.startsWith("  ") ? line.substring(2) : line;
    }

    // --------------------------------------------------------------- sync

    private void sync() {
        syncFile("config.yml", cfg, "");
        for (Map.Entry<String, YamlConfiguration> e : modules.entrySet())
            syncFile(e.getKey() + ".yml", e.getValue(), e.getKey() + ".");
    }

    /** Adds the keys one file lacks from its bundled defaults (with comments) and warns about unknown ones. */
    private void syncFile(String name, YamlConfiguration loaded, String ownedPrefix) {
        YamlConfiguration defaults;
        try (var in = plugin.getResource(name)) {
            if (in == null) return;
            defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read bundled " + name + ": " + e.getMessage());
            return;
        }

        List<String> created = new ArrayList<>();
        int added = 0;
        for (String key : defaults.getKeys(true)) {
            if (loaded.contains(key, true) || userOwned(ownedPrefix + key)) continue;
            created.add(key);
            Object value = defaults.get(key);
            if (value instanceof ConfigurationSection) continue;
            loaded.set(key, value);
            added++;
        }
        for (String key : created) {
            List<String> comments = defaults.getComments(key);
            if (!comments.isEmpty() && loaded.contains(key, true)) loaded.setComments(key, comments);
        }
        if (added > 0) {
            try {
                loaded.save(fileOf(name));
                plugin.getLogger().info(name + ": added " + added + " missing setting(s) from the defaults.");
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save " + name + ": " + e.getMessage());
            }
        }

        for (String key : loaded.getKeys(true)) {
            if (userOwned(ownedPrefix + key) || defaults.contains(key, true)) continue;
            if (hasUnknownAncestor(defaults, key)) continue;
            plugin.getLogger().warning(name + ": unknown setting '" + key + "' is ignored"
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
    private static boolean userOwned(String path) {
        for (String owned : USER_OWNED)
            if (path.startsWith(owned + ".")) return true;
        return false;
    }
}
