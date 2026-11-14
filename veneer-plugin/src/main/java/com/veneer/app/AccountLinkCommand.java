package com.veneer.app;

import com.veneer.VeneerPlugin;
import com.veneer.core.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * {@code /accountlink <player1> <player2>} marks two accounts as one person by hand; {@code unlink} removes it.
 * The link shows in the web panel's graph and alts lists like an automatic one, at full strength.
 */
public class AccountLinkCommand implements CommandExecutor, TabCompleter {

    private final VeneerPlugin plugin;

    public AccountLinkCommand(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        boolean unlink = args.length > 0 && args[0].equalsIgnoreCase("unlink");
        String first = unlink ? (args.length > 1 ? args[1] : null) : (args.length > 0 ? args[0] : null);
        String second = unlink ? (args.length > 2 ? args[2] : null) : (args.length > 1 ? args[1] : null);
        if (first == null || second == null || (unlink && args.length != 3) || (!unlink && args.length != 2)) {
            plugin.messages().send(sender, "accountlink-usage", "label", label);
            return true;
        }
        if (first.equalsIgnoreCase(second)) {
            plugin.messages().send(sender, "accountlink-self");
            return true;
        }
        final String nameA = first, nameB = second;
        FoliaScheduler.runAsync(plugin, () -> {
            String a = uuid(nameA), b = uuid(nameB);
            if (a.isEmpty() || b.isEmpty()) {
                plugin.messages().send(sender, "player-not-found", "player", a.isEmpty() ? nameA : nameB);
                return;
            }
            if (unlink) plugin.database().manualLinks().unlink(a, b);
            else plugin.database().manualLinks().link(a, b);
            plugin.messages().send(sender, unlink ? "accountlink-unlinked" : "accountlink-linked",
                    "first", nameA, "second", nameB);
        });
        return true;
    }

    /** An offline player's uuid from the database: Bukkit.getOfflinePlayer(name) may ask Mojang and block. */
    private String uuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online.getUniqueId().toString()
                : (String) plugin.database().players().getPlayerRecord(name).getOrDefault("uuid", "");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1)
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("unlink"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("unlink"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        return List.of();
    }
}
