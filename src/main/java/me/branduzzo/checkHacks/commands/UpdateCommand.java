package me.branduzzo.checkHacks.commands;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Map;

public class UpdateCommand implements CommandExecutor {

    private final CheckHacksPlugin plugin;

    public UpdateCommand(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("checkhacks.update")) {
            sender.sendMessage(plugin.getMessageManager().get("no-permission", Map.of()));
            return true;
        }
        if (plugin.getUpdateManager() == null) {
            sender.sendMessage(plugin.getMessageManager().get("update-failed", Map.of("error", "Update manager not initialized")));
            return true;
        }
        if (!plugin.getUpdateManager().isUpdateAvailable()) {
            sender.sendMessage(plugin.getMessageManager().get("update-no-update", Map.of()));
            return true;
        }
        plugin.getUpdateManager().downloadAndUpdate(sender);
        return true;
    }
}
