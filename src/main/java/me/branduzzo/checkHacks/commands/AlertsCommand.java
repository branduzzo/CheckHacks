package me.branduzzo.checkHacks.commands;

import me.branduzzo.checkHacks.AlertMode;
import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public class AlertsCommand implements CommandExecutor {

    private final CheckHacksPlugin plugin;

    public AlertsCommand(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageUtil.parseRaw(plugin,
                    plugin.getConfigManager().getPrefix() + "<red>Only players can use this command.", Map.of()));
            return true;
        }
        if (!player.hasPermission("checkhacks.alerts")) {
            player.sendMessage(MessageUtil.parse(plugin, "no-permission", Map.of()));
            return true;
        }

        AlertMode current = plugin.getAlertMode(player.getUniqueId());
        AlertMode next;
        if (args.length == 0) {
            next = current.next();
        } else {
            next = AlertMode.parse(args[0], null);
            if (next == null) {
                player.sendMessage(MessageUtil.parse(plugin, "alerts-usage", Map.of()));
                return true;
            }
        }
        plugin.setAlertMode(player.getUniqueId(), next);

        String key = switch (next) {
            case ALL -> "alerts-mode-all";
            case ONLY_DETECTED -> "alerts-mode-only-detected";
            case OFF -> "alerts-mode-off";
        };
        player.sendMessage(MessageUtil.parse(plugin, key, Map.of()));
        return true;
    }
}
