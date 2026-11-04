package com.veneer.spoof;

import com.github.retrooper.packetevents.PacketEvents;
import com.veneer.core.PacketEventsHook;
import com.veneer.VeneerPlugin;
import com.veneer.core.Text;
import com.veneer.core.PlayerProfile;
import com.veneer.core.VeneerModule;
import com.veneer.spoof.commands.SpoofCommand;
import com.veneer.spoof.listeners.PlayerEventListener;
import com.veneer.spoof.managers.AlertManager;
import com.veneer.spoof.managers.BrandVerdicts;
import com.veneer.spoof.managers.ConfigManager;
import com.veneer.spoof.managers.DetectionManager;
import com.veneer.spoof.utils.DiscordWebhookHandler;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Client spoof detection (from AntiSpoof): compares the client brand and registered plugin channels with
 * what the claimed client can really send. Owns the brand channel, the PacketEvents hook and the detection
 * state; the per-player data itself lives in the shared {@link PlayerProfile}.
 */
public class SpoofModule extends VeneerModule {

    private static final String BRAND_CHANNEL = "minecraft:brand";

    private ConfigManager config;
    private AlertManager alertManager;
    private DetectionManager detection;
    private PlayerEventListener events;
    private DiscordWebhookHandler discord;
    private BrandVerdicts verdicts;

    public SpoofModule(VeneerPlugin plugin) {
        super(plugin);
    }

    @Override public String id() { return "spoof"; }

    @Override public List<String> commandNames() { return List.of("spoof"); }

    @Override
    protected void enable() {
        verdicts = new BrandVerdicts(plugin);
        config = new ConfigManager(plugin);
        alertManager = new AlertManager(plugin);
        detection = new DetectionManager(plugin);
        discord = new DiscordWebhookHandler(plugin);

        // The lite jar runs on the PacketEvents plugin, which has already loaded and started it
        if (PacketEventsHook.bundled()) {
            PacketEvents.setAPI(SpigotPacketEventsBuilder.build(plugin));
            PacketEvents.getAPI().getSettings()
                    .reEncodeByDefault(false)
                    .checkForUpdates(false);
            PacketEvents.getAPI().load();
        }

        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, BRAND_CHANNEL,
                (channel, player, message) -> onBrand(player, new String(message).substring(1)));

        events = new PlayerEventListener(plugin);
        PacketEvents.getAPI().getEventManager().registerListener(events);

        command("spoof", new SpoofCommand(plugin));
        if (PacketEventsHook.bundled()) PacketEvents.getAPI().init();

        if (!config.isDiscordWebhookEnabled()) {
            plugin.debug("Spoof Discord alerts are disabled.");
        } else if (plugin.config().webhook(config.getDiscordWebhookUrl()).isEmpty()) {
            plugin.getLogger().warning("spoof.discord is enabled but no valid webhook URL is set"
                    + " (spoof.discord.webhook or general.discord.webhook).");
        }
    }

    @Override
    protected void disable() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, BRAND_CHANNEL);
        if (PacketEvents.getAPI() == null) return;
        if (PacketEventsHook.bundled()) PacketEvents.getAPI().terminate();
        else if (events != null) PacketEvents.getAPI().getEventManager().unregisterListener(events);
    }

    @Override
    protected void reload() {
        config.reload();
    }

    private void onBrand(Player player, String brand) {
        PlayerProfile profile = plugin.profile(player);
        if (brand.equals(profile.getBrand())) return;
        profile.setBrand(brand);
        plugin.debug("Received brand for " + player.getName() + ": " + brand);
        detection.checkPlayerAsync(player, false);
    }

    // ------------------------------------------------------------ accessors

    public ConfigManager config() { return config; }
    public AlertManager alertManager() { return alertManager; }
    /** Staff verdicts on unknown brands; null before the module started. */
    public BrandVerdicts verdicts() { return verdicts; }
    public DetectionManager detection() { return detection; }
    public DiscordWebhookHandler discord() { return discord; }

    /** The brand the client announced, or null if none arrived yet. */
    public String getClientBrand(Player player) {
        return plugin.brandOf(player);
    }

    @Override
    public void playerJoin(Player player) {
        if (events != null) events.onPlayerJoin(player);
    }

    @Override
    public void playerQuit(UUID uuid) {
        detection.handlePlayerQuit(uuid);
        alertManager.handlePlayerQuit(uuid);
        discord.handlePlayerQuit(uuid);
        plugin.debug("Cleaned up all data for player with UUID: " + uuid);
    }

    // ------------------------------------------------------------- alerts

    /** Sends the alert for a player's brand once per session; false if it was suppressed. */
    public boolean sendBrandAlert(Player player, String brand, String brandKey) {
        PlayerProfile profile = plugin.profile(player);
        if (profile.isBrandAlerted()) {
            plugin.debug("Suppressing duplicate brand alert for " + player.getName());
            return false;
        }
        profile.setBrandAlerted(true);

        if (brandKey == null) {
            alertManager.sendSimpleBrandAlert(player, brand);
            return true;
        }

        ConfigManager.ClientBrandConfig brandConfig = config.getClientBrandConfig(brandKey);
        if (!brandConfig.shouldAlert()) return false;

        String alertMessage = brandConfig.getAlertMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", Text.inert(brand));
        String consoleMessage = brandConfig.getConsoleAlertMessage()
                .replace("%player%", player.getName())
                .replace("%brand%", Text.inert(brand));

        plugin.getLogger().info(consoleMessage);
        alertManager.sendAlertToRecipients(alertMessage);

        if (config.isDiscordWebhookEnabled() && brandConfig.shouldDiscordAlert()) {
            List<String> brandInfo = new ArrayList<>();
            brandInfo.add("Client brand: " + brand);
            discord.sendAlert(player, "CLIENT_BRAND", "Using client: " + brandKey, brand, null, brandInfo);
        }
        return true;
    }

    // ---------------------------------------------------------- detection

    public boolean isSpoofingGeyser(Player player) {
        if (player == null || !config.isPunishSpoofingGeyser()) return false;
        String brand = getClientBrand(player);
        if (brand == null) return false;
        return brand.toLowerCase(Locale.ROOT).contains("geyser") && !plugin.bedrock().isBedrock(player);
    }

    /** Whether the player currently trips any enabled spoof check (without punishing or alerting). */
    public boolean isPlayerSpoofing(Player player) {
        if (player == null) return false;

        String brand = getClientBrand(player);
        if (brand == null) {
            return config.isNoBrandCheckEnabled() || config.shouldBlockNonVanillaWithChannels();
        }

        if (plugin.bedrock().isBedrock(player) && config.getBedrockHandlingMode().equals("IGNORE")) return false;

        PlayerProfile data = plugin.profileOf(player.getUniqueId());
        if (data == null) return false;

        Set<String> filteredChannels = detection.getFilteredChannels(data.getChannels());
        boolean hasChannels = !filteredChannels.isEmpty();
        boolean claimsVanilla = brand.equalsIgnoreCase("vanilla");

        if (config.isPunishSpoofingGeyser() && isSpoofingGeyser(player)) return true;
        if (config.isVanillaCheckEnabled() && claimsVanilla && hasChannels) return true;

        if (config.isBlockedBrandsEnabled() && config.isBrandBlocked(brand)) return true;

        if (config.shouldBlockNonVanillaWithChannels() && (!claimsVanilla || hasChannels)) return true;

        if (config.isBlockedChannelsEnabled() && hasChannels) {
            if (config.isChannelWhitelistEnabled()) {
                if (!detection.checkChannelWhitelist(filteredChannels)) return true;
            } else if (detection.findBlockedChannel(filteredChannels) != null) {
                return true;
            }
        }

        String matchedBrand = config.getMatchingClientBrand(brand);
        if (matchedBrand != null && hasChannels) {
            for (Pattern required : config.getClientBrandConfig(matchedBrand).getRequiredChannels()) {
                boolean matched = false;
                for (String channel : filteredChannels) {
                    if (required.matcher(channel).matches()) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) return true;
            }
        }
        return false;
    }
}
