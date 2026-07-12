package me.branduzzo.checkHacks.commands;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class AlertsCommand implements CommandExecutor {

    private final CheckHacksPlugin plugin;

    public AlertsCommand(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessageManager().deserialize(
                    plugin.getConfigManager().getPrefix() + "<red>Only players can use this command."));
            return true;
        }
        if (!player.hasPermission("checkhacks.alerts")) {
            player.sendMessage(plugin.getMessageManager().get("no-permission"));
            return true;
        }
        plugin.toggleAlerts(player.getUniqueId());
        String key = plugin.hasAlertsEnabled(player.getUniqueId()) ? "alerts-enabled" : "alerts-disabled";
        player.sendMessage(plugin.getMessageManager().get(key));
        return true;
    }
}
