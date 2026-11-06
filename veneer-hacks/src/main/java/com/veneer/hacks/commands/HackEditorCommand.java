package com.veneer.hacks.commands;

import com.veneer.VeneerPlugin;
import com.veneer.core.FoliaScheduler;
import com.veneer.hacks.HacksModule;
import com.veneer.hacks.managers.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code /hackeditor} opens the panel in a connected browser, {@code new} connects another browser with a one-time
 * link, {@code links} lists browsers and {@code unauth} disconnects them.
 */
public class HackEditorCommand implements CommandExecutor, TabCompleter {

    /** Panel actions beyond viewing the scan history, each {@code veneer.web.<name>}. */
    public static final List<String> WEB_PERMS = List.of("scan", "delete", "links", "ips");
    private static final String ADMIN = "veneer.hacks.editor.admin";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    private final VeneerPlugin plugin;

    public HackEditorCommand(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("veneer.hacks.editor")) {
            sender.sendMessage(plugin.messages().in(sender).get("no-permission"));
            return true;
        }
        ConfigManager config = plugin.module(HacksModule.class).config();
        if (!config.isWebEditorEnabled()) {
            sender.sendMessage(plugin.messages().in(sender).get("editor-disabled"));
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "" -> open(sender, config);
            case "new" -> connect(sender, config);
            case "links" -> links(sender, args);
            case "unauth" -> unauth(sender, args);
            case "block" -> block(sender, args);
            case "unblock" -> unblock(sender, args);
            default -> plugin.messages().send(sender, "editor-usage");
        }
        return true;
    }

    /** The rights the player has now, as stored with a session. */
    public static String webPerms(Player player) {
        return WEB_PERMS.stream().filter(p -> player.hasPermission("veneer.web." + p)).collect(Collectors.joining(","));
    }

    private void open(CommandSender sender, ConfigManager config) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "editor-players-only");
            return;
        }
        String uuid = player.getUniqueId().toString(), perms = webPerms(player), url = config.getPanelUrl() + "/";
        FoliaScheduler.runAsync(plugin, () -> {
            var tokens = plugin.database().tokens();
            tokens.updatePerms(uuid, perms);
            boolean connected = tokens.countSessions(uuid) > 0;
            FoliaScheduler.runAtEntity(plugin, player, () -> player.sendMessage(connected
                    ? plugin.messages().in(player).get("editor-link", Map.of("url", url))
                    : plugin.messages().in(player).get("editor-not-connected")));
        });
    }

    private void connect(CommandSender sender, ConfigManager config) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "editor-players-only");
            return;
        }
        String uuid = player.getUniqueId().toString(), name = player.getName(), perms = webPerms(player);
        int minutes = config.getLinkExpireMinutes();
        // The insert waits for the database connection, which the web panel may be holding: not on the main thread
        FoliaScheduler.runAsync(plugin, () -> {
            String secret = plugin.database().tokens().createLink(uuid, name, minutes, perms);
            if (secret == null) return;
            String url = config.getPanelUrl() + "/connect?code=" + secret;
            FoliaScheduler.runAtEntity(plugin, player, () -> {
                var m = plugin.messages().in(player);
                player.sendMessage(m.get("editor-new-warning"));
                player.sendMessage(m.get("editor-new-link", Map.of("url", url, "minutes", String.valueOf(minutes))));
            });
        });
    }

    private boolean mayManage(CommandSender sender, String target) {
        return sender.hasPermission(ADMIN) || (sender instanceof Player p && p.getName().equalsIgnoreCase(target))
                || !(sender instanceof Player);
    }

    private void links(CommandSender sender, String[] args) {
        String target = args.length > 1 ? args[1] : sender instanceof Player p ? p.getName() : null;
        if (target == null) {
            plugin.messages().send(sender, "editor-usage");
            return;
        }
        if (!mayManage(sender, target)) {
            sender.sendMessage(plugin.messages().in(sender).get("no-permission"));
            return;
        }
        boolean showIp = !(sender instanceof Player) || sender.hasPermission("veneer.web.ips");
        FoliaScheduler.runAsync(plugin, () -> {
            var rows = plugin.database().tokens().listSessions(null, target);
            var m = plugin.messages();
            if (rows.isEmpty()) {
                m.send(sender, "editor-links-empty", "player", target);
                return;
            }
            m.send(sender, "editor-links-header", "player", target, "count", rows.size());
            for (var row : rows) {
                m.send(sender, "editor-links-line", "id", row.get("id"),
                        "created", DATE.format(Instant.ofEpochMilli((Long) row.get("created"))),
                        "used", DATE.format(Instant.ofEpochMilli((Long) row.get("used"))),
                        "ip", showIp ? String.valueOf(row.get("ip")) : "-");
            }
        });
    }

    private void unauth(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "editor-usage");
            return;
        }
        String target = args[1], id = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : null;
        if (!mayManage(sender, target)) {
            sender.sendMessage(plugin.messages().in(sender).get("no-permission"));
            return;
        }
        FoliaScheduler.runAsync(plugin, () -> {
            int removed = plugin.database().tokens().revokeByName(target, id);
            plugin.getLogger().info(sender.getName() + " disconnected " + removed + " web panel browser(s) of " + target);
            plugin.messages().send(sender, removed > 0 ? "editor-unauth-done" : "editor-links-empty",
                    "player", target, "count", removed);
        });
    }

    /**
     * Disconnects one browser and bans its address from the panel and the server. Only the address the browser
     * really used can be banned, so this right cannot be turned against any address someone types.
     */
    private void block(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(sender, "editor-usage");
            return;
        }
        String target = args[1], id = args[2].toLowerCase(Locale.ROOT);
        boolean confirmed = args.length > 3 && args[3].equalsIgnoreCase("confirm");
        if (!mayManage(sender, target)) {
            sender.sendMessage(plugin.messages().in(sender).get("no-permission"));
            return;
        }
        var address = sender instanceof Player p && p.getAddress() != null ? p.getAddress().getAddress() : null;
        String own = address != null ? address.getHostAddress() : null;
        FoliaScheduler.runAsync(plugin, () -> {
            var tokens = plugin.database().tokens();
            var m = plugin.messages();
            String ip = tokens.sessionIp(target, id);
            if (ip == null) {
                m.send(sender, "editor-links-empty", "player", target);
                return;
            }
            // A proxy or this machine shows as a local address: banning it would lock out everyone behind it
            if (isLocal(ip) || ip.equals(own)) {
                tokens.revokeByName(target, id);
                m.send(sender, "editor-block-refused", "player", target, "ip", ip);
                return;
            }
            if (!confirmed) {
                m.send(sender, "editor-block-confirm", "player", target, "id", id, "ip", ip);
                return;
            }
            tokens.revokeByName(target, id);
            tokens.blockIp(ip);
            plugin.getLogger().warning(sender.getName() + " blocked " + ip + " from the web panel and the server (browser of " + target + ")");
            // Through the command, so a ban plugin that replaces it keeps its own records
            FoliaScheduler.runGlobal(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "ban-ip " + ip + " Unauthorized web panel access"));
            m.send(sender, "editor-block-done", "player", target, "ip", ip);
        });
    }

    private void unblock(CommandSender sender, String[] args) {
        if (sender instanceof Player && !sender.hasPermission(ADMIN)) {
            sender.sendMessage(plugin.messages().in(sender).get("no-permission"));
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "editor-usage");
            return;
        }
        String ip = args[1];
        FoliaScheduler.runAsync(plugin, () -> {
            boolean was = plugin.database().tokens().unblockIp(ip);
            if (!was) {
                plugin.messages().send(sender, "editor-unblock-none", "ip", ip);
                return;
            }
            plugin.getLogger().info(sender.getName() + " unblocked " + ip);
            FoliaScheduler.runGlobal(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon-ip " + ip));
            plugin.messages().send(sender, "editor-unblock-done", "ip", ip);
        });
    }

    /** By the literal address only: no name lookups. */
    private static boolean isLocal(String ip) {
        return ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1") || ip.startsWith("127.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("veneer.hacks.editor")) return List.of();
        if (args.length == 1)
            return List.of("new", "links", "unauth", "block", "unblock").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && !args[0].equalsIgnoreCase("new"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
