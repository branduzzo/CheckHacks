package me.branduzzo.checkHacks.commands;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.CheckType;
import me.branduzzo.checkHacks.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /checkclient - one entry point for every check type.
 * <pre>
 *   /checkclient list
 *   /checkclient info &lt;player&gt;
 *   /checkclient &lt;player&gt; [brand,modlist,hack,lang]
 * </pre>
 */
public class CheckClientCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("list", "info");

    private final CheckHacksPlugin plugin;

    public CheckClientCommand(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("checkhacks.checkclient")) {
            sender.sendMessage(MessageUtil.parse(plugin, "no-permission", Map.of()));
            return true;
        }
        if (args.length == 0) { usage(sender); return true; }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("list")) {
            sendList(sender);
            return true;
        }

        if (sub.equals("info")) {
            if (args.length < 2) { usage(sender); return true; }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(MessageUtil.parse(plugin, "player-not-found", Map.of("player", args[1])));
                return true;
            }
            plugin.getClientCheckManager().sendProfile(sender, target);
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(MessageUtil.parse(plugin, "player-not-found", Map.of("player", args[0])));
            return true;
        }

        List<CheckType> types;
        if (args.length >= 2) {
            types = new ArrayList<>();
            for (String raw : args[1].split(",")) {
                CheckType type = CheckType.byId(raw);
                if (type == null) {
                    sender.sendMessage(MessageUtil.parse(plugin, "invalid-check-type",
                            Map.of("type", raw.trim(), "types", CheckType.idList())));
                    return true;
                }
                if (!types.contains(type)) types.add(type);
            }
        } else {
            types = plugin.getConfigManager().getClientDefaultChecks();
        }

        List<CheckType> allowed = new ArrayList<>();
        for (CheckType type : types) {
            if (sender.hasPermission(type.getPermission())) allowed.add(type);
            else sender.sendMessage(MessageUtil.parse(plugin, "no-permission", Map.of()));
        }
        if (allowed.isEmpty()) return true;

        Player initiator = sender instanceof Player p ? p : null;
        String reason = initiator != null
                ? "Client check by " + initiator.getName() : "Console client check";

        plugin.getCheckRunner().start(target, initiator, allowed, reason, true);
        return true;
    }

    private void sendList(CommandSender sender) {
        sender.sendMessage(plugin.getMessageManager().get("client-check-list-header"));
        for (CheckType type : CheckType.values()) {
            sender.sendMessage(plugin.getMessageManager().get("client-check-list-entry", Map.of(
                    "id", type.getId(),
                    "name", type.getDisplayName(),
                    "description", type.getDescription(),
                    "status", plugin.getMessageManager().getRaw(
                            isEnabled(type) ? "status-enabled" : "status-disabled"))));
        }
        sender.sendMessage(plugin.getMessageManager().get("client-check-list-hint",
                Map.of("types", CheckType.idList())));
    }

    private boolean isEnabled(CheckType type) {
        return switch (type) {
            case BRAND   -> plugin.getConfigManager().isBrandCheckEnabled();
            case MODLIST -> plugin.getConfigManager().isModListCheckEnabled();
            case LANG    -> !plugin.getConfigManager().getLanguages().isEmpty();
            case HACK    -> true;
        };
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(MessageUtil.parseRaw(plugin, plugin.getConfigManager().getPrefix()
                + "<red>Usage: <white>/checkclient <player> [types] <gray>| <white>/checkclient list <gray>| "
                + "<white>/checkclient info <player>", Map.of()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("checkhacks.checkclient")) return List.of();

        if (args.length == 1) {
            List<String> out = new ArrayList<>(SUBCOMMANDS);
            for (Player p : Bukkit.getOnlinePlayers())
                if (p.getName().toLowerCase().startsWith(args[0].toLowerCase())) out.add(p.getName());
            return out;
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("info"))
                return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());

            String typed   = args[1];
            String prefix  = typed.contains(",") ? typed.substring(0, typed.lastIndexOf(',') + 1) : "";
            String current = typed.contains(",") ? typed.substring(typed.lastIndexOf(',') + 1) : typed;
            return CheckType.ids().stream()
                    .filter(id -> id.startsWith(current.toLowerCase()))
                    .map(id -> prefix + id)
                    .collect(Collectors.toList());
        }

        return List.of();
    }
}
