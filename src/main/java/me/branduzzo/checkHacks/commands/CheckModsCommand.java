package me.branduzzo.checkHacks.commands;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import me.branduzzo.checkHacks.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** /checkmods - instant mod list check based on the plugin channels. */
public class CheckModsCommand implements CommandExecutor, TabCompleter {

    private final CheckHacksPlugin plugin;

    public CheckModsCommand(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("checkhacks.checkmods")) {
            sender.sendMessage(MessageUtil.parse(plugin, "no-permission", Map.of()));
            return true;
        }
        if (!plugin.getConfigManager().isModListCheckEnabled()) {
            sender.sendMessage(MessageUtil.parse(plugin, "client-check-disabled", Map.of("check", "modlist")));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(MessageUtil.parseRaw(plugin,
                    plugin.getConfigManager().getPrefix() + "<red>Usage: /checkmods <player>", Map.of()));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(MessageUtil.parse(plugin, "player-not-found", Map.of("player", args[0])));
            return true;
        }
        if (plugin.getCheckManager().isChecking(target.getUniqueId())
                || plugin.getCheckRunner().isRunning(target.getUniqueId())) {
            sender.sendMessage(MessageUtil.parse(plugin, "already-checking", Map.of("player", target.getName())));
            return true;
        }

        Player initiator = sender instanceof Player p ? p : null;
        String reason = initiator != null ? "Mod list check by " + initiator.getName() : "Console mod list check";
        FoliaScheduler.runAtEntity(plugin, target,
                () -> plugin.getClientCheckManager().runModListCheck(target, initiator, reason));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("checkhacks.checkmods")) return List.of();
        if (args.length == 1)
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        return List.of();
    }
}
