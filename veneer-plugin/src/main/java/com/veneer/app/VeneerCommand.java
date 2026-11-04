package com.veneer.app;

import com.veneer.VeneerPlugin;
import com.veneer.core.*;
import com.veneer.hacks.HackResult;
import com.veneer.rptrack.RPTrackManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code /veneer}: the one command that spans modules. {@code info} puts the client data, the latest
 * mod scan and the RPTrack link of a player on one screen; {@code reload} reloads everything; {@code lang}
 * picks the language a player reads Veneer's messages in. The module commands also run as
 * {@code /veneer <command> ...} ({@link #PASSTHROUGH}), and {@code help} lists what the sender may use.
 */
public final class VeneerCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "help", "info", "player", "find", "check", "brands", "channels", "alerts", "lang", "reload", "version");
    /** {@code /veneer <name> ...} runs the plugin.yml command it maps to, with its own permission check. */
    private static final Map<String, String> PASSTHROUGH = Map.of(
            "spoof", "spoof", "hackcheck", "hackcheck", "scan", "hackcheck", "hacklang", "hacklang",
            "hackeditor", "hackeditor", "editor", "hackeditor", "rptrack", "rptrack");

    /**
     * One line of {@code /veneer help}: shown when the module runs (null: always) and the sender has the
     * permission (null: everyone). {@code {arg}} in the usage is replaced by {@code veneer-help-arg-<arg>}, the description is {@code veneer-cmd-<key>}.
     */
    private record HelpEntry(String module, String usage, String key, String permission) {}

    private static final List<HelpEntry> HELP = List.of(
            new HelpEntry(null,      "/veneer help",                             "help",                  null),
            new HelpEntry(null,      "/veneer lang [{language}|auto|reset]",     "lang",                  "veneer.lang"),
            new HelpEntry(null,      "/veneer player ({player})",                "player",                "veneer.admin"),
            new HelpEntry(null,      "/veneer find <{player}>",                  "find",                  "veneer.admin"),
            new HelpEntry(null,      "/veneer check <{player}>",                 "check",                 "veneer.admin"),
            new HelpEntry("spoof",   "/veneer brands",                           "brands",                "veneer.admin"),
            new HelpEntry(null,      "/veneer channels",                         "channels",              "veneer.admin"),
            new HelpEntry(null,      "/veneer alerts [{player}|toggle]",         "alerts",                Alerts.PERMISSION),
            new HelpEntry(null,      "/veneer info <{player}>",                  "info",                  "veneer.admin"),
            new HelpEntry(null,      "/veneer reload",                           "reload",                "veneer.admin"),
            new HelpEntry(null,      "/veneer version",                          "version",               "veneer.admin"),
            new HelpEntry("spoof",   "/spoof brand <{player}>",                  "spoof-brand",           "veneer.spoof.use"),
            new HelpEntry("spoof",   "/spoof channels <{player}>",               "spoof-channels",        "veneer.spoof.use"),
            new HelpEntry("spoof",   "/spoof runcheck [{player}|*]",             "spoof-runcheck",        "veneer.spoof.use"),
            new HelpEntry("spoof",   "/spoof check [{player}|*]",                "spoof-check",           "veneer.spoof.admin"),
            new HelpEntry("spoof",   "/spoof brands [allow|block|forget <id>]",  "spoof-brands",          "veneer.spoof.admin"),
            new HelpEntry("spoof",   "/spoof blockedbrands",                     "spoof-blockedbrands",   "veneer.spoof.admin"),
            new HelpEntry("spoof",   "/spoof blockedchannels",                   "spoof-blockedchannels", "veneer.spoof.admin"),
            new HelpEntry("hacks",   "/hackcheck <{player}> [{mods}]",           "hackcheck",             "veneer.hacks.check"),
            new HelpEntry("hacks",   "/hacklang <{player}> [{languages}]",       "hacklang",              "veneer.hacks.lang"),
            new HelpEntry("hacks",   "/hackeditor",                              "hackeditor",            "veneer.hacks.editor"),
            new HelpEntry("rptrack", "/rptrack <{player}>",                      "rptrack",               "veneer.rptrack"));


    private final Veneer plugin;

    public VeneerCommand(Veneer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String target = PASSTHROUGH.get(sub);
        if (target != null) {
            PluginCommand routed = plugin.getCommand(target);
            if (routed != null) routed.execute(sender, target, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        switch (sub) {
            case "reload" -> reload(sender);
            case "alerts" -> alertsPage(sender, args);
            case "lang", "language" -> lang(sender, args);
            case "info" -> info(sender, label, args);
            case "version" -> version(sender);
            case "gui" -> { if (sender instanceof Player p && !denied(sender, "veneer.admin")) plugin.gui().openPage(p, args); }
            case "player", "p" -> playerPage(sender, args, false);
            case "find" -> playerPage(sender, args, true);
            case "check" -> checkPage(sender, args);
            case "brands" -> {
                if (!(sender instanceof Player p)) { plugin.messages().send(sender, "veneer-cmd-players-only"); return true; }
                if (denied(sender, "veneer.admin")) return true;
                plugin.gui().openPage(p, new String[]{"gui", "brands"});
            }
            case "channels" -> {
                if (!(sender instanceof Player p)) { plugin.messages().send(sender, "veneer-cmd-players-only"); return true; }
                if (denied(sender, "veneer.admin")) return true;
                plugin.gui().openPage(p, new String[]{"gui", "channels"});
            }
            default -> {
                if (args.length == 0 && sender instanceof Player p && plugin.gui().open(p)) break;
                help(sender);
            }
        }
        return true;
    }

    private boolean denied(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return false;
        sender.sendMessage(plugin.messages().in(sender).get("no-permission"));
        return true;
    }

    /** The commands the sender can use, grouped by module, each clickable to put it into the chat box. */
    private void help(CommandSender sender) {
        Messages m = plugin.messages().in(sender);
        m.send(sender, "veneer-help-title", "version", plugin.version());
        String section = "";
        for (HelpEntry e : HELP) {
            if (e.module() != null && !module(e.module()).running()) continue;
            if (e.permission() != null && !sender.hasPermission(e.permission())) continue;
            String id = e.module() == null ? "general" : e.module();
            if (!id.equals(section)) {
                section = id;
                sender.sendMessage(m.with("veneer-help-section", "section", m.raw("veneer-help-section-" + id)));
            }
            String usage = e.usage();
            for (String arg : List.of("player", "mods", "languages", "language"))
                usage = usage.replace("{" + arg + "}", m.plain("veneer-help-arg-" + arg));
            // What the click types: the command up to its first argument
            String typed = e.usage().replaceAll(" [<\\[].*$", "") + " ";
            sender.sendMessage(m.with("veneer-help-line", "usage", usage, "typed", typed,
                    "description", m.raw("veneer-cmd-" + e.key())));
        }
        m.send(sender, "veneer-help-shortcut");
    }

    private VeneerModule module(String id) {
        return switch (id) {
            case "spoof" -> plugin.spoof();
            case "hacks" -> plugin.hacks();
            default -> plugin.rptrack();
        };
    }

    /** {@code /veneer lang}: shows the choice; {@code <code>}, {@code auto} (the client's language) or {@code reset} changes it. */
    private void lang(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "veneer-lang-players-only");
            return;
        }
        if (denied(sender, "veneer.lang")) return;
        Messages messages = plugin.messages();
        String available = String.join(", ", messages.languages());
        if (args.length < 2) {
            String choice = messages.choice(player.getUniqueId());
            String current = messages.in(player).language();
            if (choice == null) messages.send(sender, "veneer-lang-current-server", "lang", current, "available", available);
            else if (choice.equals(Messages.AUTO)) messages.send(sender, "veneer-lang-current-auto", "lang", current, "available", available);
            else messages.send(sender, "veneer-lang-current", "lang", current, "available", available);
            return;
        }
        String code = args[1].toLowerCase(Locale.ROOT);
        if (code.equals("reset") || code.equals("server")) {
            messages.choose(player.getUniqueId(), null);
            messages.send(sender, "veneer-lang-reset", "lang", messages.language());
        } else if (code.equals(Messages.AUTO)) {
            messages.choose(player.getUniqueId(), Messages.AUTO);
            messages.send(sender, "veneer-lang-auto", "lang", messages.in(player).language(), "client", player.getLocale());
        } else if (messages.exists(code)) {
            messages.choose(player.getUniqueId(), code);
            messages.send(sender, "veneer-lang-set", "lang", code);
        } else {
            messages.send(sender, "veneer-lang-unknown", "lang", args[1], "available", available);
        }
    }

    private void reload(CommandSender sender) {
        if (denied(sender, "veneer.admin")) return;
        plugin.reloadAll();
        sender.sendMessage(plugin.messages().in(sender).get("reload-done"));
    }

    /** {@code /veneer alerts [nick]} — alert history GUI. {@code /veneer alerts toggle} keeps the old toggle. */
    private void alertsPage(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "veneer-alerts-players-only");
            return;
        }
        if (!player.hasPermission(Alerts.PERMISSION)) {
            sender.sendMessage(plugin.messages().in(sender).get("no-permission"));
            return;
        }
        if (args.length >= 2 && "toggle".equalsIgnoreCase(args[1])) {
            boolean on = plugin.alerts().toggle(player.getUniqueId());
            sender.sendMessage(plugin.messages().in(sender).get(on ? "alerts-enabled" : "alerts-disabled"));
            return;
        }
        // The history is part of the admin panel; veneer.alerts alone only toggles
        if (denied(sender, "veneer.admin")) return;
        String target = args.length >= 2 ? args[1] : null;
        plugin.gui().openPage(player, target == null
                ? new String[]{"gui", "alerts"}
                : new String[]{"gui", "alerts", "0", target});
    }

    /** {@code /veneer player [nick]} — player info GUI; no nick opens player list. */
    private void playerPage(CommandSender sender, String[] args, boolean requireNick) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "veneer-cmd-players-only");
            return;
        }
        if (denied(sender, "veneer.admin")) return;
        if (args.length < 2) {
            if (requireNick) {
                plugin.messages().send(sender, "veneer-info-usage", "label", "veneer find");
                return;
            }
            plugin.gui().openPage(player, new String[]{"gui", "players"});
            return;
        }
        plugin.gui().openPlayerInfo(player, args[1]);
    }

    /** {@code /veneer check <player>} — Dialog with check-type checkboxes. */
    private void checkPage(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "veneer-cmd-players-only");
            return;
        }
        if (denied(sender, "veneer.admin")) return;
        if (args.length < 2) {
            plugin.messages().send(sender, "veneer-info-usage", "label", "veneer check");
            return;
        }
        plugin.gui().openCheckDialog(player, args[1]);
    }

    private void version(CommandSender sender) {
        if (denied(sender, "veneer.admin")) return;
        String current = plugin.version();
        plugin.messages().send(sender, "veneer-running", "version", current);
        plugin.updates().lookup(false);
        UpdateChecker.Status s = plugin.updates().status();
        if (s == null) plugin.messages().send(sender, "veneer-latest-checking");
        else if (s.latest() == null) plugin.messages().send(sender, "veneer-latest-none");
        else if (s.updateAvailable()) plugin.messages().send(sender, "veneer-update-available", "latest", s.latest(), "url", s.url());
        else if (s.ahead()) plugin.messages().send(sender, "veneer-latest-ahead", "latest", s.latest(), "current", current);
        else plugin.messages().send(sender, "veneer-latest-ok", "latest", s.latest());
    }

    private void info(CommandSender sender, String label, String[] args) {
        if (denied(sender, "veneer.admin")) return;
        if (args.length < 2) {
            plugin.messages().send(sender, "veneer-info-usage", "label", label);
            return;
        }
        String name = args[1];
        Player online = Bukkit.getPlayerExact(name);

        plugin.messages().send(sender, "veneer-info-title", "player", name,
                "offline", online == null ? plugin.messages().in(sender).raw("veneer-info-offline") : new Messages.Raw(""));
        if (online != null) clientSection(sender, online);

        FoliaScheduler.runAsync(plugin, () -> {
            // An offline player's uuid from the database: Bukkit.getOfflinePlayer(name) may ask Mojang and block
            String uuid = online != null ? online.getUniqueId().toString()
                    : (String) plugin.database().players().getPlayerRecord(name).getOrDefault("uuid", "");
            scanSection(sender, uuid, name);
            rptrackSection(sender, online, uuid);
        });
    }

    private void clientSection(CommandSender sender, Player player) {
        PlayerProfile profile = plugin.profileOf(player.getUniqueId());
        String brand = plugin.brandOf(player) == null ? plugin.messages().in(sender).plain("veneer-unknown") : plugin.brandOf(player);
        int channels = profile == null ? 0 : profile.getChannels().size();
        String type = profile == null ? plugin.messages().in(sender).plain("veneer-unknown") : profile.clientType().name().toLowerCase(Locale.ROOT);
        plugin.messages().send(sender, "veneer-info-client", "brand", brand, "type", type, "channels", channels);
        if (profile != null && profile.getVersionReal() != null)
            plugin.messages().send(sender, profile.isVersionMismatch() ? "veneer-info-version-mismatch" : "veneer-info-version",
                    "real", profile.getVersionReal(), "claimed", profile.getVersionClaimed());

        List<String> flags = new ArrayList<>();
        if (plugin.bedrock().isBedrock(player)) flags.add(plugin.messages().in(sender).getRaw("veneer-flag-bedrock"));
        if (plugin.spoof().running() && plugin.spoof().isPlayerSpoofing(player)) flags.add(plugin.messages().in(sender).getRaw("veneer-flag-spoof"));
        if (!flags.isEmpty()) plugin.messages().send(sender, "veneer-info-status", "flags", new Messages.Raw(String.join(", ", flags)));

        String group = plugin.hooks().primaryGroup(player);
        if (!group.isEmpty()) plugin.messages().send(sender, "veneer-info-group", "group", group);
        bypassSection(sender, player);
    }

    /** One line per running module the player bypasses, with the node (and group) that grants it. */
    private void bypassSection(CommandSender sender, Player player) {
        for (VeneerModule module : List.of(plugin.spoof(), plugin.hacks(), plugin.rptrack())) {
            if (!module.running()) continue;
            VeneerPlugin.Bypass join = plugin.bypass(player, module.id(), true);
            // RPTrack only checks on joining
            VeneerPlugin.Bypass later = module == plugin.rptrack() ? join : plugin.bypass(player, module.id(), false);
            if (!join.value() && !later.value()) continue;
            String when = join.value() && later.value() ? "veneer-info-bypass-always"
                    : join.value() ? "veneer-info-bypass-join" : "veneer-info-bypass-later";
            VeneerPlugin.Bypass by = join.value() ? join : later;
            Messages.Raw source = new Messages.Raw(by.group() == null
                    ? plugin.messages().in(sender).plain("veneer-info-bypass-node", "node", by.node())
                    : plugin.messages().in(sender).plain("veneer-info-bypass-node-group", "node", by.node(), "group", by.group()));
            plugin.messages().send(sender, "veneer-info-bypass", "module", module.id(), "when", plugin.messages().in(sender).raw(when), "source", source);
        }
    }

    @SuppressWarnings("unchecked")
    private void scanSection(CommandSender sender, String uuid, String name) {
        if (!plugin.hacks().running()) return;
        List<Map<String, Object>> scans = plugin.database().scans().getPlayerScans(uuid, name).stream()
                .filter(row -> "hack".equals(row.get("type"))).toList();
        if (scans.isEmpty()) {
            plugin.messages().send(sender, "veneer-scan-never");
            return;
        }
        Map<String, Object> latest = scans.get(0);
        List<Map<String, Object>> results = (List<Map<String, Object>>) latest.get("results");
        String found = results.stream()
                .filter(r -> HackResult.DETECTED.name().equals(r.get("result")) || HackResult.PROTECTED.name().equals(r.get("result"))
                        || HackResult.TIMEOUT.name().equals(r.get("result")))
                .map(r -> r.get("hack_name") + " (" + String.valueOf(r.get("result")).toLowerCase(Locale.ROOT) + ")")
                .collect(Collectors.joining(", "));
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(((Number) latest.get("timestamp")).longValue()));
        plugin.messages().send(sender, "veneer-scan-line", "when", when, "count", scans.size(),
                "result", found.isEmpty() ? plugin.messages().in(sender).raw("veneer-scan-clean")
                        : new Messages.Raw(plugin.messages().in(sender).plain("veneer-scan-found", "found", found)));
    }

    private void rptrackSection(CommandSender sender, Player online, String uuid) {
        RPTrackManager rp = plugin.rptrack();
        if (!rp.isEnabled()) return;
        long mask = rp.getMask(uuid);
        if (mask == 0) {
            plugin.messages().send(sender, "veneer-rp-none");
            return;
        }
        List<String> linked = rp.findLinked(uuid, mask);
        plugin.messages().send(sender, "veneer-rp-line", "packs", Long.bitCount(mask),
                "linked", linked.isEmpty() ? plugin.messages().in(sender).plain("veneer-rp-linked-none") : String.join(", ", linked));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            List<String> options = new ArrayList<>(SUBCOMMANDS);
            PASSTHROUGH.forEach((name, target) -> {
                PluginCommand c = plugin.getCommand(target);
                if (c != null && c.testPermissionSilent(sender)) options.add(name);
            });
            return options.stream().filter(s -> s.startsWith(sub)).sorted().toList();
        }
        String target = PASSTHROUGH.get(sub);
        if (target != null) {
            PluginCommand c = plugin.getCommand(target);
            return c == null ? List.of() : c.tabComplete(sender, target, Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length == 2 && (sub.equals("lang") || sub.equals("language"))) {
            List<String> options = new ArrayList<>(plugin.messages().languages());
            options.add(Messages.AUTO);
            options.add("reset");
            return options.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && (sub.equals("info") || sub.equals("player") || sub.equals("p")
                || sub.equals("find") || sub.equals("check")))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && sub.equals("alerts"))
            return List.of("toggle");
        return List.of();
    }
}
