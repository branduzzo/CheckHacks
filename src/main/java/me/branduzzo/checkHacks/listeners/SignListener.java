package me.branduzzo.checkHacks.listeners;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

import java.util.UUID;

public class SignListener implements Listener {

    private final CheckHacksPlugin plugin;

    public SignListener(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        boolean hackChecking = plugin.getCheckManager().isChecking(uuid);
        boolean langChecking = plugin.getLangCheckManager().isChecking(uuid);
        if (!hackChecking && !langChecking) return;

        Location expected = hackChecking
                ? plugin.getCheckManager().getActiveSignLocation(uuid)
                : plugin.getLangCheckManager().getActiveSignLocation(uuid);
        if (expected != null && !sameBlock(expected, event.getBlock().getLocation())) {
            return;
        }

        event.setCancelled(true);

        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            net.kyori.adventure.text.Component c = event.line(i);
            lines[i] = c != null ? PlainTextComponentSerializer.plainText().serialize(c) : "";
        }

        if (hackChecking) plugin.getCheckManager().handleBatchResponse(player, lines);
        else plugin.getLangCheckManager().handleResponse(player, lines);
    }

    private static boolean sameBlock(Location a, Location b) {
        if (a.getWorld() == null || b.getWorld() == null) return false;
        if (!a.getWorld().equals(b.getWorld())) return false;
        return a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }
}
