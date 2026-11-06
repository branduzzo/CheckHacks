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

    private static final List<String> TYPES = List.of("all", "version", "lang", "hack");

    private final VeneerPlugin plugin;

    public HackCheckCommand(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("veneer.hacks.check") && !sender.hasPermission("veneer.hacks.lang")) {
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
        HacksModule hacksModule = plugin.module(HacksModule.class);
        if (hacksModule.checks().isChecking(target.getUniqueId()) || hacksModule.langChecks().isChecking(target.getUniqueId())) {
            sender.sendMessage(plugin.messages().in(sender).get("already-checking",
                    Map.of("player", target.getName())));
            return true;
        }

        // /hackcheck <player> <hack1,hack2> still works: anything that is not a type is a list of mods
        String second = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "all";
        String type = TYPES.contains(second) ? second : "hack";
        String list = TYPES.contains(second) ? (args.length >= 3 ? args[2] : null) : args[1];

        boolean wantsHacks = type.equals("hack") || type.equals("all");
        boolean wantsLang  = !type.equals("hack");
        if ((wantsHacks && !sender.hasPermission("veneer.hacks.check")) || (wantsLang && !sender.hasPermission("veneer.hacks.lang"))) {
            sender.sendMessage(plugin.messages().in(sender).get("no-permission", Map.of()));
            return true;
        }

        List<HackDefinition> hacks = List.of();
        if (wantsHacks) {
            if (list != null && type.equals("hack")) {
                hacks = new ArrayList<>();
                for (String id : list.split(",")) {
                    HackDefinition h = hacksModule.config().getHack(id.trim().toLowerCase(Locale.ROOT));
                    if (h == null) {
                        sender.sendMessage(plugin.messages().in(sender).get("invalid-hack", Map.of("hack", id.trim())));
                        return true;
                    }
                    hacks.add(h);
                }
            } else {
                hacks = hacksModule.config().getDefaultCheckHacks();
            }
            if (hacks.isEmpty() && type.equals("hack")) {
                plugin.messages().send(sender, "hackcheck-no-valid");
                return true;
            }
        }

        Map<String, String> languages = hacksModule.config().getLanguages();
        if (type.equals("lang") && list != null) {
            Map<String, String> chosen = new java.util.LinkedHashMap<>();
            for (String code : list.split(",")) {
                String trimmed = code.trim().toLowerCase(Locale.ROOT);
                if (!languages.containsKey(trimmed)) {
                    plugin.messages().send(sender, "hacklang-unknown", "lang", trimmed);
                    return true;
                }
                chosen.put(trimmed, languages.get(trimmed));
            }
            languages = chosen;
        }

        Player initiator = (sender instanceof Player p) ? p : null;
        String reason = initiator != null
                ? "Manual check by " + initiator.getName() : "Console check";
        final List<HackDefinition> scanHacks = hacks;
        final Map<String, String> scanLanguages = languages;
        FoliaScheduler.runAtEntity(plugin, target, () -> {
            switch (type) {
                case "lang" -> hacksModule.langChecks().startCheck(target, initiator, scanLanguages, true, false);
                case "version" -> hacksModule.langChecks().startCheck(target, initiator, scanLanguages, false, true);
                case "all" -> {
                    // The language sign is quick, the mod scan waits for it: they share one sign editor
                    hacksModule.langChecks().startCheck(target, initiator, scanLanguages, !scanLanguages.isEmpty(), true);
                    if (!scanHacks.isEmpty()) hacksModule.checks().startCheckAfterLang(target, initiator, scanHacks, reason);
                }
                default -> hacksModule.checks().startCheck(target, initiator, scanHacks, false, reason);
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("veneer.hacks.check") && !sender.hasPermission("veneer.hacks.lang")) return List.of();
        if (args.length == 1)
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        if (args.length == 2) {
            String typed = args[1];
            String prefix  = typed.contains(",") ? typed.substring(0, typed.lastIndexOf(',') + 1) : "";
            String current = typed.contains(",") ? typed.substring(typed.lastIndexOf(',') + 1) : typed;
            List<String> options = new ArrayList<>(prefix.isEmpty() ? TYPES : List.of());
            options.addAll(plugin.module(HacksModule.class).config().getHacks().keySet());
            return options.stream()
                    .filter(id -> id.startsWith(current.toLowerCase(Locale.ROOT)))
                    .map(id -> prefix + id)
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && (args[1].equalsIgnoreCase("hack") || args[1].equalsIgnoreCase("lang"))) {
            String typed = args[2];
            String prefix  = typed.contains(",") ? typed.substring(0, typed.lastIndexOf(',') + 1) : "";
            String current = typed.substring(prefix.length()).toLowerCase(Locale.ROOT);
            var config = plugin.module(HacksModule.class).config();
            var ids = args[1].equalsIgnoreCase("hack") ? config.getHacks().keySet() : config.getLanguages().keySet();
            return ids.stream().filter(id -> id.startsWith(current)).map(id -> prefix + id).collect(Collectors.toList());
        }
        return List.of();
    }
}
