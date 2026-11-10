package com.veneer.hacks.commands;

import com.veneer.hacks.HacksModule;
import com.veneer.VeneerPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class HackLangCommand implements CommandExecutor, TabCompleter {

    private final VeneerPlugin plugin;

    public HackLangCommand(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("veneer.hacks.lang")) {
            sender.sendMessage(plugin.messages().in(sender).get("no-permission", Map.of()));
            return true;
        }
        if (args.length < 1) {
            plugin.messages().send(sender, "hacklang-usage");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(plugin.messages().in(sender).get("player-not-found",
                    Map.of("player", args[0])));
            return true;
        }
        if (plugin.module(HacksModule.class).langChecks().isChecking(target.getUniqueId())) {
            sender.sendMessage(plugin.messages().in(sender).get("already-checking",
                    Map.of("player", target.getName())));
            return true;
        }

        Map<String, String> allLangs = plugin.module(HacksModule.class).config().getLanguages();
        Map<String, String> langs;

        if (args.length >= 2) {
            langs = new LinkedHashMap<>();
            for (String code : args[1].split(",")) {
                String trimmed = code.trim().toLowerCase(Locale.ROOT);
                if (!allLangs.containsKey(trimmed)) {
                    plugin.messages().send(sender, "hacklang-unknown", "lang", trimmed);
                    return true;
                }
                langs.put(trimmed, allLangs.get(trimmed));
            }
        } else {
            langs = allLangs;
        }

        Player initiator = sender instanceof Player p ? p : null;
        // On the target's thread (Folia): the check places a sign next to that player
        Map<String, String> asked = langs;
        com.veneer.core.FoliaScheduler.runAtEntity(plugin, target, () -> {
            if (target.isOnline()) plugin.module(HacksModule.class).langChecks().startCheck(target, initiator, asked);
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("veneer.hacks.lang")) return List.of();
        if (args.length == 1)
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        if (args.length == 2) {
            String typed   = args[1];
            String prefix  = typed.contains(",") ? typed.substring(0, typed.lastIndexOf(',') + 1) : "";
            String current = typed.contains(",") ? typed.substring(typed.lastIndexOf(',') + 1) : typed;
            return plugin.module(HacksModule.class).config().getLanguages().keySet().stream()
                    .filter(id -> id.startsWith(current.toLowerCase(Locale.ROOT)))
                    .map(id -> prefix + id)
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
