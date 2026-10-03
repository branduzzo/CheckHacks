package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.*;
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
    private FileConfiguration clientConfig;
    private final Map<String, HackDefinition> hacks = new LinkedHashMap<>();
    private final Map<String, CommandRule> commandRules = new LinkedHashMap<>();
    private final Map<String, List<CommandRule>> rulesByHack = new HashMap<>();

    /* ---------------- client checks (checkclient.yml) ---------------- */
    private final List<BrandRule> brandRules = new ArrayList<>();
    private final List<String> brandSuspiciousPatterns = new ArrayList<>();
    private final List<ChannelModRule> channelModRules = new ArrayList<>();
    private final List<String> suspiciousChannels = new ArrayList<>();
    private final List<String> ignoredChannels = new ArrayList<>();
    private final List<ClientCommandRule> clientCommandRules = new ArrayList<>();

    private boolean brandCheckEnabled;
    private boolean brandAlertOnSuspicious;
    private boolean modListCheckEnabled;
    private boolean modListAlertOnSuspicious;
    private boolean clientShowChannels;
    private List<CheckType> clientDefaultChecks = new ArrayList<>();
    private long betweenChecksTicks;
    private long chainTimeoutTicks;
    private boolean clientJoinCheckEnabled;
    private List<CheckType> clientJoinCheckTypes = new ArrayList<>();
    private long clientJoinDelayTicks;
    private boolean clientFlagCheckEnabled;
    private List<CheckType> clientFlagCheckTypes = new ArrayList<>();
    private boolean clientDiscordEnabled;
    private String clientWebhookUrl;
    private int clientEmbedColor;
    private String clientDiscordMessage;
    private boolean clientDiscordComponentsV2;
    private boolean clientCommandIfSuspiciousEnabled;
    private String clientCommandIfSuspicious;
    private boolean clientCommandIfCleanEnabled;
    private String clientCommandIfClean;

    /* default rules, used when checkclient.yml has no rules at all */
    private static final List<String> DEFAULT_IGNORED_CHANNELS = List.of(
            "minecraft:*", "bungeecord*", "velocity*", "paper*", "spigot*", "bukkit*");

    private static final List<String> DEFAULT_SUSPICIOUS_BRANDS = List.of(
            "*wurst*", "*meteor*", "*impact*", "*aristois*", "*liquidbounce*", "*kamiblue*",
            "*inertia*", "*salhack*", "*konas*", "*novoline*", "*sigma*", "*gamesense*",
            "*bleachhack*", "*future*", "*tlauncher*", "*cracked*", "*cheatbreaker*");

    private static final List<String> DEFAULT_SUSPICIOUS_CHANNELS = List.of(
            "*wurst*", "*meteor*", "*impact*", "*aristois*", "*liquidbounce*", "*kamiblue*",
            "*inertia*", "*salhack*", "*konas*", "*novoline*", "*sigma*", "*gamesense*",
            "*bleachhack*", "*baritone*", "*seedcracker*", "*world_download*", "*wdl*",
            "*trouser*", "*anhero*", "*newernewchunks*");

    private static final Object[][] DEFAULT_BRAND_RULES = {
            {"vanilla", "vanilla", "Vanilla", "VANILLA"},
            {"fabric", "fabric*", "Fabric", "FABRIC"},
            {"forge", "forge*", "Forge", "FORGE"},
            {"neoforge", "neoforge*", "NeoForge", "NEOFORGE"},
            {"quilt", "quilt*", "Quilt", "QUILT"},
            {"lunar", "*lunar*", "Lunar Client", "LUNAR"},
            {"badlion", "*badlion*", "Badlion Client", "BADLION"},
            {"feather", "*feather*", "Feather Client", "FEATHER"},
            {"labymod", "*labymod*", "LabyMod", "LABYMOD"},
            {"geyser", "*geyser*", "Bedrock (Geyser)", "BEDROCK"}
    };

    private static final Object[][] DEFAULT_MOD_RULES = {
            {"fabric-api", "fabric*", "Fabric API", "false"},
            {"sodium", "*sodium*", "Sodium", "false"},
            {"iris", "*iris*", "Iris Shaders", "false"},
            {"modmenu", "*modmenu*", "Mod Menu", "false"},
            {"xaero-minimap", "*xaerominimap*", "Xaero's Minimap", "false"},
            {"xaero-worldmap", "*xaeroworldmap*", "Xaero's World Map", "false"},
            {"journeymap", "*journeymap*", "JourneyMap", "false"},
            {"litematica", "*litematica*", "Litematica", "false"},
            {"minihud", "*minihud*", "MiniHUD", "false"},
            {"itemscroller", "*itemscroller*", "Item Scroller", "false"},
            {"tweakeroo", "*tweakeroo*", "Tweakeroo", "false"},
            {"replaymod", "*replaymod*", "Replay Mod", "false"},
            {"worldedit-cui", "*worldedit:cui*", "WorldEdit CUI", "false"},
            {"freecam", "*freecam*", "Freecam", "false"},
            {"autofish", "*autofish*", "AutoFish", "false"},
            {"baritone", "*baritone*", "Baritone", "true"},
            {"wurst", "*wurst*", "Wurst Client", "true"},
            {"meteor", "*meteor*", "Meteor Client", "true"},
            {"impact", "*impact*", "Impact", "true"},
            {"aristois", "*aristois*", "Aristois", "true"},
            {"liquidbounce", "*liquidbounce*", "LiquidBounce", "true"},
            {"bleachhack", "*bleachhack*", "BleachHack", "true"},
            {"kamiblue", "*kamiblue*", "KamiBlue", "true"},
            {"inertia", "*inertia*", "Inertia", "true"},
            {"salhack", "*salhack*", "SalHack", "true"},
            {"konas", "*konas*", "Konas", "true"},
            {"novoline", "*novoline*", "Novoline", "true"},
            {"sigma", "*sigma*", "Sigma", "true"},
            {"gamesense", "*gamesense*", "GameSense", "true"},
            {"world-downloader", "*wdl*", "World Downloader", "true"},
            {"seedcracker", "*seedcracker*", "SeedCracker", "true"},
            {"trouser-streak", "*trouser*", "Trouser Streak", "true"}
    };

    /* ---------------- classic checkhacks.yml settings ---------------- */
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

        hacksConfig  = loadFile("checkhacks.yml");
        langConfig   = loadFile("checklang.yml");
        clientConfig = loadFile("checkclient.yml");

        loadHacks();
        loadCommandRules();
        loadClientRules();
        cacheSettings();
        cacheClientSettings();
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

    private void loadClientRules() {
        brandRules.clear();
        brandSuspiciousPatterns.clear();
        channelModRules.clear();
        suspiciousChannels.clear();
        ignoredChannels.clear();
        clientCommandRules.clear();

        ConfigurationSection brands = clientConfig.getConfigurationSection("brand.known-brands");
        if (brands != null) {
            for (String id : brands.getKeys(false)) {
                String pattern = brands.getString(id + ".pattern", "");
                if (pattern.isBlank()) continue;
                String name = brands.getString(id + ".display-name", id);
                ClientType type = parseClientType(brands.getString(id + ".type", "UNKNOWN"));
                brandRules.add(new BrandRule(id, pattern, name, type));
            }
        }
        if (brandRules.isEmpty()) {
            for (Object[] row : DEFAULT_BRAND_RULES)
                brandRules.add(new BrandRule((String) row[0], (String) row[1], (String) row[2],
                        parseClientType((String) row[3])));
        }

        brandSuspiciousPatterns.addAll(clientConfig.getStringList("brand.suspicious-patterns"));
        if (brandSuspiciousPatterns.isEmpty()) brandSuspiciousPatterns.addAll(DEFAULT_SUSPICIOUS_BRANDS);

        ConfigurationSection mods = clientConfig.getConfigurationSection("modlist.mods");
        if (mods != null) {
            for (String id : mods.getKeys(false)) {
                String pattern = mods.getString(id + ".pattern", "");
                if (pattern.isBlank()) continue;
                String name = mods.getString(id + ".display-name", id);
                boolean suspicious = mods.getBoolean(id + ".suspicious", false);
                channelModRules.add(new ChannelModRule(id, pattern, name, suspicious));
            }
        }
        if (channelModRules.isEmpty()) {
            for (Object[] row : DEFAULT_MOD_RULES)
                channelModRules.add(new ChannelModRule((String) row[0], (String) row[1],
                        (String) row[2], Boolean.parseBoolean((String) row[3])));
        }

        suspiciousChannels.addAll(clientConfig.getStringList("modlist.suspicious-channels"));
        if (suspiciousChannels.isEmpty()) suspiciousChannels.addAll(DEFAULT_SUSPICIOUS_CHANNELS);

        ignoredChannels.addAll(clientConfig.getStringList("modlist.ignored-channels"));
        if (ignoredChannels.isEmpty()) ignoredChannels.addAll(DEFAULT_IGNORED_CHANNELS);

        ConfigurationSection rules = clientConfig.getConfigurationSection("command-rules");
        if (rules != null) {
            for (String name : rules.getKeys(false)) {
                String match = rules.getString(name + ".match", "");
                String cmd   = rules.getString(name + ".command", "");
                if (match.isBlank() || cmd.isBlank()) continue;
                CheckType check = CheckType.byId(rules.getString(name + ".check", "any"));
                clientCommandRules.add(new ClientCommandRule(name, check, match, cmd));
            }
        }
    }

    private ClientType parseClientType(String raw) {
        if (raw == null) return ClientType.UNKNOWN;
        try {
            return ClientType.valueOf(raw.trim().toUpperCase().replace(" ", "_").replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return ClientType.UNKNOWN;
        }
    }

    private List<CheckType> parseCheckTypes(List<String> ids, List<CheckType> fallback) {
        List<CheckType> types = CheckType.byIds(ids);
        return types.isEmpty() ? fallback : types;
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

    private void cacheClientSettings() {
        brandCheckEnabled       = clientConfig.getBoolean("brand.enabled", true);
        brandAlertOnSuspicious  = clientConfig.getBoolean("brand.alert-on-suspicious", true);
        modListCheckEnabled     = clientConfig.getBoolean("modlist.enabled", true);
        modListAlertOnSuspicious = clientConfig.getBoolean("modlist.alert-on-suspicious", true);
        clientShowChannels      = clientConfig.getBoolean("modlist.show-channels", true);

        clientDefaultChecks = parseCheckTypes(clientConfig.getStringList("default-checks"),
                List.of(CheckType.BRAND, CheckType.MODLIST, CheckType.HACK));

        betweenChecksTicks = clientConfig.getLong("between-checks-ticks", 10);
        chainTimeoutTicks  = clientConfig.getLong("chain-timeout-ticks", 400);

        clientJoinCheckEnabled = clientConfig.getBoolean("auto-check-on-join.enabled", false);
        clientJoinCheckTypes   = parseCheckTypes(clientConfig.getStringList("auto-check-on-join.checks"),
                List.of(CheckType.BRAND, CheckType.MODLIST));
        clientJoinDelayTicks   = clientConfig.getLong("auto-check-on-join.delay-ticks", 60);

        clientFlagCheckEnabled = clientConfig.getBoolean("detect-flag.enabled", true);
        clientFlagCheckTypes   = parseCheckTypes(clientConfig.getStringList("detect-flag.checks"),
                List.of(CheckType.BRAND, CheckType.MODLIST));

        clientDiscordEnabled        = clientConfig.getBoolean("discord.enabled", false);
        clientWebhookUrl            = clientConfig.getString("discord.webhook-url", "");
        clientEmbedColor            = clientConfig.getInt("discord.embed-color", 16776960);
        clientDiscordMessage        = clientConfig.getString("discord.message", "");
        clientDiscordComponentsV2   = clientConfig.getBoolean("discord.use-components-v2", true);

        clientCommandIfSuspiciousEnabled = clientConfig.getBoolean("command-if-suspicious.enabled", false);
        clientCommandIfSuspicious        = clientConfig.getString("command-if-suspicious.command", "");
        clientCommandIfCleanEnabled      = clientConfig.getBoolean("command-if-clean.enabled", false);
        clientCommandIfClean             = clientConfig.getString("command-if-clean.command", "");
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

    /* ---------------- client check accessors ---------------- */

    public boolean isBrandCheckEnabled()        { return brandCheckEnabled; }
    public boolean isModListCheckEnabled()      { return modListCheckEnabled; }
    public boolean isBrandAlertOnSuspicious()   { return brandAlertOnSuspicious; }
    public boolean isModListAlertOnSuspicious() { return modListAlertOnSuspicious; }
    public boolean isClientShowChannels()       { return clientShowChannels; }

    public List<BrandRule> getBrandRules()                 { return brandRules; }
    public List<String> getBrandSuspiciousPatterns()       { return brandSuspiciousPatterns; }
    public List<ChannelModRule> getChannelModRules()       { return channelModRules; }
    public List<String> getSuspiciousChannels()            { return suspiciousChannels; }
    public List<String> getIgnoredChannels()               { return ignoredChannels; }
    public List<ClientCommandRule> getClientCommandRules() { return clientCommandRules; }

    public List<CheckType> getClientDefaultChecks()   { return clientDefaultChecks; }
    public List<CheckType> getClientJoinCheckTypes()  { return clientJoinCheckTypes; }
    public List<CheckType> getClientFlagCheckTypes()  { return clientFlagCheckTypes; }

    public long getBetweenChecksTicks() { return betweenChecksTicks; }
    public long getChainTimeoutTicks()  { return chainTimeoutTicks; }

    public boolean isClientJoinCheckEnabled() { return clientJoinCheckEnabled; }
    public long    getClientJoinDelayTicks()  { return clientJoinDelayTicks; }
    public boolean isClientFlagCheckEnabled() { return clientFlagCheckEnabled; }

    public boolean isClientDiscordEnabled()       { return clientDiscordEnabled; }
    public String  getClientWebhookUrl()          { return clientWebhookUrl; }
    public int     getClientEmbedColor()          { return clientEmbedColor; }
    public String  getClientDiscordMessage()      { return clientDiscordMessage; }
    public boolean isClientDiscordComponentsV2()  { return clientDiscordComponentsV2; }

    public boolean isClientCommandIfSuspiciousEnabled() { return clientCommandIfSuspiciousEnabled; }
    public String  getClientCommandIfSuspicious()       { return clientCommandIfSuspicious; }
    public boolean isClientCommandIfCleanEnabled()      { return clientCommandIfCleanEnabled; }
    public String  getClientCommandIfClean()            { return clientCommandIfClean; }
}
