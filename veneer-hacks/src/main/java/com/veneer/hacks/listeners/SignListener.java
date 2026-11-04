package com.veneer.hacks.listeners;

import com.veneer.hacks.HacksModule;
import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import com.veneer.VeneerPlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

import java.util.UUID;

public class SignListener implements Listener {

    private final VeneerPlugin plugin;

    public SignListener(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    /** Probe signs stand in the air with nothing under them: an update from below would break them into an item. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDestroy(BlockDestroyEvent event) {
        var at = event.getBlock().getLocation();
        if (plugin.module(HacksModule.class).checks().isProbeSign(at) || plugin.module(HacksModule.class).langChecks().isProbeSign(at))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();

        // Only the player's current probe sign counts. Any other sign (one they really edit, or an old probe the
        // answer arrived too late for) is left alone, so it can neither be swallowed nor scored as a scan answer.
        var at = event.getBlock().getLocation();
        boolean hackChecking = plugin.module(HacksModule.class).checks().ownsSign(uuid, at);
        boolean langChecking = !hackChecking && plugin.module(HacksModule.class).langChecks().ownsSign(uuid, at);
        if (!hackChecking && !langChecking) return;

        event.setCancelled(true);

        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            net.kyori.adventure.text.Component c = event.line(i);
            lines[i] = c != null ? PlainTextComponentSerializer.plainText().serialize(c) : "";
        }

        if (hackChecking) plugin.module(HacksModule.class).checks().handleBatchResponse(player, lines);
        else              plugin.module(HacksModule.class).langChecks().handleResponse(player, lines);
    }
}
