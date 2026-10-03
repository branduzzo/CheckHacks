package me.branduzzo.checkHacks.listeners;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.ClientProfile;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerUnregisterChannelEvent;

/**
 * Keeps the client profile of every online player up to date: the brand (on
 * Paper) and the full list of plugin channels, which is what the mod list
 * check works with.
 */
public class ClientProfileListener implements Listener {

    private final CheckHacksPlugin plugin;

    public ClientProfileListener(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // The brand and the first channels arrive a couple of ticks after the
        // join packet, so read them twice to be safe.
        for (long delay : new long[]{5L, 40L}) {
            FoliaScheduler.runAtEntityLater(plugin, player, () -> {
                if (!player.isOnline()) return;
                ClientProfile profile = plugin.getClientCheckManager().refreshProfile(player);
                if (delay >= 40L)
                    plugin.getLogger().info("[CheckHacks] " + player.getName()
                            + " client: brand=" + (profile.hasBrand() ? profile.getBrand() : "unknown")
                            + " type=" + profile.getClientType()
                            + " channels=" + profile.getChannels().size());
            }, delay);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        ClientProfile profile = plugin.getClientDataManager().getOrCreate(event.getPlayer());
        profile.addChannel(event.getChannel());
        profile.setClientType(plugin.getClientCheckManager()
                .inferType(profile.getBrand(), profile.getChannels()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnregisterChannel(PlayerUnregisterChannelEvent event) {
        ClientProfile profile = plugin.getClientDataManager().get(event.getPlayer().getUniqueId());
        if (profile == null) return;
        profile.removeChannel(event.getChannel());
        profile.setClientType(plugin.getClientCheckManager()
                .inferType(profile.getBrand(), profile.getChannels()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getClientDataManager().remove(event.getPlayer().getUniqueId());
    }
}
