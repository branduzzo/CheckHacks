package com.veneer.hacks.listeners;

import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.event.events.FlagEvent;
import com.veneer.VeneerPlugin;
import com.veneer.core.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Isolated so GrimAPI classes are only loaded when Grim is installed. */
final class GrimHook {

    private GrimHook() {}

    static void register(VeneerPlugin plugin, AntiCheatListener listener) {
        GrimAbstractAPI api = GrimAPIProvider.get();
        api.getEventBus().get(FlagEvent.class).onFlag(api.getGrimPlugin(plugin), (user, check, verbose, cancelled) -> {
            // Grim fires this on its own thread: touch Bukkit only on the global region thread
            UUID uuid = user.getUniqueId();
            FoliaScheduler.runGlobal(plugin, () -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) listener.queueCheck(player, "Grim");
            });
            return cancelled;
        });
    }
}
