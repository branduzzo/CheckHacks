package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.CommandRule;
import me.branduzzo.checkHacks.DetectionMode;
import me.branduzzo.checkHacks.HackDefinition;
import me.branduzzo.checkHacks.HackResult;
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
    private final Map<String, CommandRule> commandRules = new LinkedHashMap<>();
    private final Map<String, List<CommandRule>> rulesByHack = new HashMap<>();

    private String prefix;
    private long timeoutTicks;
    private long betweenSignTicks;
    private boolean doubleCheck;
    private boolean commandIfPositiveEnabled;
    private String positiveCommand;
    private boolean commandIfProtectedEnabled;
    private String protectedCommand;
    private boolean commandIfCleanEnabled;
    private String cleanCommand;

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
        loadCommandRules();
        cacheSettings();
    }

    private FileConfiguration loadFile(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        } else if ("checkhacks.yml".equals(name)) {
            upgradeHacksConfig(file);
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private void upgradeHacksConfig(File file) {
        try {
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            boolean changed = false;
            if (!cfg.contains("doublecheck")) {
                cfg.set("doublecheck", true);
                changed = true;
            }
            if (!cfg.contains("command-rules")) {
                cfg.set("command-rules.meteorclient1.mod", "meteorclient");
                cfg.set("command-rules.meteorclient1.result", "DETECTED");
                cfg.set("command-rules.meteorclient1.command", "tempban %player% 31d Meteor Client");
                changed = true;
            }
            if (changed) cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("[CheckHacks] Failed to upgrade checkhacks.yml: " + e.getMessage());
        }
    }

    private void loadHacks() {
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
                        section.getString(id + ".mode", "TRANSLATE").toUpperCase());
            } catch (IllegalArgumentException e) {
                mode = DetectionMode.TRANSLATE;
            }
            hacks.put(id, new HackDefinition(id, displayName, key, mode));
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
                            section.getString(name + ".result", "DETECTED").toUpperCase()
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
        prefix                 = masterConfig.getString("prefix", "<yellow>[CheckHacks] <gray>");
        timeoutTicks           = hacksConfig.getLong("timeout-ticks", 200);
        betweenSignTicks       = hacksConfig.getLong("between-sign-ticks", 5);
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

    public String getPrefix()    { return prefix; }
    public String getLanguage()  { return masterConfig.getString("language", "en"); }

    public boolean isBedrockEnabled()        { return masterConfig.getBoolean("bedrock.enabled", true); }
    public List<String> getBedrockPrefixes() { return masterConfig.getStringList("bedrock.prefixes"); }

    public boolean isDiscordEnabled()   { return masterConfig.getBoolean("discord.enabled", false); }
    public String  getWebhookUrl()      { return masterConfig.getString("discord.webhook-url", ""); }
    public int     getEmbedColor()      { return masterConfig.getInt("discord.embed-color", 16776960); }
    public String  getDiscordMessage()  { return masterConfig.getString("discord.message", ""); }
    public boolean isDiscordUseComponentsV2() { return masterConfig.getBoolean("discord.use-components-v2", true); }

    public boolean isWebEditorEnabled() { return masterConfig.getBoolean("web-editor.enabled", true); }
    public int     getWebPort()         { return masterConfig.getInt("web-editor.port", 8080); }
    public String  getWebHost()         { return masterConfig.getString("web-editor.host", "localhost"); }
    public int     getTokenExpireMinutes() { return masterConfig.getInt("web-editor.token-expire-minutes", 10); }

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

    public boolean isDetectFlagEnabled() { return hacksConfig.getBoolean("detect-flag.enabled", false); }
    public boolean isGrimEnabled()       { return hacksConfig.getBoolean("detect-flag.anticheats.grim", true); }
    public boolean isVulcanEnabled()     { return hacksConfig.getBoolean("detect-flag.anticheats.vulcan", true); }
    public boolean isSpartanEnabled()    { return hacksConfig.getBoolean("detect-flag.anticheats.spartan", true); }
    public boolean isMatrixEnabled()     { return hacksConfig.getBoolean("detect-flag.anticheats.matrix", true); }
    public long    getFlagCooldownHours(){ return hacksConfig.getLong("detect-flag.cooldown-hours", 24); }

    public boolean isJoinCheckEnabled()  { return hacksConfig.getBoolean("auto-check-on-join.enabled", false); }
    public boolean isOnlyFirstJoin()     { return hacksConfig.getBoolean("auto-check-on-join.only-first-join", false); }

    public long getTimeoutTicks()     { return timeoutTicks; }
    public long getBetweenSignTicks() { return betweenSignTicks; }

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
    public boolean isLangDiscordUseComponentsV2() { return langConfig.getBoolean("discord.use-components-v2", true); }
    public int     getLangTimeoutTicks()    { return langConfig.getInt("timeout-ticks", 100); }
}