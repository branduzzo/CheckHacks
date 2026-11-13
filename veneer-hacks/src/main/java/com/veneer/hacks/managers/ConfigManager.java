package com.veneer.hacks.managers;

import com.veneer.VeneerPlugin;
import com.veneer.hacks.CommandRule;
import com.veneer.hacks.DetectionMode;
import com.veneer.hacks.HackDefinition;
import com.veneer.hacks.HackResult;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

public class ConfigManager {

    private final VeneerPlugin plugin;
    private ConfigurationSection masterConfig;
    private ConfigurationSection hacksConfig;
    private ConfigurationSection langConfig;
    private final Map<String, HackDefinition> hacks = new LinkedHashMap<>();
    private final Map<String, CommandRule> commandRules = new LinkedHashMap<>();
    private final Map<String, List<CommandRule>> rulesByHack = new HashMap<>();
    /** Levels of the bundled mods, read once from the jar. */
    private Map<String, Integer> bundledLevels;

    private long timeoutTicks;
    private long betweenSignTicks;
    private boolean hideClean;
    private boolean showChannels;
    private boolean adaptiveTimeout;
    private long adaptiveMarginTicks;
    private long adaptiveMinTicks;
    private boolean retryOnTimeout;
    private boolean doubleCheck;
    private boolean commandIfPositiveEnabled;
    private String positiveCommand;
    private boolean commandIfProtectedEnabled;
    private String protectedCommand;
    private boolean commandIfCleanEnabled;
    private String cleanCommand;

    public ConfigManager(VeneerPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        masterConfig = plugin.config().section("hacks");
        hacksConfig  = plugin.config().section("hacks.detection");
        langConfig   = plugin.config().section("hacks.language-check");

        loadHacks();
        loadCommandRules();
        cacheSettings();
    }

    /**
     * Levels of the bundled mods. The hack list is the admin's and is never filled in from the defaults, so a
     * config from before levels existed gets them from here; a mod of the admin's own without one is a cheat.
     */
    private Map<String, Integer> readBundledLevels() {
        Map<String, Integer> out = new HashMap<>();
        try (var in = plugin.getResource("config.yml")) {
            if (in == null) return out;
            var defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            ConfigurationSection s = defaults.getConfigurationSection("hacks.detection.hacks");
            if (s != null) for (String id : s.getKeys(false)) if (s.contains(id + ".level")) out.put(id, s.getInt(id + ".level"));
        } catch (java.io.IOException ignored) {}
        return out;
    }

    /**
     * Level of a mod by id (1 = cheat, 2 = forbidden, 3 = tolerated). Old scan results keep the id of a mod the admin
     * has since removed or renamed: such an id takes its bundled level, or 2 when it was the admin's own.
     */
    public int levelOf(String id) {
        HackDefinition h = hacks.get(id);
        if (h != null) return h.getLevel();
        Map<String, Integer> bundled = bundledLevels;
        return bundled == null ? 2 : bundled.getOrDefault(id, 2);
    }

    private void loadHacks() {
        if (bundledLevels == null) bundledLevels = readBundledLevels();
        hacks.clear();
        ConfigurationSection section = hacksConfig.getConfigurationSection("hacks");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            String displayName = section.getString(id + ".display-name", id);
            String key = section.getString(id + ".key", "");
            if (key.isBlank()) continue;
            DetectionMode mode;
            try {
                mode = DetectionMode.valueOf(
                        section.getString(id + ".mode", "TRANSLATE").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                mode = DetectionMode.TRANSLATE;
            }
            List<String> channels = section.getStringList(id + ".channels");
            int level = section.getInt(id + ".level", bundledLevels.getOrDefault(id, 1));
            hacks.put(id, new HackDefinition(id, displayName, key, mode, channels, level));
        }
        plugin.getLogger().info("Loaded " + hacks.size() + " hacks.");
    }

    private void loadCommandRules() {
        commandRules.clear();
        ConfigurationSection section = hacksConfig.getConfigurationSection("command-rules");
        if (section != null) {
            for (String name : section.getKeys(false)) {
                String mod = section.getString(name + ".mod", "");
                String cmd = section.getString(name + ".command", "");
                if (mod == null || mod.isBlank() || cmd == null || cmd.isBlank()) continue;
                HackResult result;
                try {
                    result = HackResult.valueOf(
                            section.getString(name + ".result", "DETECTED").toUpperCase(Locale.ROOT)
                                    .replace(" ", "_").replace("-", "_"));
                } catch (IllegalArgumentException e) {
                    result = HackResult.DETECTED;
                }
                commandRules.put(name, new CommandRule(name, mod.trim(), result, cmd));
            }
        }
        rulesByHack.clear();
        for (HackDefinition hack : hacks.values()) {
            String idNorm = normalize(hack.getId());
            String dnNorm = normalize(hack.getDisplayName());
            List<CommandRule> list = new ArrayList<>();
            for (CommandRule rule : commandRules.values()) {
                String modNorm = normalize(rule.getMod());
                if (modNorm.equals(idNorm) || modNorm.equals(dnNorm)) list.add(rule);
            }
            rulesByHack.put(hack.getId(), list);
        }
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private void cacheSettings() {
        timeoutTicks           = hacksConfig.getLong("timeout-ticks", 200);
        betweenSignTicks       = hacksConfig.getLong("between-sign-ticks", 5);
        hideClean              = hacksConfig.getBoolean("hide-clean", true);
        showChannels           = hacksConfig.getBoolean("show-channels", true);
        adaptiveTimeout        = hacksConfig.getBoolean("adaptive-timeout", true);
        adaptiveMarginTicks    = hacksConfig.getLong("adaptive-margin-ticks", 4);
        adaptiveMinTicks       = hacksConfig.getLong("adaptive-min-ticks", 20);
        retryOnTimeout         = hacksConfig.getBoolean("retry-on-timeout", true);
        doubleCheck            = hacksConfig.getBoolean("doublecheck", true);
        commandIfPositiveEnabled = hacksConfig.getBoolean("command-if-positive.enabled", false);
        positiveCommand        = hacksConfig.getString("command-if-positive.command", "");
        commandIfProtectedEnabled = hacksConfig.getBoolean("command-if-protected.enabled", false);
        protectedCommand       = hacksConfig.getString("command-if-protected.command", "");
        commandIfCleanEnabled  = hacksConfig.getBoolean("command-if-clean.enabled", false);
        cleanCommand           = hacksConfig.getString("command-if-clean.command", "");
    }

    public Map<String, HackDefinition> getHacks()     { return hacks; }
    public HackDefinition getHack(String id)           { return hacks.get(id); }

    public List<HackDefinition> getDefaultCheckHacks() { return resolveHackList("default-check-hacks"); }
    public List<HackDefinition> getJoinCheckHacks()    { return resolveHackList("auto-check-on-join.hacks"); }
    public List<HackDefinition> getFlagCheckHacks()    { return resolveHackList("detect-flag.hacks"); }

    private List<HackDefinition> resolveHackList(String path) {
        List<HackDefinition> result = new ArrayList<>();
        for (String id : hacksConfig.getStringList(path)) {
            HackDefinition h = hacks.get(id);
            if (h != null) result.add(h);
        }
        return result;
    }

    public boolean isDiscordEnabled()   { return masterConfig.getBoolean("discord.enabled", false); }
    public String  getWebhookUrl()      { return plugin.config().webhook(masterConfig.getString("discord.webhook-url", "")); }
    public int     getEmbedColor()      { return com.veneer.core.Webhook.color(masterConfig.getString("discord.embed-color"), 16776960); }
    public String  getDiscordTitle()    { return masterConfig.getString("discord.title", "Veneer mod scan"); }
    public String  getDiscordMessage()  { return masterConfig.getString("discord.message", ""); }
    public boolean isDiscordUseComponentsV2() { return masterConfig.getBoolean("discord.use-components-v2", true); }

    public boolean isWebEditorEnabled() { return masterConfig.getBoolean("web-editor.enabled", true); }
    public int     getWebPort()         { return masterConfig.getInt("web-editor.port", 8080); }
    public String  getWebHost()         { return masterConfig.getString("web-editor.host", "localhost"); }
    public boolean isWebHttps()         { return masterConfig.getBoolean("web-editor.https", false); }
    public String  getWebBind()         { return masterConfig.getString("web-editor.bind", "127.0.0.1"); }
    public int     getLinkExpireMinutes() { return Math.max(1, masterConfig.getInt("web-editor.link-expire-minutes", 5)); }
    /** Days a connected browser stays logged in without being used. */
    /** Days the browsers of an offline account stay connected when nothing can tell its rights (no LuckPerms). */
    public int     getOfflineAccessDays() { return Math.max(1, masterConfig.getInt("web-editor.offline-access-days", 7)); }
    public int     getSessionExpireDays() { return Math.max(1, masterConfig.getInt("web-editor.session-expire-days", 30)); }
    /** The address players open: {@code host} when it is a full URL (behind an HTTPS proxy), else http://host:port. */
    public String  getPanelUrl() {
        String host = getWebHost().strip();
        if (isWebHttps() && host.startsWith("http://")) host = "https://" + host.substring("http://".length());
        if (host.startsWith("http://") || host.startsWith("https://")) return host.replaceAll("/+$", "");
        return (isWebHttps() ? "https://" : "http://") + host + ":" + getWebPort();
    }
    public boolean isPanelHttps() { return getPanelUrl().startsWith("https://"); }
    /** Whether the panel's traffic leaves this machine unencrypted. */
    public boolean isPanelExposed() {
        if (isPanelHttps()) return false;
        String host = java.net.URI.create(getPanelUrl()).getHost();
        return host == null || !(host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
    }

    public boolean isCommandIfPositiveEnabled() { return commandIfPositiveEnabled; }
    public String  getPositiveCommand()         { return positiveCommand; }

    public boolean isCommandIfProtectedEnabled() { return commandIfProtectedEnabled; }
    public String  getProtectedCommand()         { return protectedCommand; }

    public boolean isCommandIfCleanEnabled() { return commandIfCleanEnabled; }
    public String  getCleanCommand()         { return cleanCommand; }

    public boolean isDoubleCheckEnabled() { return doubleCheck; }

    public List<CommandRule> getRulesForHack(String hackId) {
        return rulesByHack.getOrDefault(hackId, List.of());
    }

    /** Whether a spoof-detection flag queues a mod scan on the flagged player. */
    public boolean isSpoofFlagScanEnabled() { return hacksConfig.getBoolean("detect-spoof-flag.enabled", true); }

    public boolean isDetectFlagEnabled() { return hacksConfig.getBoolean("detect-flag.enabled", false); }
    public boolean isAnticheatEnabled(String id) { return hacksConfig.getBoolean("detect-flag.anticheats." + id, true); }
    public long    getFlagCooldownHours(){ return hacksConfig.getLong("detect-flag.cooldown-hours", 24); }

    public boolean isJoinCheckEnabled()  { return hacksConfig.getBoolean("auto-check-on-join.enabled", false); }
    /** BETA: run the join scan while the client loads the resource pack. */
    public boolean isScanDuringResourcePack() { return hacksConfig.getBoolean("auto-check-on-join.during-resource-pack", false); }
    public boolean isOnlyFirstJoin()     { return hacksConfig.getBoolean("auto-check-on-join.only-first-join", false); }

    public long getTimeoutTicks()     { return timeoutTicks; }
    public long getBetweenSignTicks() { return betweenSignTicks; }
    public String getMinClientVersion() { return hacksConfig.getString("min-client-version", "1.17"); }
    public boolean isHideClean()          { return hideClean; }
    public boolean isShowChannels()       { return showChannels; }
    public boolean isAdaptiveTimeout()    { return adaptiveTimeout; }
    public long getAdaptiveMarginTicks()  { return adaptiveMarginTicks; }
    public long getAdaptiveMinTicks()     { return adaptiveMinTicks; }
    public boolean isRetryOnTimeout()     { return retryOnTimeout; }

    public Map<String, String> getLanguages() {
        Map<String, String> langs = new LinkedHashMap<>();
        ConfigurationSection section = langConfig.getConfigurationSection("languages");
        if (section == null) return langs;
        for (String key : section.getKeys(false))
            langs.put(key, section.getString(key, ""));
        return langs;
    }

    public boolean isLangJoinCheckEnabled() { return langConfig.getBoolean("auto-check-on-join.enabled", false); }
    public boolean isLangOnlyFirstJoin()    { return langConfig.getBoolean("auto-check-on-join.only-first-join", false); }
    public boolean isLangDiscordEnabled()   { return langConfig.getBoolean("discord.enabled", false); }
    public String  getLangWebhookUrl()      { return plugin.config().webhook(langConfig.getString("discord.webhook-url", "")); }
    public int     getLangEmbedColor()      { return com.veneer.core.Webhook.color(langConfig.getString("discord.embed-color"), 5763719); }
    public String  getLangDiscordTitle()    { return langConfig.getString("discord.title", "Veneer language check"); }
    public String  getLangDiscordMessage()  { return langConfig.getString("discord.message", ""); }
    public boolean isVersionCheckEnabled()  { return langConfig.getBoolean("version-check.enabled", true); }
    public String  getVersionMismatchCommand() { return langConfig.getString("version-check.command-if-mismatch", ""); }
    public boolean isLangDiscordUseComponentsV2() { return langConfig.getBoolean("discord.use-components-v2", true); }
    public int     getLangTimeoutTicks()    { return langConfig.getInt("timeout-ticks", 100); }
}