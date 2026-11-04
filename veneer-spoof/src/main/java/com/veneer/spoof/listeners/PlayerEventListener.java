package com.veneer.spoof.listeners;

import com.veneer.spoof.SpoofModule;
import com.veneer.VeneerPlugin;
import com.veneer.spoof.managers.ConfigManager;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import org.bukkit.entity.Player;
import java.nio.charset.StandardCharsets;

public class PlayerEventListener extends PacketListenerAbstract {
    private final VeneerPlugin plugin;
    private final ConfigManager config;

    // Extended delay in ticks for required channel checks (5 seconds)
    private static final long REQUIRED_CHANNEL_CHECK_DELAY = 5 * 20L;

    public PlayerEventListener(VeneerPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.module(SpoofModule.class).config();
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;

        Player player = (Player) event.getPlayer();

        // No bypass check here: it would run on every packet. Channels are recorded for everyone (/veneer info
        // shows them); the checks they start honour the bypass in DetectionManager.checkPlayerAsync.
        // Handle plugin messages based on the packet type
        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage packet = new WrapperPlayClientPluginMessage(event);
            handlePluginMessage(player, packet.getChannelName(), packet.getData());
        } else if (event.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            WrapperConfigClientPluginMessage packet = new WrapperConfigClientPluginMessage(event);
            handlePluginMessage(player, packet.getChannelName(), packet.getData());
        }
    }

    private boolean handlePluginMessage(Player player, String channel, byte[] data) {
        boolean channelRegistered = false;

        // Handle channel registration/unregistration (for Fabric/Forge mods)
        if (channel.equals("minecraft:register") || channel.equals("minecraft:unregister")) {
            channelRegistered = handleChannelRegistration(player, channel, data);
        } else {
            // Direct channel usage - check if this is a new channel
            channelRegistered = plugin.module(SpoofModule.class).detection().addPlayerChannel(player, channel, true);
        }

        return channelRegistered;
    }

    private boolean handleChannelRegistration(Player player, String channel, byte[] data) {
        String payload = new String(data, StandardCharsets.UTF_8);
        String[] channels = payload.split("\0");
        boolean didRegister = false;

        for (String registeredChannel : channels) {
            if (channel.equals("minecraft:register")) {
                // Register the channel and trigger checks if needed
                if (plugin.module(SpoofModule.class).detection().addPlayerChannel(player, registeredChannel, true)) {
                    didRegister = true;
                }
            } else {
                // Unregister the channel
                plugin.module(SpoofModule.class).detection().removePlayerChannel(player, registeredChannel);
            }
        }

        return didRegister;
    }

    /**
     * Run the initial brand-only check
     * This doesn't check for required channels
     */
    private void scheduleInitialBrandCheck(Player player, long delayTicks) {
        com.veneer.core.FoliaScheduler.runAtEntityLater(plugin, player, () -> {
            if (player.isOnline()) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Running initial brand check for " + player.getName() +
                                         " (without required channels check)");
                }
                plugin.module(SpoofModule.class).detection().checkPlayerAsync(player, true, false);
            }
        }, delayTicks);
    }

    /**
     * Schedule the final check with required channels validation
     * This is run after a longer delay to ensure all channels are registered
     */
    private void scheduleRequiredChannelsCheck(Player player, long delayTicks) {
        com.veneer.core.FoliaScheduler.runAtEntityLater(plugin, player, () -> {
            if (player.isOnline()) {
                if (config.isDebugMode()) {
                    plugin.getLogger().info("[Debug] Running complete check with required channels for " + player.getName());
                }
                plugin.module(SpoofModule.class).detection().checkPlayerAsync(player, false, true);
            }
        }, delayTicks);
    }

    public void onPlayerJoin(Player player) {

        // Skip if player has bypass permission
        if (plugin.bypasses(player, "spoof", true)) return;

        // Make sure the profile exists (channels may have registered during the configuration phase)
        plugin.profile(player);

        // Special handling for no-brand detection
        if (config.isNoBrandCheckEnabled()) {
            com.veneer.core.FoliaScheduler.runAtEntityLater(plugin, player, () -> {
                // Only process if still null after 1 second (allows time for brand packet)
                if (player.isOnline() && plugin.module(SpoofModule.class).getClientBrand(player) == null) {
                    if (config.isDebugMode()) {
                        plugin.getLogger().info("[Debug] Processing no-brand alert for " + player.getName());
                    }

                    // Process on main thread
                    com.veneer.core.FoliaScheduler.runAtEntity(plugin, player, () -> {
                        plugin.module(SpoofModule.class).detection().processViolation(player, "NO_BRAND", plugin.messages().getRaw("spoof-reason-no-brand"));
                    });
                }
            }, 20L); // 1 second delay to allow brand packet to arrive
        }

        // Schedule the checks with appropriate delays
        int standardDelay = config.getCheckDelay();

        // First, do a "brand-only" check after the standard delay
        // This will only check for problematic brands but NOT required channels
        if (standardDelay >= 0) {
            scheduleInitialBrandCheck(player, standardDelay * 20L);
        }

        // Then, do a complete check with required channels, but with a longer delay
        // This gives the client more time to register all its channels
        scheduleRequiredChannelsCheck(player, REQUIRED_CHANNEL_CHECK_DELAY);
    }
}
