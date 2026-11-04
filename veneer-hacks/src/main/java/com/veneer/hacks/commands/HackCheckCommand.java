package com.veneer.hacks.commands;

import com.veneer.hacks.HacksModule;
import com.veneer.VeneerPlugin;
import com.veneer.hacks.HackDefinition;
import com.veneer.core.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class HackCheckCommand implements CommandExecutor, TabCompleter {

    private final VeneerPlugin plugin;

    public HackCheckCommand(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("veneer.hacks.check")) {
            sender.sendMessage(plugin.messages().in(sender).get("no-permission", Map.of()));
            return true;
        }
        if (args.length < 1) {
            plugin.messages().send(sender, "hackcheck-usage");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(plugin.messages().in(sender).get("player-not-found",
                    Map.of("player", args[0])));
            return true;
        }
        if (plugin.module(HacksModule.class).checks().isChecking(target.getUniqueId())) {
            sender.sendMessage(plugin.messages().in(sender).get("already-checking",
                    Map.of("player", target.getName())));
            return true;
        }

        List<HackDefinition> hacks;
        if (args.length >= 2) {
            hacks = new ArrayList<>();
            for (String id : args[1].split(",")) {
                HackDefinition h = plugin.module(HacksModule.class).config().getHack(id.trim().toLowerCase(Locale.ROOT));
                if (h == null) {
                    sender.sendMessage(plugin.messages().in(sender).get("invalid-hack",
                            Map.of("hack", id.trim())));
                    return true;
                }
                hacks.add(h);
            }
        } else {
            hacks = plugin.module(HacksModule.class).config().getDefaultCheckHacks();
        }

        if (hacks.isEmpty()) {
            plugin.messages().send(sender, "hackcheck-no-valid");
            return true;
        }

        Player initiator = (sender instanceof Player p) ? p : null;
        String reason = initiator != null
                ? "Manual check by " + initiator.getName() : "Console check";
        FoliaScheduler.runAtEntity(plugin, target,
                () -> plugin.module(HacksModule.class).checks().startCheck(target, initiator, hacks, false, reason));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("veneer.hacks.check")) return List.of();
        if (args.length == 1)
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        if (args.length == 2) {
            String typed = args[1];
            String prefix  = typed.contains(",") ? typed.substring(0, typed.lastIndexOf(',') + 1) : "";
            String current = typed.contains(",") ? typed.substring(typed.lastIndexOf(',') + 1) : typed;
            return plugin.module(HacksModule.class).config().getHacks().keySet().stream()
                    .filter(id -> id.startsWith(current.toLowerCase(Locale.ROOT)))
                    .map(id -> prefix + id)
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
