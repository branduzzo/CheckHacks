package me.branduzzo.checkHacks.commands;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CheckLangCommand implements CommandExecutor, TabCompleter {

    private final CheckHacksPlugin plugin;

    public CheckLangCommand(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("checkhacks.checklang")) {
            sender.sendMessage(plugin.getMessageManager().get("no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(plugin.getMessageManager().deserialize(
                    plugin.getConfigManager().getPrefix()
                            + "<red>Usage: /checklang <player> [lang1,lang2,...]"));
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

        Map<String, String> allLangs = plugin.getConfigManager().getLanguages();
        Map<String, String> langs;

        if (args.length >= 2) {
            langs = new LinkedHashMap<>();
            for (String code : args[1].split(",")) {
                String trimmed = code.trim().toLowerCase();
                if (!allLangs.containsKey(trimmed)) {
                    sender.sendMessage(plugin.getMessageManager().deserialize(
                            plugin.getConfigManager().getPrefix()
                                    + "<red>Unknown language: <white>" + trimmed));
                    return true;
                }
                langs.put(trimmed, allLangs.get(trimmed));
            }
        } else {
            langs = allLangs;
        }

        Player initiator = sender instanceof Player p ? p : null;
        plugin.getLangCheckManager().startCheck(target, initiator, langs);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("checkhacks.checklang")) return List.of();
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
            return plugin.getConfigManager().getLanguages().keySet().stream()
                    .filter(id -> id.startsWith(current.toLowerCase()))
                    .map(id -> prefix + id)
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
