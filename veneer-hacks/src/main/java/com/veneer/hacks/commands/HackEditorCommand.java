package com.veneer.hacks.commands;

import com.veneer.hacks.HacksModule;
import com.veneer.VeneerPlugin;
import com.veneer.core.FoliaScheduler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public class HackEditorCommand implements CommandExecutor {

    /** Panel actions beyond viewing the scan history, each {@code veneer.web.<name>}. */
    private static final List<String> WEB_PERMS = List.of("scan", "delete", "links", "ips");

    private final VeneerPlugin plugin;

    public HackEditorCommand(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "editor-players-only");
            return true;
        }
        if (!player.hasPermission("veneer.hacks.editor")) {
            player.sendMessage(plugin.messages().in(player).get("no-permission"));
            return true;
        }
        if (!plugin.module(HacksModule.class).config().isWebEditorEnabled()) {
            player.sendMessage(plugin.messages().in(player).get("editor-disabled"));
            return true;
        }

        String uuid = player.getUniqueId().toString(), name = player.getName();
        // The token carries the rights the player has now; changing them later needs a new /hackeditor
        String perms = String.join(",", WEB_PERMS.stream().filter(p -> player.hasPermission("veneer.web." + p)).toList());
        int expire = plugin.module(HacksModule.class).config().getTokenExpireMinutes();
        String host = plugin.module(HacksModule.class).config().getWebHost();
        int    port = plugin.module(HacksModule.class).config().getWebPort();
        // The insert waits for the database connection, which the web panel may be holding: not on the main thread
        FoliaScheduler.runAsync(plugin, () -> {
            String token = plugin.database().tokens().saveToken(uuid, name, expire, perms);
            String url  = "http://" + host + ":" + port + "/editor?token=" + token;
            FoliaScheduler.runAtEntity(plugin, player,
                    () -> player.sendMessage(plugin.messages().in(player).get("editor-url", Map.of("url", url))));
        });
        return true;
    }
}
