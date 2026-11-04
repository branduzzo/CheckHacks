package com.veneer.spoof.commands;

import com.veneer.spoof.SpoofModule;
import com.veneer.VeneerPlugin;
import com.veneer.core.Messages;
import com.veneer.core.PlayerProfile;
import com.veneer.spoof.managers.BrandVerdicts;
import com.veneer.spoof.managers.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

public class SpoofCommand implements CommandExecutor, TabCompleter {
    private final VeneerPlugin plugin;
    private final List<String> subcommands = Arrays.asList(
        "channels", "brand", "brands", "help", "check", "blockedchannels", "blockedbrands", "runcheck"
    );

    public SpoofCommand(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages.Raw raw(CommandSender sender, String key) {
        return plugin.messages().in(sender).raw(key);
    }

    /** Sends the language-file message {@code key}; {@code pairs} are placeholder name and value. */
    private void send(CommandSender sender, String key, Object... pairs) {
        plugin.messages().send(sender, key, pairs);
    }

    private String plain(CommandSender sender, String key, Object... pairs) {
        return plugin.messages().in(sender).plain(key, pairs);
    }

    private Messages.Raw yesNo(CommandSender sender, boolean value) {
        return raw(sender, value ? "spoof-yes" : "spoof-no");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);

        // Check permission
        if (!sender.hasPermission("veneer.spoof.use")) {
            send(sender, "no-permission");
            return true;
        }

        if (subCommand.equals("help")) {
            showHelp(sender);
            return true;
        }

        // Command to show blocked channels
        if (subCommand.equals("blockedchannels")) {
            if (!sender.hasPermission("veneer.spoof.admin")) {
                send(sender, "no-permission");
                return true;
            }

            showBlockedChannels(sender);
            return true;
        }

        // Command to show blocked brands
        if (subCommand.equals("blockedbrands")) {
            if (!sender.hasPermission("veneer.spoof.admin")) {
                send(sender, "no-permission");
                return true;
            }

            showBlockedBrands(sender);
            return true;
        }

        // Staff verdicts on unknown client brands
        if (subCommand.equals("brands")) {
            if (!sender.hasPermission("veneer.spoof.admin")) {
                send(sender, "no-permission");
                return true;
            }
            handleBrands(sender, args);
            return true;
        }

        // Handle runcheck command
        if (subCommand.equals("runcheck")) {
            handleRunCheckCommand(sender, args);
            return true;
        }

        // Handle check command
        if (subCommand.equals("check")) {
            if (!sender.hasPermission("veneer.spoof.admin")) {
                send(sender, "no-permission");
                return true;
            }

            if (args.length < 2) {
                // Check all online players
                checkAllPlayers(sender);
                return true;
            }

            // Check specific player
            String playerName = args[1];
            if (playerName.equals("*")) {
                checkAllPlayers(sender);
                return true;
            }

            Player target = Bukkit.getPlayer(playerName);
            if (target == null) {
                send(sender, "player-not-found", "player", args.length > 1 ? args[1] : "");
                return true;
            }

            checkPlayer(sender, target);
            return true;
        }

        // Commands that require a player argument
        if (args.length < 2) {
            send(sender, "spoof-usage", "label", label, "sub", subCommand);
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            send(sender, "player-not-found", "player", args.length > 1 ? args[1] : "");
            return true;
        }

        UUID targetUUID = target.getUniqueId();
        PlayerProfile data = plugin.profiles().get(targetUUID);
        if (data == null) {
            send(sender, "spoof-no-data");
            return true;
        }

        // Handle subcommands
        switch (subCommand) {
            case "channels" -> showChannels(sender, target, data);
            case "brand" -> showBrand(sender, target);
            default -> {
                send(sender, "spoof-unknown-sub", "sub", subCommand);
                showHelp(sender);
            }
        }

        return true;
    }

    /** {@code /spoof brands} lists unknown brands; {@code allow|block|forget <id>} records a verdict (the alert's buttons). */
    private void handleBrands(CommandSender sender, String[] args) {
        BrandVerdicts verdicts = plugin.module(SpoofModule.class).verdicts();
        if (args.length >= 2) {
            BrandVerdicts.Verdict verdict;
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "allow" -> verdict = BrandVerdicts.Verdict.ALLOW;
                case "block" -> verdict = BrandVerdicts.Verdict.BLOCK;
                case "forget" -> verdict = null;
                default -> {
                    send(sender, "spoof-verdict-usage");
                    return;
                }
            }
            if (args.length < 3) {
                send(sender, "spoof-verdict-usage");
                return;
            }
            int id;
            try {
                id = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                send(sender, "spoof-verdict-unknown-id", "id", args[2]);
                return;
            }
            if (!verdicts.decide(id, verdict, sender.getName())) {
                send(sender, "spoof-verdict-unknown-id", "id", args[2]);
                return;
            }
            String brand = verdicts.get(id).brand();
            send(sender, verdict == null ? "spoof-verdict-forgotten"
                    : verdict == BrandVerdicts.Verdict.ALLOW ? "spoof-verdict-allowed" : "spoof-verdict-blocked", "brand", brand);
            plugin.getLogger().info(sender.getName() + (verdict == null ? " cleared the verdict on"
                    : verdict == BrandVerdicts.Verdict.ALLOW ? " allowed" : " blocked") + " client brand '" + brand + "'");
            // Apply it to players online with that brand now, not on their next join
            for (Player p : Bukkit.getOnlinePlayers())
                if (brand.equals(plugin.brandOf(p))) plugin.module(SpoofModule.class).detection().checkPlayerAsync(p, false, true);
            return;
        }

        List<BrandVerdicts.Entry> all = verdicts.all();
        send(sender, "spoof-verdict-list-title", "count", all.size());
        if (all.isEmpty()) send(sender, "spoof-verdict-list-empty");
        for (BrandVerdicts.Entry e : all.subList(0, Math.min(20, all.size()))) {
            String key = e.verdict() == null ? "spoof-verdict-list-undecided"
                    : e.verdict() == BrandVerdicts.Verdict.ALLOW ? "spoof-verdict-list-allowed" : "spoof-verdict-list-blocked";
            send(sender, key, "id", e.id(), "brand", e.brand(),
                    "player", e.lastPlayer() == null ? "?" : e.lastPlayer(), "by", e.decidedBy() == null ? "?" : e.decidedBy());
        }
    }

    private void handleRunCheckCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("veneer.spoof.admin")) {
            send(sender, "no-permission");
            return;
        }

        if (args.length < 2 || args[1].equals("*")) {
            send(sender, "spoof-run-all");
            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                plugin.module(SpoofModule.class).detection().checkPlayerAsync(player, false, true);
                count++;
            }
            send(sender, "spoof-run-done", "count", count);
            send(sender, "spoof-run-note");
            return;
        }

        String playerName = args[1];
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            send(sender, "player-not-found", "player", args.length > 1 ? args[1] : "");
            return;
        }

        send(sender, "spoof-run-player", "player", target.getName());
        plugin.module(SpoofModule.class).detection().checkPlayerAsync(target, false, true);
        send(sender, "spoof-run-player-done", "player", target.getName());
        send(sender, "spoof-run-note");
    }

    private void showBlockedBrands(CommandSender sender) {
        boolean enabled = plugin.module(SpoofModule.class).config().isBlockedBrandsEnabled();
        boolean whitelistMode = plugin.module(SpoofModule.class).config().isBrandWhitelistEnabled();
        List<String> blockedBrands = plugin.module(SpoofModule.class).config().getBlockedBrands();

        send(sender, "spoof-brands-title");
        send(sender, "spoof-enabled", "value", yesNo(sender, enabled));
        send(sender, "spoof-match-type");
        send(sender, "spoof-mode", "mode", raw(sender, whitelistMode ? "spoof-mode-whitelist" : "spoof-mode-blacklist"));

        if (blockedBrands.isEmpty()) {
            send(sender, whitelistMode ? "spoof-brands-none-whitelisted" : "spoof-brands-none-blocked");
        } else {
            send(sender, whitelistMode ? "spoof-brands-list-whitelisted" : "spoof-brands-list-blocked");
            blockedBrands.forEach(brand -> send(sender, "spoof-list-item", "value", brand));
        }
    }

    private void showBlockedChannels(CommandSender sender) {
        boolean enabled = plugin.module(SpoofModule.class).config().isBlockedChannelsEnabled();
        String whitelistMode = plugin.module(SpoofModule.class).config().getChannelWhitelistMode();
        List<String> blockedChannels = plugin.module(SpoofModule.class).config().getBlockedChannels();

        boolean blacklist = whitelistMode.equals("FALSE");
        send(sender, "spoof-channels-title");
        send(sender, "spoof-enabled", "value", yesNo(sender, enabled));
        send(sender, "spoof-match-type");

        String modeKey = blacklist ? "spoof-mode-blacklist"
                : whitelistMode.equals("SIMPLE") ? "spoof-mode-whitelist-simple" : "spoof-mode-whitelist-strict";
        send(sender, "spoof-mode", "mode", raw(sender, modeKey));

        if (blockedChannels.isEmpty()) {
            send(sender, blacklist ? "spoof-channels-none-blocked" : "spoof-channels-none-whitelisted");
        } else {
            send(sender, blacklist ? "spoof-channels-list-blocked" : "spoof-channels-list-whitelisted");
            blockedChannels.forEach(channel -> send(sender, "spoof-list-item", "value", channel));
        }
    }

    private void checkPlayer(CommandSender sender, Player target) {
        boolean isSpoofing = plugin.module(SpoofModule.class).isPlayerSpoofing(target);
        String brand = plugin.module(SpoofModule.class).getClientBrand(target);
        List<String> flagReasons = new ArrayList<>();

        if (brand == null) {
            if (!plugin.module(SpoofModule.class).config().shouldBlockNonVanillaWithChannels()) {
                send(sender, "spoof-no-brand-yet", "player", target.getName());
                return;
            }
            flagReasons.add(plain(sender, "spoof-reason-non-vanilla"));
        }

        PlayerProfile data = plugin.profiles().get(target.getUniqueId());
        boolean hasChannels = data != null && !data.getChannels().isEmpty();
        boolean claimsVanilla = brand != null && brand.equalsIgnoreCase("vanilla");

        // Display channels first regardless of spoof status
        if (hasChannels) {
            send(sender, "spoof-channels-header");
            data.getChannels().forEach(channel -> send(sender, "spoof-channel-line", "channel", channel));
        }

        // Determine all reasons for flagging
        if (isSpoofing) {
            // Check for vanilla client with channels
            if (claimsVanilla && hasChannels && plugin.module(SpoofModule.class).config().isVanillaCheckEnabled()) {
                flagReasons.add(plain(sender, "spoof-reason-vanilla-channels"));
            }

            // Check for Geyser spoofing
            if (plugin.module(SpoofModule.class).isSpoofingGeyser(target)) {
                flagReasons.add(plain(sender, "spoof-reason-geyser"));
            }

            // Check for strict non-vanilla
            if (plugin.module(SpoofModule.class).config().shouldBlockNonVanillaWithChannels() && (!claimsVanilla || hasChannels)) {
                flagReasons.add(plain(sender, "spoof-reason-non-vanilla"));
            }

            // Check for blocked brand
            if (plugin.module(SpoofModule.class).config().isBlockedBrandsEnabled()) {
                boolean brandBlocked = plugin.module(SpoofModule.class).config().isBrandBlocked(brand);

                if (brandBlocked) {
                    if (plugin.module(SpoofModule.class).config().isBrandWhitelistEnabled()) {
                        flagReasons.add(plain(sender, "spoof-reason-brand-not-whitelisted"));
                    } else {
                        flagReasons.add(plain(sender, "spoof-reason-brand-blocked"));
                    }
                }
            }

            // Check channel whitelist/blacklist
            if (hasChannels && plugin.module(SpoofModule.class).config().isBlockedChannelsEnabled()) {
                String whitelistMode = plugin.module(SpoofModule.class).config().getChannelWhitelistMode();

                Set<String> filtered = plugin.module(SpoofModule.class).detection().getFilteredChannels(data.getChannels());

                if (!whitelistMode.equals("FALSE")) {
                    // Whitelist mode
                    boolean passesWhitelist = plugin.module(SpoofModule.class).detection().checkChannelWhitelist(filtered);
                    if (!passesWhitelist) {
                        if (whitelistMode.equals("STRICT")) {
                            // Get missing channels for strict mode
                            List<String> missingChannels = new ArrayList<>();
                            for (String whitelistedChannel : plugin.module(SpoofModule.class).config().getBlockedChannels()) {
                                boolean found = false;
                                for (String playerChannel : filtered) {
                                    try {
                                        if (playerChannel.matches(whitelistedChannel)) {
                                            found = true;
                                            break;
                                        }
                                    } catch (Exception e) {
                                        // If regex fails, try direct match
                                        if (playerChannel.equals(whitelistedChannel)) {
                                            found = true;
                                            break;
                                        }
                                    }
                                }

                                if (!found) {
                                    missingChannels.add(whitelistedChannel);
                                }
                            }

                            if (!missingChannels.isEmpty()) {
                                flagReasons.add(plain(sender, "spoof-reason-missing-channels", "channels", String.join(", ", missingChannels)));
                            } else {
                                flagReasons.add(plain(sender, "spoof-reason-whitelist-mismatch"));
                            }
                        } else {
                            flagReasons.add(plain(sender, "spoof-reason-no-whitelisted"));
                        }
                    }
                } else {
                    // Blacklist mode - check for blocked channels
                    String blockedChannel = plugin.module(SpoofModule.class).detection().findBlockedChannel(filtered);
                    if (blockedChannel != null) {
                        flagReasons.add(plain(sender, "spoof-reason-blocked-channel", "channel", blockedChannel));
                    }
                }
            }

            // Check for missing required brand channels
            String matchedBrand = plugin.module(SpoofModule.class).config().getMatchingClientBrand(brand);
            if (matchedBrand != null && hasChannels) {
                ConfigManager.ClientBrandConfig brandConfig = plugin.module(SpoofModule.class).config().getClientBrandConfig(matchedBrand);
                if (!brandConfig.getRequiredChannels().isEmpty()) {
                    // Look for missing required channels
                    List<String> missingRequiredChannels = new ArrayList<>();
                    for (String requiredChannel : brandConfig.getRequiredChannelStrings()) {
                        boolean found = false;
                        for (String playerChannel : data.getChannels()) {
                            try {
                                if (playerChannel.matches(requiredChannel)) {
                                    found = true;
                                    break;
                                }
                            } catch (Exception e) {
                                // If regex fails, try simple contains check
                                String simplePattern = requiredChannel
                                    .replace("(?i)", "")
                                    .replace(".*", "")
                                    .replace("^", "")
                                    .replace("$", "");
                                if (playerChannel.toLowerCase(Locale.ROOT).contains(simplePattern.toLowerCase(Locale.ROOT))) {
                                    found = true;
                                    break;
                                }
                            }
                        }
                        if (!found) {
                            missingRequiredChannels.add(requiredChannel);
                        }
                    }

                    if (!missingRequiredChannels.isEmpty()) {
                        flagReasons.add(plain(sender, "spoof-reason-missing-brand-channels", "brand", matchedBrand, "channels", String.join(", ", missingRequiredChannels)));
                    }
                }
            }

            // Add default reason if none found
            if (flagReasons.isEmpty()) {
                flagReasons.add(plain(sender, "spoof-reason-unknown"));
            }
        }

        // Display player status and brand information after channels
        if (isSpoofing) {
            send(sender, "spoof-flagged", "player", target.getName());

            // Show client brand first
            send(sender, "spoof-client-brand", "brand", String.valueOf(brand));

            // Show all violations
            send(sender, "spoof-violations", "count", flagReasons.size());
            for (String reason : flagReasons) {
                send(sender, "spoof-violation-item", "reason", reason);
            }

            // Check if the brand is blocked/not whitelisted and display with appropriate color
            if (plugin.module(SpoofModule.class).config().isBlockedBrandsEnabled()) {
                boolean brandBlocked = plugin.module(SpoofModule.class).config().isBrandBlocked(brand);

                if (brandBlocked) {
                    send(sender, "spoof-brand-status-blocked");
                } else {
                    send(sender, "spoof-brand-status-allowed");
                }
            }

            send(sender, "spoof-has-channels", "value", yesNo(sender, hasChannels));

            // Channel whitelist/blacklist checks
            if (hasChannels && plugin.module(SpoofModule.class).config().isBlockedChannelsEnabled()) {
                String whitelistMode = plugin.module(SpoofModule.class).config().getChannelWhitelistMode();

                if (!whitelistMode.equals("FALSE")) {
                    // Whitelist mode
                    send(sender, "spoof-whitelist-mode", "mode", raw(sender, whitelistMode.equals("STRICT") ? "spoof-mode-strict" : "spoof-mode-simple"));

                    send(sender, "spoof-whitelist-status");

                    // First, list all player channels and if they're whitelisted
                    for (String channel : data.getChannels()) {
                        boolean whitelisted = plugin.module(SpoofModule.class).config().matchesChannelPattern(channel);

                        if (whitelisted) {
                            send(sender, "spoof-channel-ok", "channel", channel);
                        } else {
                            send(sender, "spoof-channel-not-whitelisted", "channel", channel);
                        }
                    }

                    // If in STRICT mode, also list any missing whitelisted channels
                    if (whitelistMode.equals("STRICT")) {
                        List<String> missingWhitelistedChannels = new ArrayList<>();

                        for (String whitelistedChannel : plugin.module(SpoofModule.class).config().getBlockedChannels()) {
                            boolean found = false;
                            for (String playerChannel : data.getChannels()) {
                                try {
                                    if (playerChannel.matches(whitelistedChannel)) {
                                        found = true;
                                        break;
                                    }
                                } catch (Exception e) {
                                    // If regex fails, try direct match
                                    if (playerChannel.equals(whitelistedChannel)) {
                                        found = true;
                                        break;
                                    }
                                }
                            }

                            if (!found) {
                                missingWhitelistedChannels.add(whitelistedChannel);
                            }
                        }

                        if (!missingWhitelistedChannels.isEmpty()) {
                            send(sender, "spoof-missing-header");
                            for (String missingChannel : missingWhitelistedChannels) {
                                send(sender, "spoof-channel-bad", "channel", missingChannel);
                            }
                        }
                    }
                } else {
                    // Blacklist mode - Collect all blocked channels first, then display them together
                    List<String> blockedChannels = new ArrayList<>();

                    for (String channel : data.getChannels()) {
                        boolean blocked = plugin.module(SpoofModule.class).config().matchesChannelPattern(channel);

                        if (blocked) {
                            blockedChannels.add(channel);
                        }
                    }

                    // Display all blocked channels under one header
                    if (!blockedChannels.isEmpty()) {
                        send(sender, "spoof-blocked-header");
                        for (String channel : blockedChannels) {
                            send(sender, "spoof-channel-bad", "channel", channel);
                        }
                    }
                }
            }
        } else {
            send(sender, "spoof-not-spoofing", "player", target.getName());

            // Show client brand
            send(sender, "spoof-client-brand", "brand", String.valueOf(brand));

            // Check if the brand is blocked/not whitelisted and display with appropriate color
            if (plugin.module(SpoofModule.class).config().isBlockedBrandsEnabled()) {
                boolean brandBlocked = plugin.module(SpoofModule.class).config().isBrandBlocked(brand);

                if (brandBlocked) {
                    send(sender, "spoof-brand-status-blocked-noflag");
                } else {
                    send(sender, "spoof-brand-status-allowed");
                }
            }

            send(sender, "spoof-has-channels", "value", yesNo(sender, hasChannels));
        }
    }

    private void checkAllPlayers(CommandSender sender) {
        send(sender, "spoof-flagging-title");

        boolean foundSpoofing = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.module(SpoofModule.class).isPlayerSpoofing(player)) {
                foundSpoofing = true;
                String brand = plugin.module(SpoofModule.class).getClientBrand(player);
                send(sender, "spoof-flagging-line", "player", player.getName(),
                        "brand", brand != null ? brand : plain(sender, "spoof-brand-unknown"));
            }
        }

        if (!foundSpoofing) {
            send(sender, "spoof-none-flagging");
        }
    }

    private void showHelp(CommandSender sender) {
        for (String key : List.of("title", "channels", "brand", "brands", "check", "runcheck", "blockedchannels", "blockedbrands", "help"))
            send(sender, "spoof-help-" + key);
    }

    private void showChannels(CommandSender sender, Player target, PlayerProfile data) {
        send(sender, "spoof-channels-for", "player", target.getName());

        if (data.getChannels().isEmpty()) {
            send(sender, "spoof-channels-empty");
        } else {
            String whitelistMode = plugin.module(SpoofModule.class).config().getChannelWhitelistMode();
            boolean isWhitelist = !whitelistMode.equals("FALSE");

            data.getChannels().forEach(channel -> {
                boolean isListed = plugin.module(SpoofModule.class).config().matchesChannelPattern(channel);

                if (isListed) {
                    if (isWhitelist) {
                        send(sender, "spoof-ch-whitelisted", "channel", channel);
                    } else {
                        send(sender, "spoof-ch-blocked", "channel", channel);
                    }
                } else {
                    if (isWhitelist) {
                        send(sender, "spoof-ch-not-whitelisted", "channel", channel);
                    } else {
                        send(sender, "spoof-ch-plain", "channel", channel);
                    }
                }
            });
        }
    }

    private void showBrand(CommandSender sender, Player target) {
        String brand = plugin.module(SpoofModule.class).getClientBrand(target);
        if (brand == null || brand.isEmpty()) {
            send(sender, "spoof-brand-none", "player", target.getName());
        } else {
            // Check if the brand is in the whitelist/blacklist and display with appropriate color
            if (plugin.module(SpoofModule.class).config().isBlockedBrandsEnabled()) {
                boolean brandBlocked = plugin.module(SpoofModule.class).config().isBrandBlocked(brand);
                boolean whitelistMode = plugin.module(SpoofModule.class).config().isBrandWhitelistEnabled();

                if (brandBlocked) {
                    send(sender, "spoof-brand-blocked", "player", target.getName(), "brand", brand);

                    if (whitelistMode) {
                        send(sender, "spoof-status-not-whitelisted");
                    } else {
                        send(sender, "spoof-status-blocked");
                    }
                } else {
                    send(sender, "spoof-brand-ok", "player", target.getName(), "brand", brand);

                    if (whitelistMode) {
                        send(sender, "spoof-status-in-whitelist");
                    } else {
                        send(sender, "spoof-status-allowed");
                    }
                }
            } else {
                send(sender, "spoof-brand-plain", "player", target.getName(), "brand", brand);
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String partialArg = args[0].toLowerCase(Locale.ROOT);
            for (String subcommand : subcommands) {
                if (subcommand.startsWith(partialArg)) {
                    completions.add(subcommand);
                }
            }
        } else if (args.length == 2) {
            String partialArg = args[1].toLowerCase(Locale.ROOT);

            if (args[0].equalsIgnoreCase("brands"))
                for (String s : List.of("allow", "block", "forget"))
                    if (s.startsWith(partialArg)) completions.add(s);

            if (args[0].equalsIgnoreCase("check") ||
                args[0].equalsIgnoreCase("runcheck")) {
                completions.add("*");
            }

            if (args[0].equalsIgnoreCase("channels") ||
                args[0].equalsIgnoreCase("brand") ||
                args[0].equalsIgnoreCase("check") ||
                args[0].equalsIgnoreCase("runcheck")) {
                completions.addAll(Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partialArg))
                        .collect(Collectors.toList()));
            }
        }

        return completions;
    }
}