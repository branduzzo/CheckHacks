package me.branduzzo.checkHacks.commands;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.HackDefinition;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CheckHacksCommand implements CommandExecutor, TabCompleter {

    private final CheckHacksPlugin plugin;

    public CheckHacksCommand(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("checkhacks.check")) {
            sender.sendMessage(plugin.getMessageManager().get("no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(plugin.getMessageManager().deserialize(
                    plugin.getConfigManager().getPrefix()
                            + "<red>Usage: /checkhacks <player> [hack1,hack2,...]"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(plugin.getMessageManager().get("player-not-found",
                    Map.of("player", args[0])));
            return true;
        }
        if (plugin.hasActiveSignSession(target.getUniqueId())) {
            sender.sendMessage(plugin.getMessageManager().get("already-checking",
                    Map.of("player", target.getName())));
            return true;
        }

        List<HackDefinition> hacks;
        if (args.length >= 2) {
            hacks = new ArrayList<>();
            for (String id : args[1].split(",")) {
                HackDefinition h = plugin.getConfigManager().getHack(id.trim().toLowerCase());
                if (h == null) {
                    sender.sendMessage(plugin.getMessageManager().get("invalid-hack",
                            Map.of("hack", id.trim())));
                    return true;
                }
                hacks.add(h);
            }
        } else {
            hacks = plugin.getConfigManager().getDefaultCheckHacks();
        }

        if (hacks.isEmpty()) {
            sender.sendMessage(plugin.getMessageManager().deserialize(
                    plugin.getConfigManager().getPrefix() + "<red>No valid hacks to check."));
            return true;
        }

        Player initiator = (sender instanceof Player p) ? p : null;
        String reason = initiator != null
                ? "Manual check by " + initiator.getName() : "Console check";
        FoliaScheduler.runAtEntity(plugin, target,
                () -> plugin.getCheckManager().startCheck(target, initiator, hacks, false, reason));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("checkhacks.check")) return List.of();
        if (args.length == 1) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String typed = args[1];
            String prefix = typed.contains(",") ? typed.substring(0, typed.lastIndexOf(',') + 1) : "";
            String current = typed.contains(",") ? typed.substring(typed.lastIndexOf(',') + 1) : typed;
            return plugin.getConfigManager().getHacks().keySet().stream()
                    .filter(id -> id.startsWith(current.toLowerCase()))
                    .map(id -> prefix + id)
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
