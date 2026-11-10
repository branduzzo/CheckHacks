package com.veneer.core;

import com.veneer.VeneerPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;

/**
 * The only join/quit listener in Veneer. It hands each event to every running module in order
 * ({@link VeneerModule#playerJoin}, {@link VeneerModule#playerQuit}), then to the update notice and the
 * profile cleanup, so modules never register their own join/quit handlers.
 */
public final class PlayerEvents implements Listener {

    private final VeneerPlugin plugin;

    public PlayerEvents(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.profile(event.getPlayer());
        var address = event.getPlayer().getAddress();
        if (address != null && address.getAddress() != null) {
            var uuid = event.getPlayer().getUniqueId().toString();
            var name = event.getPlayer().getName();
            var ip = address.getAddress().getHostAddress();
            var skin = event.getPlayer().getPlayerProfile().getTextures().getSkin();
            var skinUrl = skin != null ? skin.toString() : null;
            FoliaScheduler.runAsync(plugin, () -> {
                plugin.database().players().recordJoin(uuid, name, ip);
                if (skinUrl != null && !plugin.database().players().hasFreshSkin(uuid, 7 * 86_400_000L)) {
                    String skinPng = Heads.fetch(skinUrl);
                    if (skinPng != null) plugin.database().players().saveSkin(uuid, skinPng);
                }
            });
        }
        for (VeneerModule module : plugin.modules()) {
            if (!module.running()) continue;
            try {
                module.playerJoin(event.getPlayer());
            } catch (Throwable t) {
                plugin.getLogger().warning("Module '" + module.id() + "' failed on join of " + event.getPlayer().getName() + ": " + t);
            }
        }
        plugin.updates().onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        var uuid = event.getPlayer().getUniqueId();
        // By quitting the client has sent its brand and every channel it registers (the spoof module collects them)
        var brand = plugin.brandOf(event.getPlayer());
        var profile = plugin.profileOf(uuid);
        if (brand != null && profile != null) {
            var name = event.getPlayer().getName();
            var channels = List.copyOf(profile.getChannels());
            FoliaScheduler.runAsync(plugin, () -> plugin.database().players().recordClient(uuid.toString(), name, brand, channels));
        }
        for (VeneerModule module : plugin.modules()) {
            if (!module.running()) continue;
            try {
                module.playerQuit(uuid);
            } catch (Throwable t) {
                plugin.getLogger().warning("Module '" + module.id() + "' failed on quit: " + t);
            }
        }
        plugin.dropProfile(uuid);
    }
}
