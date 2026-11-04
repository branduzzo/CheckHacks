package com.veneer.rptrack;

import com.veneer.VeneerPlugin;
import com.veneer.core.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.List;

/** /rptrack <player> - shows the stored cache fingerprint and accounts sharing it. */
public class RPTrackCommand implements CommandExecutor {

    private final VeneerPlugin plugin;

    public RPTrackCommand(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        RPTrackManager fp = plugin.module(RPTrackManager.class);
        if (fp == null || !fp.isEnabled()) {
            plugin.messages().send(sender, "rptrack-disabled");
            return true;
        }
        if (args.length < 1) {
            plugin.messages().send(sender, "rptrack-usage", "label", label);
            return true;
        }
        Player online = Bukkit.getPlayerExact(args[0]);
        FoliaScheduler.runAsync(plugin, () -> {
            // An offline player's uuid from the database: Bukkit.getOfflinePlayer(name) may ask Mojang and block
            String uuid = online != null ? online.getUniqueId().toString()
                    : (String) plugin.database().players().getPlayerRecord(args[0]).getOrDefault("uuid", "");
            long mask = fp.getMask(uuid);
            if (mask == 0) {
                plugin.messages().send(sender, "rptrack-none", "player", args[0]);
                return;
            }
            List<String> linked = fp.findLinked(uuid, mask);
            plugin.messages().send(sender, "rptrack-cache", "player", args[0],
                    "mask", Long.toBinaryString(mask), "packs", Long.bitCount(mask));
            plugin.messages().send(sender, "rptrack-linked",
                    "linked", linked.isEmpty() ? plugin.messages().in(sender).plain("veneer-rp-linked-none") : String.join(", ", linked));
        });
        return true;
    }
}
