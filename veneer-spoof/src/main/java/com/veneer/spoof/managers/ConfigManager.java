package com.veneer.spoof.managers;

import com.veneer.spoof.SpoofModule;
import org.bukkit.configuration.ConfigurationSection;
import com.veneer.VeneerPlugin;

import java.util.Locale;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class ConfigManager {
    /** Built-in brand keys for brands staff allowed or blocked (see {@link BrandVerdicts}). */
    public static final String STAFF_ALLOWED = "staff-allowed";
    public static final String STAFF_BLOCKED = "staff-blocked";

    private final VeneerPlugin plugin;
    private ConfigurationSection config;

    // Cache for compiled regex patterns
    private final Map<String, Pattern> channelPatterns = new HashMap<>();

    // Client brand configurations
    private boolean clientBrandsEnabled;
    // Insertion order: when two patterns match, the brand listed first in the config wins. Replaced
    // wholesale on reload; readers on packet/netty threads must never see a half-cleared map.
    private volatile Map<String, ClientBrandConfig> clientBrands = new LinkedHashMap<>();
    private volatile ClientBrandConfig defaultBrandConfig = new ClientBrandConfig();

    // Class to hold client brand configuration
    public static class ClientBrandConfig {
        private boolean enabled;
        private List<Pattern> patterns = new ArrayList<>();
        private List<String> patternStrings = new ArrayList<>();
        private boolean flag;
        private boolean alert;
        private boolean discordAlert;
        private String alertMessage;
        private String consoleAlertMessage;
        private boolean punish;
        private List<String> punishments = new ArrayList<>();
        private List<Pattern> requiredChannels = new ArrayList<>();
        private List<String> requiredChannelStrings = new ArrayList<>();
        private boolean strictCheck;
        // Added fields for required channels punishment
        private boolean requiredChannelsPunish;
        private List<String> requiredChannelsPunishments = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public List<Pattern> getPatterns() { return patterns; }
        public List<String> getPatternStrings() { return patternStrings; }
        public boolean shouldFlag() { return flag; }
        public boolean shouldAlert() { return alert; }
        public boolean shouldDiscordAlert() { return discordAlert; }
        public String getAlertMessage() { return alertMessage; }
        public String getConsoleAlertMessage() { return consoleAlertMessage; }
        public boolean shouldPunish() { return punish; }
        public List<String> getPunishments() { return punishments; }
        public List<Pattern> getRequiredChannels() { return requiredChannels; }
        public List<String> getRequiredChannelStrings() { return requiredChannelStrings; }
        public boolean hasStrictCheck() { return strictCheck; }
        // Added getters for required channels punishment
        public boolean shouldPunishRequiredChannels() { return requiredChannelsPunish; }
        public List<String> getRequiredChannelsPunishments() { return requiredChannelsPunishments; }
    }

    public ConfigManager(VeneerPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        config = plugin.config().section("spoof");

        // Clear and recompile regex patterns
        channelPatterns.clear();

        // Compile channel patterns
        List<String> channelRegexes = getBlockedChannels();
        for (String regex : channelRegexes) {
            try {
                newPatterns.put(regex, Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                plugin.getLogger().warning("Invalid channel regex pattern: " + regex + " - " + e.getMessage());
            }
        }

        // Load client brand configurations
        loadClientBrandConfigs();
    }

    /**
     * Load all client brand configurations from config
     */
    private void loadClientBrandConfigs() {
        // Clear existing configurations
        clientBrands.clear();

        // Check if client brands system is enabled
        clientBrandsEnabled = config.getBoolean("client-brands.enabled", true);

        // Load default brand config
        ConfigurationSection defaultSection = config.getConfigurationSection("client-brands.default");
        defaultBrandConfig = new ClientBrandConfig();

        if (defaultSection != null) {
            defaultBrandConfig.enabled = true;
            defaultBrandConfig.flag = defaultSection.getBoolean("flag", true);
            defaultBrandConfig.alert = defaultSection.getBoolean("alert", true);
            defaultBrandConfig.discordAlert = defaultSection.getBoolean("discord-alert", false);
            defaultBrandConfig.alertMessage = defaultSection.getString("alert-message",
                "&8[&cVeneer&8] &7%player% using unknown client: &e%brand%");
            defaultBrandConfig.consoleAlertMessage = defaultSection.getString("console-alert-message",
                "%player% using unknown client: %brand%");
            defaultConfig.punish = defaultSection.getBoolean("punish", false);
            defaultConfig.punishments = defaultSection.getStringList("punishments");
        } else {
            // Set up default values if section is missing
            defaultConfig.enabled = true;
            defaultConfig.flag = true;
            defaultConfig.alert = true;
            defaultConfig.discordAlert = false;
            defaultConfig.alertMessage = "<dark_gray>[<red>Veneer</dark_gray>] <gray>%player% using unknown client: <yellow>%brand%";
            defaultConfig.consoleAlertMessage = "%player% using unknown client: %brand%";
            defaultConfig.punish = false;
            defaultConfig.punishments = new ArrayList<>();
        }

        // Brands staff decided on: allowed ones are a known clean client, blocked ones flag like a cheat client
        ClientBrandConfig allowed = new ClientBrandConfig();
        allowed.enabled = true;
        allowed.alertMessage = defaultConfig.alertMessage;
        allowed.consoleAlertMessage = defaultConfig.consoleAlertMessage;
        newBrands.put(STAFF_ALLOWED, allowed);
        ConfigurationSection blockedSection = config.getConfigurationSection("client-brands.staff-verdicts.blocked");
        ClientBrandConfig blocked = new ClientBrandConfig();
        blocked.enabled = true;
        blocked.flag = true;
        blocked.alert = true;
        blocked.alertMessage = blockedSection != null ? blockedSection.getString("alert-message",
                "<dark_gray>[<red>Veneer</dark_gray>] <gray>%player% is using blocked client <red>%brand%") : "<dark_gray>[<red>Veneer</dark_gray>] <gray>%player% is using blocked client <red>%brand%";
        blocked.consoleAlertMessage = blockedSection != null ? blockedSection.getString("console-alert-message",
                "%player% is using blocked client: %brand%") : "%player% is using blocked client: %brand%";
        blocked.discordAlert = blockedSection != null && blockedSection.getBoolean("discord-alert", true);
        blocked.punish = blockedSection != null && blockedSection.getBoolean("punish", false);
        blocked.punishments = blockedSection != null ? blockedSection.getStringList("punishments") : new ArrayList<>();
        newBrands.put(STAFF_BLOCKED, blocked);

        // Load individual brand configurations
        ConfigurationSection brandsSection = config.getConfigurationSection("client-brands.brands");
        if (brandsSection != null) {
            for (String brandKey : brandsSection.getKeys(false)) {
                ConfigurationSection brandSection = brandsSection.getConfigurationSection(brandKey);
                if (brandSection != null && !brandKey.equals(STAFF_ALLOWED) && !brandKey.equals(STAFF_BLOCKED)) {
                    ClientBrandConfig brandConfig = loadBrandConfig(brandSection);
                    clientBrands.put(brandKey, brandConfig);

                    if (isDebugMode()) {
                        plugin.getLogger().info("[Debug] Loaded client brand config: " + brandKey +
                                              " with " + brandConfig.patterns.size() + " patterns");
                    }
                }
            }
        }
        clientBrands = newBrands;
    }

    /**
     * Load a specific brand configuration from a config section
     */
    private ClientBrandConfig loadBrandConfig(ConfigurationSection section) {
        ClientBrandConfig brandConfig = new ClientBrandConfig();
        brandConfig.enabled = section.getBoolean("enabled", true);
        brandConfig.flag = section.getBoolean("flag", false);
        brandConfig.alert = section.getBoolean("alert", true);
        brandConfig.discordAlert = section.getBoolean("discord-alert", false);
        // A configured brand is a known client, so the fallback is neutral; the "unknown client"
        // wording belongs to the default section, which only applies when no pattern matched.
        brandConfig.alertMessage = section.getString("alert-message", "<dark_gray>[<yellow>Veneer</dark_gray>] <gray>%player% is using <aqua>%brand%");
        brandConfig.consoleAlertMessage = section.getString("console-alert-message", "%player% is using %brand%");
        brandConfig.punish = section.getBoolean("punish", false);
        brandConfig.punishments = section.getStringList("punishments");
        brandConfig.strictCheck = section.getBoolean("strict-check", false);

        // Added loading for required channels punishment fields
        brandConfig.requiredChannelsPunish = section.getBoolean("required-channels-punish", false);
        brandConfig.requiredChannelsPunishments = section.getStringList("required-channels-punishments");

        // Load and compile pattern strings
        List<String> patterns = section.getStringList("values");
        brandConfig.patternStrings.addAll(patterns);

        for (String pattern : patterns) {
            try {
                brandConfig.patterns.add(Pattern.compile(pattern));
            } catch (PatternSyntaxException e) {
                plugin.getLogger().warning("Invalid brand pattern: " + pattern + " - " + e.getMessage());
                // Add a simple exact match pattern as fallback
                brandConfig.patterns.add(Pattern.compile("^" + Pattern.quote(pattern) + "$"));
            }
        }

        // Load and compile required channel patterns
        List<String> requiredChannels = section.getStringList("required-channels");
        brandConfig.requiredChannelStrings.addAll(requiredChannels);

        for (String channel : requiredChannels) {
            try {
                brandConfig.requiredChannels.add(Pattern.compile(channel));
            } catch (PatternSyntaxException e) {
                plugin.getLogger().warning("Invalid required channel pattern: " + channel + " - " + e.getMessage());
                // Add a simple exact match pattern as fallback
                brandConfig.requiredChannels.add(Pattern.compile("^" + Pattern.quote(channel) + "$"));
            }
        }

        return brandConfig;
    }

    public int getCheckDelay() {
        return config.getInt("delay-in-seconds", 3);
    }

    public boolean isDebugMode() {
        return plugin.config().debug();
    }

    /**
     * @return Whether the client brands system is enabled
     */
    public boolean isClientBrandsEnabled() {
        return clientBrandsEnabled;
    }

    /**
     * Check if a brand matches any configured client brands
     * @param brand The brand to check
     * @return The configured brand key or null if no match
     */
    public String getMatchingClientBrand(String brand) {
        if (brand == null || !clientBrandsEnabled) return null;

        // A staff verdict on this exact brand comes before the configured patterns
        BrandVerdicts verdicts = plugin.module(SpoofModule.class).verdicts();
        BrandVerdicts.Verdict verdict = verdicts != null ? verdicts.verdict(brand) : null;
        if (verdict != null) return verdict == BrandVerdicts.Verdict.ALLOW ? STAFF_ALLOWED : STAFF_BLOCKED;

        for (Map.Entry<String, ClientBrandConfig> entry : clientBrands.entrySet()) {
            ClientBrandConfig brandConfig = entry.getValue();

            if (!brandConfig.isEnabled()) continue;

            for (Pattern pattern : brandConfig.getPatterns()) {
                try {
                    if (pattern.matcher(brand).matches()) {
                        return entry.getKey();
                    }
                } catch (Exception e) {
                    // If regex fails, try direct comparison
                    if (brand.equals(pattern.pattern())) {
                        return entry.getKey();
                    }
                }
            }
        }

        return null;
    }

    /**
     * Get the configuration for a specific client brand
     * @param brandKey The brand key to get configuration for
     * @return The brand configuration or default if not found
     */
    public ClientBrandConfig getClientBrandConfig(String brandKey) {
        return clientBrands.getOrDefault(brandKey, defaultBrandConfig);
    }



    /**
     * @return Whether brand detection is enabled (maps to client-brands.enabled)
     */
    public boolean isBlockedBrandsEnabled() {
        return isClientBrandsEnabled();
    }

    /**
     * Checks if a brand is blocked under the new system
     * @param brand The brand to check
     * @return True if the brand is blocked, false otherwise
     */
    public boolean isBrandBlocked(String brand) {
        if (!isClientBrandsEnabled()) return false;

        // Match to a client brand
        String matchedBrandKey = getMatchingClientBrand(brand);
        if (matchedBrandKey != null) {
            // Get the client brand config
            ClientBrandConfig brandConfig = getClientBrandConfig(matchedBrandKey);
            // Return whether this brand should be flagged
            return brandConfig.shouldFlag();
        }

        // If no brand pattern matched, use the default config for unknown brands
        return defaultBrandConfig.shouldFlag();
    }


    /**
     * @return Whether brand whitelist mode is enabled (backward compatibility)
     */
    public boolean isBrandWhitelistEnabled() {
        // In the new system, we don't have a global whitelist mode
        // But if any brand with flag: false exists, we can consider it a whitelist
        for (ClientBrandConfig brandConfig : clientBrands.values()) {
            if (brandConfig.isEnabled() && !brandConfig.shouldFlag()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get all brand patterns for backward compatibility
     * @return List of brand patterns
     */
    public List<String> getBlockedBrands() {
        List<String> result = new ArrayList<>();

        // Collect all brand patterns
        for (ClientBrandConfig brandConfig : clientBrands.values()) {
            if (brandConfig.isEnabled()) {
                result.addAll(brandConfig.getPatternStrings());
            }
        }

        return result;
    }

    /**
     * Get the alert message for blocked brands (backward compatibility)
     * @return The alert message
     */
    public String getBlockedBrandsAlertMessage() {
        // Use the default client brand alert message
        return defaultBrandConfig.getAlertMessage();
    }

    /**
     * Get the console alert message for blocked brands (backward compatibility)
     * @return The console alert message
     */
    public String getBlockedBrandsConsoleAlertMessage() {
        // Use the default client brand console alert message
        return defaultBrandConfig.getConsoleAlertMessage();
    }

    /**
     * Check if Discord alerts are enabled for blocked brands (backward compatibility)
     * @return True if Discord alerts are enabled, false otherwise
     */
    public boolean isBlockedBrandsDiscordAlertEnabled() {
        // Use the default client brand discord alert setting
        return defaultBrandConfig.shouldDiscordAlert();
    }



    // Global alert messages (legacy)
    public String getAlertMessage() {
        return config.getString("messages.alert", "&8[&cVeneer&8] &e%player% flagged! &c%reason%");
    }

    public String getConsoleAlertMessage() {
        return config.getString("messages.console-alert", "%player% flagged! %reason%");
    }

    // Multiple flags message (console and Discord)
    public String getConsoleMultipleFlagsMessage() {
        return config.getString("messages.console-multiple-flags", "%player% has multiple violations: %reasons%");
    }

    // Global punishments (legacy)
    public List<String> getPunishments() {
        return config.getStringList("punishments");
    }

    // No Brand Check
    public boolean isNoBrandCheckEnabled() {
        return config.getBoolean("no-brand-check.enabled", true);
    }

    public boolean isNoBrandDiscordAlertEnabled() {
        return config.getBoolean("no-brand-check.discord-alert", true);
    }

    public boolean shouldPunishNoBrand() {
        return config.getBoolean("no-brand-check.punish", false);
    }

    public String getNoBrandAlertMessage() {
        return config.getString("no-brand-check.alert-message",
                               "&8[&cVeneer&8] &e%player% flagged! &cNo client brand detected");
    }

    public String getNoBrandConsoleAlertMessage() {
        return config.getString("no-brand-check.console-alert-message",
                               "%player% flagged! No client brand detected");
    }

    public List<String> getNoBrandPunishments() {
        return config.getStringList("no-brand-check.punishments");
    }

    // Vanilla Spoof Check (claims vanilla but has channels)
    public boolean isVanillaCheckEnabled() {
        return config.getBoolean("vanillaspoof-check.enabled", true);
    }

    public boolean isVanillaCheckDiscordAlertEnabled() {
        return config.getBoolean("vanillaspoof-check.discord-alert", true);
    }

    public boolean shouldPunishVanillaCheck() {
        return config.getBoolean("vanillaspoof-check.punish", true);
    }

    public String getVanillaCheckAlertMessage() {
        return config.getString("vanillaspoof-check.alert-message", getAlertMessage());
    }

    public String getVanillaCheckConsoleAlertMessage() {
        return config.getString("vanillaspoof-check.console-alert-message", getConsoleAlertMessage());
    }

    public List<String> getVanillaCheckPunishments() {
        return config.getStringList("vanillaspoof-check.punishments");
    }

    // Non-Vanilla Check (anything not vanilla with channels)
    public boolean shouldBlockNonVanillaWithChannels() {
        return config.getBoolean("non-vanilla-check.enabled", false);
    }

    public boolean isNonVanillaCheckDiscordAlertEnabled() {
        return config.getBoolean("non-vanilla-check.discord-alert", false);
    }

    public boolean shouldPunishNonVanillaCheck() {
        return config.getBoolean("non-vanilla-check.punish", true);
    }

    public String getNonVanillaCheckAlertMessage() {
        return config.getString("non-vanilla-check.alert-message", getAlertMessage());
    }

    public String getNonVanillaCheckConsoleAlertMessage() {
        return config.getString("non-vanilla-check.console-alert-message", getConsoleAlertMessage());
    }

    public List<String> getNonVanillaCheckPunishments() {
        return config.getStringList("non-vanilla-check.punishments");
    }

    // Blocked Channels Check
    public boolean isBlockedChannelsEnabled() {
        return config.getBoolean("blocked-channels.enabled", false);
    }

    public boolean isBlockedChannelsDiscordAlertEnabled() {
        return config.getBoolean("blocked-channels.discord-alert", false);
    }

    public String getChannelWhitelistMode() {
        return config.getString("blocked-channels.whitelist-mode", "FALSE").toUpperCase(Locale.ROOT);
    }

    public boolean isChannelWhitelistEnabled() {
        String mode = getChannelWhitelistMode();
        return mode.equals("SIMPLE") || mode.equals("STRICT");
    }

    public boolean isChannelWhitelistStrict() {
        return getChannelWhitelistMode().equals("STRICT");
    }

    public List<String> getBlockedChannels() {
        return config.getStringList("blocked-channels.values");
    }

    public String getBlockedChannelsAlertMessage() {
        return config.getString("blocked-channels.alert-message", getAlertMessage());
    }

    public String getBlockedChannelsConsoleAlertMessage() {
        return config.getString("blocked-channels.console-alert-message", getConsoleAlertMessage());
    }

    // Added whitelist message methods
    public String getChannelWhitelistAlertMessage() {
        return config.getString("blocked-channels.whitelist-alert-message",
            "&8[&cVeneer&8] &e%player% flagged! &cChannels don't match whitelist requirements");
    }

    public String getChannelWhitelistConsoleAlertMessage() {
        return config.getString("blocked-channels.whitelist-console-alert-message",
            "%player% flagged! Channels don't match whitelist requirements");
    }

    public boolean shouldPunishBlockedChannels() {
        return config.getBoolean("blocked-channels.punish", true);
    }

    public List<String> getBlockedChannelsPunishments() {
        return config.getStringList("blocked-channels.punishments");
    }

    // Channel regex matching
    public boolean matchesChannelPattern(String channel) {
        if (channel == null) return false;

        for (Map.Entry<String, Pattern> entry : channelPatterns.entrySet()) {
            try {
                if (entry.getValue().matcher(channel).matches()) {
                    return true; // Channel matches a pattern
                }
            } catch (Exception e) {
                // If there's any error with the pattern, try direct comparison as fallback
                if (channel.equals(entry.getKey())) {
                    return true;
                }
            }
        }

        return false; // No patterns matched
    }

    // Bedrock Handling
    public String getBedrockHandlingMode() {
        return config.getString("bedrock-handling.mode", "EXEMPT").toUpperCase(Locale.ROOT);
    }

    public boolean isBedrockExemptMode() {
        return getBedrockHandlingMode().equals("EXEMPT");
    }

    // Geyser Spoof Detection
    public boolean isPunishSpoofingGeyser() {
        return config.getBoolean("bedrock-handling.geyser-spoof.enabled", true);
    }

    public boolean isGeyserSpoofDiscordAlertEnabled() {
        return config.getBoolean("bedrock-handling.geyser-spoof.discord-alert", true);
    }

    public boolean shouldPunishGeyserSpoof() {
        return config.getBoolean("bedrock-handling.geyser-spoof.punish", true);
    }

    public String getGeyserSpoofAlertMessage() {
        return config.getString("bedrock-handling.geyser-spoof.alert-message", getAlertMessage());
    }

    public String getGeyserSpoofConsoleAlertMessage() {
        return config.getString("bedrock-handling.geyser-spoof.console-alert-message", getConsoleAlertMessage());
    }

    public List<String> getGeyserSpoofPunishments() {
        return config.getStringList("bedrock-handling.geyser-spoof.punishments");
    }



    // Global Alert settings
    public boolean isJoinBrandAlertsEnabled() {
        return config.getBoolean("global-alerts.join-brand-alerts", false);
    }


    // Discord webhook settings
    public boolean isDiscordWebhookEnabled() {
        return config.getBoolean("discord.enabled", false);
    }

    /** The webhook for spoof alerts: {@code spoof.discord.webhook}, else {@code general.discord.webhook}. */
    public String getDiscordWebhookUrl() {
        return plugin.config().webhook(config.getString("discord.webhook", ""));
    }

    public String getDiscordEmbedTitle() {
        return config.getString("discord.embed-title", "**Veneer spoof alert**");
    }

    public String getDiscordEmbedColor() {
        return config.getString("discord.embed-color", "#2AB7CA");
    }

    /** The alert text; see DiscordWebhookHandler for the placeholders. */
    public String getDiscordMessage() {
        return config.getString("discord.message", "**Player**: %player%\n**Violations**:\n%violations%\n"
                + "**Client Version**: %version%\n**Brand**: `%brand%`\n**Channels**:\n%channels%");
    }

    public boolean isDiscordUseComponentsV2() {
        return config.getBoolean("discord.use-components-v2", false);
    }


}