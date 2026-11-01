package com.veneer.spoof.managers;

import net.kyori.adventure.text.Component;
import com.veneer.core.Messages;
import com.veneer.VeneerPlugin;
import com.veneer.spoof.SpoofModule;
import org.bukkit.entity.Player;
import com.veneer.core.Text;

import java.util.function.Function;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AlertManager {
    private final VeneerPlugin plugin;
    private final ConfigManager config;
    
    // Track alert cooldowns by player UUID and alert type
    private final Map<UUID, Map<String, Long>> lastAlerts = new ConcurrentHashMap<>();
    
    // Alert cooldown in milliseconds (3 seconds by default)
    private static final long ALERT_COOLDOWN = 3000;
    
    public AlertManager(VeneerPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.spoof().config();
    }
    
    /**
     * Sends a message to all players with alert permission
     * @param message The message to send
     */
    public void sendAlertToRecipients(String message) {
        // {prefix} works in every spoof.yml alert message like it does in messages/*.yml. Text.rich renders
        // MiniMessage (gradients, hex) natively and still accepts legacy & codes in the message.
        plugin.alerts().sendToPlayers(Text.rich(message.replace("{prefix}", plugin.config().prefix())));
    }
    
    /**
     * Client-supplied brand or channel text for console commands and the log: a conservative allowlist of
     * characters a real namespaced id can contain, length-capped; anything else (quotes, semicolons, newlines,
     * MiniMessage tags) is dropped so it cannot forge a log line or command argument.
     */
    public static String safeToken(String value) {
        if (value == null) return "unknown";
        if (SpoofModule.BRAND_NONE.equals(value)) return value; // our own constant, not client text
        String cleaned = value.replaceAll("[^A-Za-z0-9_.:\\-/]", "");
        if (cleaned.length() > 64) cleaned = cleaned.substring(0, 64);
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    /** Free text (violation reasons) going into a console command or the log: no command metacharacters or
     *  newlines, whitespace collapsed, length-capped. */
    public static String safeText(String value) {
        if (value == null) return "unknown";
        String cleaned = value.replaceAll("[;&|<>`$'\"\\\\\\r\\n\\t\\u0000]", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 128) cleaned = cleaned.substring(0, 128);
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    /**
     * Checks if an alert can be sent for a player
     * @param playerUUID The UUID of the player
     * @param alertType The type of alert
     * @return True if an alert can be sent, false otherwise
     */
    public boolean canSendAlert(UUID playerUUID, String alertType) {
        long now = System.currentTimeMillis();
        boolean[] allowed = new boolean[1];
        // compute is atomic per key: two racing callers can never both get "true"
        lastAlerts.computeIfAbsent(playerUUID, k -> new ConcurrentHashMap<>())
                .compute(alertType, (type, last) -> {
                    if (last == null || now - last > ALERT_COOLDOWN) {
                        allowed[0] = true;
                        return now;
                    }
                    return last;
                });
        return allowed[0];
    }
    
    /**
     * Sends a custom alert message
     * @param player The player related to the alert
     * @param alertMessage The alert message
     * @param consoleMessage The console message
     * @param alertType The type of alert for cooldown
     */
    public void sendCustomAlert(Player player, String alertMessage, String consoleMessage, String alertType) {
        if (!canSendAlert(player.getUniqueId(), alertType)) {
            return;
        }
        
        // Log to console
        plugin.getLogger().info(consoleMessage);
        
        // Send to players with permission
        sendAlertToRecipients(alertMessage);
    }
    
    /**
     * Sends a simple brand alert for a player (used when no specific brand config exists)
     * @param player The player
     * @param brand The client brand
     */
    public void sendSimpleBrandAlert(Player player, String brand) {
        // Format the player alert message with placeholders
        String playerAlert = config.getBlockedBrandsAlertMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", Text.inert(brand != null ? brand : "unknown"));
        
        // Format the console alert message with placeholders (sanitized: the brand is client-supplied)
        String consoleAlert = config.getBlockedBrandsConsoleAlertMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", safeToken(brand));
        
        // Log to console
        plugin.getLogger().info(consoleAlert);
        
        // Notify players with permission using our optimized list
        sendAlertToRecipients(playerAlert);
        
        // Send to Discord if brand join alerts are enabled
        if (config.isDiscordWebhookEnabled() && 
            config.isBlockedBrandsDiscordAlertEnabled()) {
            
            plugin.spoof().discord().sendAlert(
                player, 
                "Joined with client brand: " + brand,
                brand,
                null,
                null
            );
        }
    }
    
    /**
     * Sends a brand join alert for a player
     * @param player The player who joined
     * @param brand The player's client brand
     */
    public void sendBrandJoinAlert(Player player, String brand) {
        if (!config.isJoinBrandAlertsEnabled() || !canSendAlert(player.getUniqueId(), "JOIN_BRAND")) {
            return;
        }
        
        // Use the centralized method to ensure no duplicates
        plugin.spoof().sendBrandAlert(player, brand, null);
    }
    
    /**
     * Sends a multiple violations alert for a player
     * @param player The player with violations
     * @param violations The list of violation reasons
     * @param brand The player's client brand
     */
    public void sendMultipleViolationsAlert(Player player, List<String> violations, String brand) {
        if (!canSendAlert(player.getUniqueId(), "MULTIPLE_VIOLATIONS")) {
            return;
        }
        
        // Join all reasons with commas
        String reasonsList = String.join(", ", violations);
        
        // Format the player alert message for multiple violations
        String playerAlert = config.getMultipleFlagsMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", Text.inert(brand != null ? brand : "unknown"))
                .replace("%reasons%", reasonsList);
        
        // Format the console alert message for multiple violations
        String consoleAlert = config.getConsoleMultipleFlagsMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", safeToken(brand))
                .replace("%reasons%", safeText(reasonsList));
        
        // Log to console
        plugin.getLogger().info(consoleAlert);
        
        // Notify players with permission using our optimized list
        sendAlertToRecipients(playerAlert);
        
        // Send to Discord if enabled
        plugin.spoof().discord().sendAlert(player, "Multiple Violations", brand, null, violations);
    }
    
    /**
     * Sends a brand violation alert
     * @param player The player with the violation
     * @param reason The violation reason
     * @param brand The player's client brand
     * @param violatedChannel The violated channel (if applicable)
     * @param violationType The type of violation
     * @param brandConfig The brand configuration
     */
    public void sendBrandViolationAlert(Player player, String reason, String brand, 
                                      String violatedChannel, String violationType,
                                      ConfigManager.ClientBrandConfig brandConfig) {
        if (!canSendAlert(player.getUniqueId(), violationType)) {
            return;
        }
        
        // Get alert message from brand config
        String alertMessage = brandConfig.getAlertMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", Text.inert(brand != null ? brand : "unknown"))
                .replace("%reason%", Text.inert(reason));
        
        // Get console message from brand config
        String consoleMessage = brandConfig.getConsoleAlertMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", safeToken(brand != null ? brand : "unknown"))
                .replace("%reason%", safeText(reason));
        
        if (violatedChannel != null) {
            alertMessage = alertMessage.replace("%channel%", Text.inert(violatedChannel));
            consoleMessage = consoleMessage.replace("%channel%", safeToken(violatedChannel));
        }
        
        // Log to console
        plugin.getLogger().info(consoleMessage);
        
        // Send to players with permission. An unknown brand comes with buttons to allow or block it
        BrandVerdicts.Entry unknown = "UNKNOWN_BRAND".equals(violationType) && brand != null
                ? plugin.spoof().verdicts().seen(brand, player.getName()) : null;
        if (unknown != null)
            plugin.alerts().sendToPlayers(m -> m.with("spoof-unknown-brand",
                    "player", player.getName(), "brand", brand, "id", unknown.id()));
        else
            sendAlertToRecipients(alertMessage);
        
        // Send to Discord if enabled
        if (config.isDiscordWebhookEnabled() && brandConfig.shouldDiscordAlert()) {
            List<String> singleViolation = new ArrayList<>();
            singleViolation.add(reason);
            plugin.spoof().discord().sendAlert(player, reason, brand, violatedChannel, singleViolation);
        }
    }
    
    /**
     * Sends a violation alert for a player
     * @param player The player with the violation
     * @param reason The violation reason
     * @param brand The player's client brand
     * @param violatedChannel The violated channel (if applicable)
     * @param violationType The type of violation
     */
    public void sendViolationAlert(Player player, String reason, String brand, 
                                  String violatedChannel, String violationType) {
        if (!canSendAlert(player.getUniqueId(), violationType)) {
            return;
        }
        
        // Select the appropriate alert message based on violation type
        String alertTemplate;
        String consoleAlertTemplate;
        boolean sendDiscordAlert = false;
        
        switch(violationType) {
            case "VANILLA_WITH_CHANNELS":
                alertTemplate = config.getVanillaCheckAlertMessage();
                consoleAlertTemplate = config.getVanillaCheckConsoleAlertMessage();
                sendDiscordAlert = config.isVanillaCheckDiscordAlertEnabled();
                break;
                
            case "NON_VANILLA_WITH_CHANNELS":
                alertTemplate = config.getNonVanillaCheckAlertMessage();
                consoleAlertTemplate = config.getNonVanillaCheckConsoleAlertMessage();
                sendDiscordAlert = config.isNonVanillaCheckDiscordAlertEnabled();
                break;
                
            case "BLOCKED_CHANNEL":
                alertTemplate = config.getBlockedChannelsAlertMessage();
                consoleAlertTemplate = config.getBlockedChannelsConsoleAlertMessage();
                sendDiscordAlert = config.isBlockedChannelsDiscordAlertEnabled();
                break;
                
            case "CHANNEL_WHITELIST":
                // Use whitelist-specific messages
                alertTemplate = config.getChannelWhitelistAlertMessage();
                consoleAlertTemplate = config.getChannelWhitelistConsoleAlertMessage();
                sendDiscordAlert = config.isBlockedChannelsDiscordAlertEnabled();
                break;
                
            case "GEYSER_SPOOF":
                alertTemplate = config.getGeyserSpoofAlertMessage();
                consoleAlertTemplate = config.getGeyserSpoofConsoleAlertMessage();
                sendDiscordAlert = config.isGeyserSpoofDiscordAlertEnabled();
                break;
                
            case "NO_BRAND":
                alertTemplate = config.getNoBrandAlertMessage();
                consoleAlertTemplate = config.getNoBrandConsoleAlertMessage();
                sendDiscordAlert = config.isNoBrandDiscordAlertEnabled();
                break;
                
            case "MISSING_REQUIRED_CHANNELS":
                alertTemplate = config.getAlertMessage(); // Use general alert for now
                consoleAlertTemplate = config.getConsoleAlertMessage();
                sendDiscordAlert = true;
                break;
                
            case "UNKNOWN_BRAND":
                alertTemplate = config.getClientBrandConfig(null).getAlertMessage();
                consoleAlertTemplate = config.getClientBrandConfig(null).getConsoleAlertMessage();
                sendDiscordAlert = config.getClientBrandConfig(null).shouldDiscordAlert();
                break;
                
            default:
                // Fallback to global messages
                alertTemplate = config.getAlertMessage();
                consoleAlertTemplate = config.getConsoleAlertMessage();
                sendDiscordAlert = true; // Default to true for unknown types
        }
        
        // Format the player alert message with placeholders
        String playerAlert = alertTemplate
                .replace("%player%", player.getName())
                .replace("%brand%", Text.inert(brand != null ? brand : "unknown"))
                .replace("%reason%", Text.inert(reason));
        
        // Format the console alert message with placeholders
        String consoleAlert = consoleAlertTemplate
                .replace("%player%", player.getName())
                .replace("%brand%", safeToken(brand != null ? brand : "unknown"))
                .replace("%reason%", safeText(reason));
        
        if (violatedChannel != null && violationType.equals("BLOCKED_CHANNEL")) {
            playerAlert = playerAlert.replace("%channel%", Text.inert(violatedChannel));
            consoleAlert = consoleAlert.replace("%channel%", safeToken(violatedChannel));
        }
        
        // Log to console
        plugin.getLogger().info(consoleAlert);
        
        // Notify players with permission using our optimized list
        sendAlertToRecipients(playerAlert);
        
        // Send to Discord if enabled and this type should send alerts
        if (config.isDiscordWebhookEnabled() && sendDiscordAlert) {
            List<String> singleViolation = new ArrayList<>();
            singleViolation.add(reason);
            plugin.spoof().discord().sendAlert(player, reason, brand, violatedChannel, singleViolation);
        }
    }
    
    /**
     * Executes brand-specific punishment for a player
     * @param player The player to punish
     * @param reason The reason for punishment
     * @param brand The player's client brand
     * @param violationType The type of violation
     * @param violatedChannel The violated channel (if applicable)
     * @param brandConfig The brand configuration
     */
    public void executeBrandPunishment(Player player, String reason, String brand, 
                                     String violationType, String violatedChannel,
                                     ConfigManager.ClientBrandConfig brandConfig) {
        // Get punishments from brand config
        List<String> punishments = brandConfig.getPunishments();
        
        // If no punishments defined, don't do anything
        if (punishments.isEmpty()) {
            return;
        }
        
        // Execute the punishments
        for (String command : punishments) {
            // Values come from the client: sanitize them before they reach the console command
            String formatted = command.replace("%player%", player.getName())
                                     .replace("%reason%", safeText(reason))
                                     .replace("%brand%", safeToken(brand));
            
            if (violatedChannel != null) {
                formatted = formatted.replace("%channel%", safeToken(violatedChannel));
            }
            
            // Execute command on the main thread
            final String finalCommand = formatted;
            com.veneer.core.FoliaScheduler.runGlobal(plugin, () -> {
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), finalCommand);
            });
        }
    }
    
    /**
     * Executes punishment for a player
     * @param player The player to punish
     * @param reason The reason for punishment
     * @param brand The player's client brand
     * @param violationType The type of violation
     * @param violatedChannel The violated channel (if applicable)
     */
    public void executePunishment(Player player, String reason, String brand, 
                                 String violationType, String violatedChannel) {
        List<String> punishments;
        
        // Select the appropriate punishments based on violation type
        switch(violationType) {
            case "VANILLA_WITH_CHANNELS":
                punishments = config.getVanillaCheckPunishments();
                break;
                
            case "NON_VANILLA_WITH_CHANNELS":
                punishments = config.getNonVanillaCheckPunishments();
                break;
                
            case "BLOCKED_CHANNEL":
            case "CHANNEL_WHITELIST":
                punishments = config.getBlockedChannelsPunishments();
                break;
                
            case "GEYSER_SPOOF":
                punishments = config.getGeyserSpoofPunishments();
                break;
                
            case "NO_BRAND":
                punishments = config.getNoBrandPunishments();
                break;
                
            case "MISSING_REQUIRED_CHANNELS":
                // Check for brand-specific required-channels punishments first
                String brandKey = config.getMatchingClientBrand(brand);
                if (brandKey != null) {
                    ConfigManager.ClientBrandConfig brandConfig = config.getClientBrandConfig(brandKey);
                    
                    // Get the required-channels-punishments first
                    List<String> requiredChannelsPunishments = brandConfig.getRequiredChannelsPunishments();
                    if (!requiredChannelsPunishments.isEmpty()) {
                        if (config.isDebugMode()) {
                            plugin.getLogger().info("[Debug] Using brand's required-channels-punishments for " + player.getName());
                        }
                        punishments = requiredChannelsPunishments;
                        break;
                    }
                    
                    // Fall back to regular brand punishments if required-channel-punishments is empty
                    List<String> brandPunishments = brandConfig.getPunishments();
                    if (!brandPunishments.isEmpty()) {
                        if (config.isDebugMode()) {
                            plugin.getLogger().info("[Debug] Falling back to brand's regular punishments for " + player.getName());
                        }
                        punishments = brandPunishments;
                        break;
                    }
                }
                // If no brand-specific punishments found, fall back to global
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Falling back to global punishments for " + player.getName());
                }
                punishments = config.getPunishments();
                break;
                
            case "UNKNOWN_BRAND":
                // Use default brand config punishments
                punishments = config.getClientBrandConfig(null).getPunishments();
                if (punishments.isEmpty()) {
                    punishments = config.getPunishments();
                }
                break;
                
            default:
                // Fallback to global punishments
                punishments = config.getPunishments();
        }
        
        // If no specific punishments defined, fall back to global
        if (punishments.isEmpty()) {
            punishments = config.getPunishments();
        }
        
        // Execute the punishments
        for (String command : punishments) {
            // Values come from the client: sanitize them before they reach the console command
            String formatted = command.replace("%player%", player.getName())
                                     .replace("%reason%", safeText(reason))
                                     .replace("%brand%", safeToken(brand));
            
            if (violatedChannel != null && violationType.equals("BLOCKED_CHANNEL")) {
                formatted = formatted.replace("%channel%", safeToken(violatedChannel));
            }
            
            // Execute command on the main thread
            final String finalCommand = formatted;
            com.veneer.core.FoliaScheduler.runGlobal(plugin, () -> {
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), finalCommand);
            });
        }
    }
    
    /**
     * Cleans up alert data when a player disconnects
     * @param playerUUID The UUID of the player who disconnected
     */
    public void handlePlayerQuit(UUID playerUUID) {
        lastAlerts.remove(playerUUID);
    }
}