package me.branduzzo.checkHacks.listeners;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
        UUID   uuid   = player.getUniqueId();

        // Ordinary sign edits must pass through untouched, even during a scan.
        // Both the editor phase and the exact front-side block must match.
        if (event.getSide() != org.bukkit.block.sign.Side.FRONT) return;
        boolean hackChecking = plugin.getCheckManager().isExpectedSign(uuid, event.getBlock().getLocation());
        boolean langChecking = plugin.getLangCheckManager().isExpectedSign(uuid, event.getBlock().getLocation());
        if (!hackChecking && !langChecking) return;

        event.setCancelled(true);

        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            net.kyori.adventure.text.Component c = event.line(i);
            lines[i] = c != null ? PlainTextComponentSerializer.plainText().serialize(c) : "";
        }

        if (hackChecking) plugin.getCheckManager().handleBatchResponse(player, lines);
        else              plugin.getLangCheckManager().handleResponse(player, lines);
    }
}
