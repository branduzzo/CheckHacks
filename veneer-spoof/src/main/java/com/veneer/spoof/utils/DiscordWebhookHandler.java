package com.veneer.spoof.utils;

import com.veneer.spoof.SpoofModule;
import com.veneer.VeneerPlugin;
import com.veneer.core.Text;
import com.veneer.core.PlayerProfile;
import com.veneer.core.Webhook;
import com.veneer.spoof.managers.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DiscordWebhookHandler {
    private final VeneerPlugin plugin;
    private final ConfigManager config;

    // Map to track players who have already been alerted for spoofing in the current session
    private final Map<UUID, Boolean> alertedPlayers = new ConcurrentHashMap<>();

    // Map to track all pending violations for a player
    private final Map<UUID, List<String>> pendingViolations = new ConcurrentHashMap<>();

    // Map to track player brands for alerts
    private final Map<UUID, String> playerBrands = new ConcurrentHashMap<>();

    // Map to track blocked channels for alerts
    private final Map<UUID, String> blockedChannels = new ConcurrentHashMap<>();

    public DiscordWebhookHandler(VeneerPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.module(SpoofModule.class).config();
    }

    /**
     * Starts collecting a player's violations for the alert
     * @param playerUuid The UUID of the player
     */
    public void registerPlayerJoin(UUID playerUuid) {
        pendingViolations.put(playerUuid, new ArrayList<>());

        if (config.isDebugMode()) {
            plugin.getLogger().info("[Discord] Registered join time for player with UUID: " + playerUuid);
        }
    }

    /**
     * Sends an alert to Discord webhook
     * @param player The player who triggered the alert
     * @param reason The reason for the alert
     * @param brand The client brand
     * @param channel The channel that triggered the alert (can be null)
     * @param violations List of all violations (for multiple flags)
     * @param violationType What was detected ({@code BLOCKED_CHANNEL}, {@code NO_BRAND}, ...); picks the console
     *                      message, so the reason text is never parsed
     */
    public void sendAlert(Player player, String violationType, String reason, String brand, String channel,
                          List<String> violations) {
        if (!config.isDiscordWebhookEnabled()) {
            return;
        }

        String webhookUrl = config.getDiscordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            return;
        }

        UUID playerUuid = player.getUniqueId();
        PlayerProfile data = plugin.profiles().get(playerUuid);

        // No data, no channels to check
        if (data == null) {
            return;
        }

        // Store brand for this player
        if (brand != null) {
            playerBrands.put(playerUuid, brand);
        }

        // Store any blocked channel for future reference
        if ("BLOCKED_CHANNEL".equals(violationType) && channel != null) {
            blockedChannels.put(playerUuid, channel);
        }

        // Add this violation to the pending list
        List<String> pendingList = pendingViolations.computeIfAbsent(playerUuid, k -> new ArrayList<>());
        if (!pendingList.contains(reason)) {
            pendingList.add(reason);
        }

        // Skip if player has already been alerted for spoofing in this session
        if (alertedPlayers.getOrDefault(playerUuid, false)) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Player " + player.getName() + " already alerted for spoofing in this session, collecting additional violations");
            }

            return;
        }

        // Mark player as alerted for this session
        alertedPlayers.put(playerUuid, true);

        // Get the configured delay before sending discord alerts (in seconds)
        int delaySeconds = config.getCheckDelay();

        // If there's a delay configured, schedule the webhook after the delay
        if (delaySeconds > 0) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Scheduling alert for player: " + player.getName() +
                                       " after " + delaySeconds + " seconds");
            }

            // Schedule the webhook after the configured delay
            // On the player's thread: PlaceholderAPI expansions read the player and are not thread-safe
            com.veneer.core.FoliaScheduler.runAtEntityLater(plugin, player, () -> {
                // The player may have left during the delay
                PlayerProfile updatedData = plugin.profiles().get(playerUuid);
                if (updatedData != null && player.isOnline()) {
                    // Get all collected violations
                    List<String> allViolations = pendingViolations.getOrDefault(playerUuid, new ArrayList<>());
                    String playerBrand = playerBrands.getOrDefault(playerUuid, brand);
                    String blockedChannel = blockedChannels.get(playerUuid);

                    if (config.isDebugMode()) {
                        plugin.getLogger().info("[Discord] Sending delayed spoofing alert for player: " +
                                               player.getName() + " with " + allViolations.size() + " violations");
                    }

                    // Send the full webhook with all violations
                    sendFullWebhook(player, violationType, reason, playerBrand, blockedChannel, allViolations);
                }
            }, delaySeconds * 20L); // Convert seconds to ticks (20 ticks = 1 second)
        } else {
            // No delay, send immediate webhook
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Sending immediate spoofing alert for player: " +
                                       player.getName());
            }

            // Get all collected violations
            List<String> allViolations = pendingViolations.getOrDefault(playerUuid, new ArrayList<>());

            // Send the full webhook with all violations, on the player's thread (checks run async)
            List<String> toSend = allViolations.isEmpty() ? violations : List.copyOf(allViolations);
            com.veneer.core.FoliaScheduler.runAtEntity(plugin, player,
                    () -> sendFullWebhook(player, violationType, reason, brand, channel, toSend));
        }
    }

    /**
     * Handle player logout - reset their alert status to enable new alerts when they log back in
     * @param uuid The player's UUID
     */
    public void handlePlayerQuit(UUID uuid) {
        // Remove from all trackers to ensure they get alerts on next login
        alertedPlayers.remove(uuid);
        pendingViolations.remove(uuid);
        playerBrands.remove(uuid);
        blockedChannels.remove(uuid);

        if (config.isDebugMode()) {
            plugin.getLogger().info("[Discord] Reset alert status for player with UUID: " + uuid);
        }
    }

    /**
     * Sends a full webhook with all player information
     */
    private void sendFullWebhook(Player player, String violationType, String reason, String brand, String channel,
                                 List<String> violations) {
        // Get the appropriate console alert message
        String consoleAlert = determineConsoleAlert(player, violationType, reason, brand, channel, violations);

        // Send the full webhook
        sendWebhookDirectly(player, reason, brand, channel, violations, consoleAlert);
    }

    /**
     * Determines the appropriate console alert message based on the violation type
     */
    private String determineConsoleAlert(Player player, String violationType, String reason, String brand,
                                         String channel, List<String> violations) {
        String shownBrand = Text.inert(brand != null ? brand : "unknown");
        // For multiple violations, use the multiple flags console message
        if (violations != null && violations.size() > 1) {
            return config.getConsoleMultipleFlagsMessage()
                    .replace("%player%", player.getName())
                    .replace("%brand%", shownBrand)
                    .replace("%reasons%", Text.inert(String.join(", ", violations)));
        }

        String template = switch (violationType) {
            case "VANILLA_WITH_CHANNELS" -> config.getVanillaCheckConsoleAlertMessage();
            case "NON_VANILLA_WITH_CHANNELS" -> config.getNonVanillaCheckConsoleAlertMessage();
            case "BLOCKED_CHANNEL", "CHANNEL_WHITELIST" -> config.getBlockedChannelsConsoleAlertMessage();
            case "GEYSER_SPOOF" -> config.getGeyserSpoofConsoleAlertMessage();
            case "NO_BRAND" -> config.getNoBrandConsoleAlertMessage();
            case "CLIENT_BRAND", "JOIN_BRAND", "UNKNOWN_BRAND" -> brandConsoleMessage(brand);
            default -> config.getConsoleAlertMessage();
        };
        String alert = template
                .replace("%player%", player.getName())
                .replace("%brand%", shownBrand)
                .replace("%reason%", Text.inert(reason));
        return channel != null ? alert.replace("%channel%", Text.inert(channel)) : alert;
    }

    /** The console message of the client-brands entry matching {@code brand}, else the blocked-brands one. */
    private String brandConsoleMessage(String brand) {
        String brandKey = config.isClientBrandsEnabled() ? config.getMatchingClientBrand(brand) : null;
        return brandKey != null ? config.getClientBrandConfig(brandKey).getConsoleAlertMessage()
                : config.getBlockedBrandsConsoleAlertMessage();
    }

    /**
     * Sends the alert through the spoof.discord templates. Placeholders: %player%, %uuid%, %reason%, %brand%,
     * %version%, %violations% and %channels% (one bullet per line), %alert% (the console line), plus PlaceholderAPI.
     */
    private void sendWebhookDirectly(Player player, String reason, String brand, String channel,
                                    List<String> violations, String consoleAlert) {
        String webhookUrl = config.getDiscordWebhookUrl();
        if (webhookUrl.isEmpty()) {
            plugin.getLogger().warning("[Discord] No valid webhook URL (spoof.discord.webhook or general.discord.webhook).");
            return;
        }

        plugin.debug("[Discord] Sending webhook for player: " + player.getName());
        Map<String, String> values = new HashMap<>();
        values.put("player", player.getName());
        values.put("uuid", player.getUniqueId().toString());
        values.put("reason", reason == null ? "" : reason);
        values.put("brand", Text.inert(brand != null ? brand : "unknown"));
        values.put("version", clientVersion(player));
        values.put("violations", violationLines(reason, violations));
        values.put("channels", channelLines(player));
        values.put("alert", consoleAlert == null ? "" : consoleAlert);
        plugin.webhook().report(webhookUrl, Webhook.color(config.getDiscordEmbedColor(), 0x2AB7CA),
                config.getDiscordEmbedTitle(), config.getDiscordMessage(), config.isDiscordUseComponentsV2(), player, values);
    }

    /** One bullet per violation; what follows the last ": " (the brand, channel or pattern) goes in backticks. */
    private static String violationLines(String reason, List<String> violations) {
        List<String> list = new ArrayList<>();
        if (violations != null && !violations.isEmpty()) list.addAll(violations);
        else if (reason != null && !reason.isEmpty()) list.add(reason);
        StringBuilder sb = new StringBuilder();
        for (String violation : list) {
            int clientIndex = violation.indexOf(" (Client: ");
            String clean = clientIndex > 0 ? violation.substring(0, clientIndex) : violation;
            int cut = clean.lastIndexOf(": ");
            if (sb.length() > 0) sb.append('\n');
            if (cut > 0 && cut + 2 < clean.length())
                sb.append("• ").append(clean, 0, cut + 2).append('`').append(clean.substring(cut + 2).replace('`', '\u02cb')).append('`');
            else
                sb.append("• ").append(clean);
        }
        return sb.toString();
    }

    private String channelLines(Player player) {
        PlayerProfile data = plugin.profiles().get(player.getUniqueId());
        if (data == null || data.getChannels().isEmpty()) return "• none";
        StringBuilder sb = new StringBuilder();
        for (String ch : new java.util.TreeSet<>(data.getChannels())) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("• `").append(ch.replace('`', '\u02cb')).append('`');
        }
        return sb.toString();
    }

    /** The client version PacketEvents reports, else ViaVersion's through PlaceholderAPI, else "unknown". */
    private String clientVersion(Player player) {
        String version = com.veneer.core.ClientVersions.name(player);
        if (!"unknown".equals(version)) return version;
        if (Bukkit.getPluginManager().isPluginEnabled("ViaVersion")) {
            String via = Webhook.fill("%viaversion_player_protocol_version%", player, Map.of());
            if (!via.startsWith("%")) return via;
        }
        return version;
    }
}
