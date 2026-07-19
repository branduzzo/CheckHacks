package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.DetectionMode;
import me.branduzzo.checkHacks.HackDefinition;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

public class ConfigManager {

    private final CheckHacksPlugin plugin;
    private FileConfiguration masterConfig;
    private FileConfiguration hacksConfig;
    private FileConfiguration langConfig;
    private final Map<String, HackDefinition> hacks = new LinkedHashMap<>();

    public ConfigManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        masterConfig = plugin.getConfig();

        hacksConfig = loadFile("checkhacks.yml");
        langConfig  = loadFile("checklang.yml");

        loadHacks();
    }

    private FileConfiguration loadFile(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
        return YamlConfiguration.loadConfiguration(file);
    }

    private void loadHacks() {
        hacks.clear();
        ConfigurationSection section = hacksConfig.getConfigurationSection("hacks");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            String displayName = section.getString(id + ".display-name", id);
            String key = section.getString(id + ".key", "");
            if (key.isBlank()) continue;

            String message = section.getString(id + ".message", "&cPlease disable " + displayName + " before joining again!");
            String command = section.getString(id + ".command", "kick %player% &cPlease disable " + displayName + " before joining again!");

            DetectionMode mode;
            try {
                mode = DetectionMode.valueOf(
                        section.getString(id + ".mode", "TRANSLATE").toUpperCase());
            } catch (IllegalArgumentException e) {
                mode = DetectionMode.TRANSLATE;
            }

            hacks.put(id, new HackDefinition(id, displayName, key, message, mode));
        }
        plugin.getLogger().info("Loaded " + hacks.size() + " hacks.");
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

    public String getPrefix()    { return masterConfig.getString("prefix", "<yellow>[CheckHacks] <gray>"); }
    public String getLanguage()  { return masterConfig.getString("language", "en"); }

    public boolean isDiscordEnabled()   { return masterConfig.getBoolean("discord.enabled", false); }
    public String  getWebhookUrl()      { return masterConfig.getString("discord.webhook-url", ""); }
    public int     getEmbedColor()      { return masterConfig.getInt("discord.embed-color", 16776960); }
    public String  getDiscordMessage()  { return masterConfig.getString("discord.message", ""); }

    public boolean isBedrockEnabled()        { return masterConfig.getBoolean("bedrock.enabled", true); }
    public List<String> getBedrockPrefixes() { return masterConfig.getStringList("bedrock.prefixes"); }

    public boolean isWebEditorEnabled() { return masterConfig.getBoolean("web-editor.enabled", true); }
    public int     getWebPort()         { return masterConfig.getInt("web-editor.port", 8080); }
    public String  getWebHost()         { return masterConfig.getString("web-editor.host", "localhost"); }
    public int     getTokenExpireMinutes() { return masterConfig.getInt("web-editor.token-expire-minutes", 10); }

    public boolean isCommandIfPositiveEnabled() { return hacksConfig.getBoolean("command-if-positive.enabled", false); }
    public String  getPositiveCommand()         { return hacksConfig.getString("command-if-positive.command", ""); }

    public boolean isCommandIfProtectedEnabled() { return hacksConfig.getBoolean("command-if-protected.enabled", false); }
    public String  getProtectedCommand()         { return hacksConfig.getString("command-if-protected.command", ""); }

    public boolean isCommandIfCleanEnabled() { return hacksConfig.getBoolean("command-if-clean.enabled", false); }
    public String  getCleanCommand()         { return hacksConfig.getString("command-if-clean.command", ""); }

    public boolean isDetectFlagEnabled() { return hacksConfig.getBoolean("detect-flag.enabled", false); }
    public boolean isGrimEnabled()       { return hacksConfig.getBoolean("detect-flag.anticheats.grim", true); }
    public boolean isVulcanEnabled()     { return hacksConfig.getBoolean("detect-flag.anticheats.vulcan", true); }
    public boolean isSpartanEnabled()    { return hacksConfig.getBoolean("detect-flag.anticheats.spartan", true); }
    public long    getFlagCooldownHours(){ return hacksConfig.getLong("detect-flag.cooldown-hours", 24); }

    public boolean isJoinCheckEnabled()  { return hacksConfig.getBoolean("auto-check-on-join.enabled", false); }
    public boolean isOnlyFirstJoin()     { return hacksConfig.getBoolean("auto-check-on-join.only-first-join", false); }

    public int getTimeoutTicks()      { return hacksConfig.getInt("timeout-ticks", 200); }
    public int getBetweenSignTicks()  { return hacksConfig.getInt("between-sign-ticks", 20); }

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
    public String  getLangWebhookUrl()      { return langConfig.getString("discord.webhook-url", ""); }
    public int     getLangEmbedColor()      { return langConfig.getInt("discord.embed-color", 5763719); }
    public String  getLangDiscordMessage()  { return langConfig.getString("discord.message", ""); }
    public int     getLangTimeoutTicks()    { return langConfig.getInt("timeout-ticks", 100); }
}