package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.AlertMode;
import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.CommandRule;
import me.branduzzo.checkHacks.DetectionMode;
import me.branduzzo.checkHacks.HackDefinition;
import me.branduzzo.checkHacks.HackResult;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ConfigManager {

    private final CheckHacksPlugin plugin;
    private final ConfigMerger merger;
    private FileConfiguration masterConfig;
    private FileConfiguration hacksConfig;
    private FileConfiguration langConfig;
    private volatile Map<String, HackDefinition> hacks = Map.of();
    private volatile Map<String, CommandRule> commandRules = Map.of();
    private volatile Map<String, List<CommandRule>> rulesByHack = Map.of();

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
    private AlertMode alertDefaultMode;

    public ConfigManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
        this.merger = new ConfigMerger(plugin);
        reload();
    }

    public void reload() {
        int merged = merger.mergeAll();
        if (merged > 0) plugin.getLogger().info("Config merger added " + merged + " missing keys.");
        plugin.reloadConfig();
        masterConfig = plugin.getConfig();
        hacksConfig = loadFile("checkhacks.yml");
        langConfig = loadFile("checklang.yml");
        loadHacks();
        loadCommandRules();
        cacheSettings();
    }

    private FileConfiguration loadFile(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
        return YamlConfiguration.loadConfiguration(file);
    }

    private void loadHacks() {
        Map<String, HackDefinition> loaded = new LinkedHashMap<>();
        ConfigurationSection section = hacksConfig.getConfigurationSection("hacks");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                String displayName = section.getString(id + ".display-name", id);
                String key = section.getString(id + ".key", "");
                DetectionMode mode;
                try {
                    mode = DetectionMode.valueOf(section.getString(id + ".mode", "TRANSLATE").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    mode = DetectionMode.TRANSLATE;
                }
                if (key.isBlank()) continue;
                loaded.put(id, new HackDefinition(id, displayName, key, mode));
            }
        }
        hacks = loaded;
        plugin.getLogger().info("Loaded " + hacks.size() + " hacks.");
    }

    private void loadCommandRules() {
        Map<String, CommandRule> loadedRules = new LinkedHashMap<>();
        ConfigurationSection section = hacksConfig.getConfigurationSection("command-rules");
        if (section != null) {
            for (String name : section.getKeys(false)) {
                String mod = section.getString(name + ".mod", "");
                String cmd = section.getString(name + ".command", "");
                if (cmd == null || cmd.isBlank()) continue;
                if (mod == null || mod.isBlank()) mod = name;
                HackResult result = parseResult(section.getString(name + ".result", "DETECTED"));
                loadedRules.put(name, new CommandRule(name, mod.trim(), result, cmd.trim()));
            }
        }
        Map<String, List<CommandRule>> byHack = new LinkedHashMap<>();
        for (HackDefinition hack : hacks.values()) {
            List<CommandRule> list = new ArrayList<>();
            for (CommandRule rule : loadedRules.values()) {
                if (targets(rule, hack)) list.add(rule);
            }
            byHack.put(hack.getId(), List.copyOf(list));
        }
        commandRules = loadedRules;
        rulesByHack = byHack;
    }

    private HackResult parseResult(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (value.isEmpty() || value.equals("ANY") || value.equals("*")) return null;
        try {
            return HackResult.valueOf(value);
        } catch (IllegalArgumentException e) {
            return HackResult.DETECTED;
        }
    }

    private boolean targets(CommandRule rule, HackDefinition hack) {
        String mod = normalize(rule.getMod());
        if (mod.isEmpty() || mod.equals("*")) return true;
        String id = normalize(hack.getId());
        String display = normalize(hack.getDisplayName());
        return mod.equals(id) || mod.equals(display) || mod.startsWith(id) || id.startsWith(mod);
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9*]", "");
    }

    private void cacheSettings() {
        prefix                   = masterConfig.getString("prefix", "<yellow>[CheckHacks] <gray>");
        timeoutTicks             = Math.max(1L, hacksConfig.getLong("timeout-ticks", 200));
        betweenSignTicks         = Math.max(0L, hacksConfig.getLong("between-sign-ticks", 1));
        doubleCheck              = hacksConfig.getBoolean("doublecheck", true);
        commandIfPositiveEnabled = hacksConfig.getBoolean("command-if-positive.enabled", false);
        positiveCommand          = hacksConfig.getString("command-if-positive.command", "");
        commandIfProtectedEnabled = hacksConfig.getBoolean("command-if-protected.enabled", false);
        protectedCommand         = hacksConfig.getString("command-if-protected.command", "");
        commandIfCleanEnabled    = hacksConfig.getBoolean("command-if-clean.enabled", false);
        cleanCommand             = hacksConfig.getString("command-if-clean.command", "");

        String mode = masterConfig.getString("alerts.default-mode", null);
        if (mode == null) {
            alertDefaultMode = masterConfig.getBoolean("alerts.default-enabled", true) ? AlertMode.ALL : AlertMode.OFF;
        } else {
            alertDefaultMode = AlertMode.parse(mode, AlertMode.ALL);
        }

    }

    public Map<String, HackDefinition> getHacks() { return hacks; }
    public HackDefinition getHack(String id)       { return hacks.get(id); }

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

    public String getPrefix()   { return prefix; }
    public String getLanguage() { return masterConfig.getString("language", "en"); }

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
    public AlertMode getAlertDefaultMode() { return alertDefaultMode; }

    public List<CommandRule> getCommandRules() { return new ArrayList<>(commandRules.values()); }

    public List<CommandRule> getRulesForHack(String hackId) {
        return rulesByHack.getOrDefault(hackId, List.of());
    }

    public boolean isDetectFlagEnabled() { return hacksConfig.getBoolean("detect-flag.enabled", false); }
    public boolean isGrimEnabled()       { return hacksConfig.getBoolean("detect-flag.anticheats.grim", true); }
    public boolean isVulcanEnabled()     { return hacksConfig.getBoolean("detect-flag.anticheats.vulcan", true); }
    public boolean isSpartanEnabled()    { return hacksConfig.getBoolean("detect-flag.anticheats.spartan", true); }
    public boolean isMatrixEnabled()     { return hacksConfig.getBoolean("detect-flag.anticheats.matrix", true); }
    public long    getFlagCooldownHours(){ return hacksConfig.getLong("detect-flag.cooldown-hours", 24); }

    public boolean isJoinCheckEnabled() { return hacksConfig.getBoolean("auto-check-on-join.enabled", false); }
    public boolean isOnlyFirstJoin()    { return hacksConfig.getBoolean("auto-check-on-join.only-first-join", false); }

    public long getTimeoutTicks()     { return timeoutTicks; }
    public long getBetweenSignTicks() { return betweenSignTicks; }

    public Map<String, String> getLanguages() {
        Map<String, String> langs = new LinkedHashMap<>();
        ConfigurationSection section = langConfig.getConfigurationSection("languages");
        if (section == null) return langs;
        for (String key : section.getKeys(false)) langs.put(key, section.getString(key, ""));
        return langs;
    }

    public boolean isLangJoinCheckEnabled() { return langConfig.getBoolean("auto-check-on-join.enabled", false); }
    public boolean isLangOnlyFirstJoin()    { return langConfig.getBoolean("auto-check-on-join.only-first-join", false); }
    public boolean isLangDiscordEnabled()   { return langConfig.getBoolean("discord.enabled", false); }
    public String  getLangWebhookUrl()      { return langConfig.getString("discord.webhook-url", ""); }
    public int     getLangEmbedColor()      { return langConfig.getInt("discord.embed-color", 5763719); }
    public String  getLangDiscordMessage()  { return langConfig.getString("discord.message", ""); }
    public boolean isLangDiscordUseComponentsV2() { return langConfig.getBoolean("discord.use-components-v2", true); }
    public int     getLangTimeoutTicks()    { return Math.max(1, langConfig.getInt("timeout-ticks", 100)); }
}
