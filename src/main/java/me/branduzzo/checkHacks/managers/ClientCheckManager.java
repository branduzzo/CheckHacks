package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.*;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import me.branduzzo.checkHacks.utils.GlobUtil;
import me.branduzzo.checkHacks.utils.WebhookUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Runs the checks that do not need signs at all: the client brand check and
 * the mod list check (both read data the server already has, so they are
 * instant). Everything is written into a {@link CheckReport} so the database,
 * the web editor and Discord share the same code path.
 */
public class ClientCheckManager {

    private final CheckHacksPlugin plugin;

    public ClientCheckManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    /* ================================================================== */
    /*  profile / detection                                               */
    /* ================================================================== */

    /** Re-reads brand and channels from the live player and updates the profile. */
    public ClientProfile refreshProfile(Player player) {
        ClientProfile profile = plugin.getClientDataManager().getOrCreate(player);
        profile.setName(player.getName());
        try {
            profile.setChannels(player.getListeningPluginChannels());
        } catch (Throwable ignored) {
        }
        String brand = readBrand(player);
        if (brand != null && !brand.isBlank()) profile.setBrand(brand);
        profile.setClientType(inferType(profile.getBrand(), profile.getChannels()));
        return profile;
    }

    /**
     * Reads the client brand with reflection: Paper exposes
     * {@code Player#getClientBrandName()}, on Spigot the method is missing and
     * we simply return null, the caller falls back to channel sniffing.
     */
    public String readBrand(Player player) {
        for (String method : new String[]{"getClientBrandName", "getBrandName", "getClientBrand"}) {
            try {
                Object value = player.getClass().getMethod(method).invoke(player);
                if (value instanceof String s && !s.isBlank()) return s.trim();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Best effort client type: brand keywords first, plugin channels second. */
    public ClientType inferType(String brand, Collection<String> channels) {
        ClientType fromBrand = typeFromBrandKeywords(brand);
        if (fromBrand != ClientType.UNKNOWN) return fromBrand;

        if (channels != null) {
            for (String channel : channels) {
                if (channel == null) continue;
                String lower = channel.toLowerCase(Locale.ROOT);
                if (lower.startsWith("fml") || lower.startsWith("forge")) return ClientType.FORGE;
                if (lower.startsWith("neoforge")) return ClientType.NEOFORGE;
                if (lower.startsWith("quilt")) return ClientType.QUILT;
                if (lower.startsWith("fabric")) return ClientType.FABRIC;
                if (lower.startsWith("lunar")) return ClientType.LUNAR;
                if (lower.startsWith("badlion")) return ClientType.BADLION;
                if (lower.startsWith("labymod")) return ClientType.LABYMOD;
                if (lower.startsWith("feather")) return ClientType.FEATHER;
            }
            // second pass, a known loader mod is a good hint too
            for (String channel : channels) {
                if (channel == null) continue;
                String lower = channel.toLowerCase(Locale.ROOT);
                if (lower.startsWith("sodium") || lower.startsWith("modmenu")
                        || lower.startsWith("fabric-api")) return ClientType.FABRIC;
            }
        }
        return ClientType.UNKNOWN;
    }

    private ClientType typeFromBrandKeywords(String brand) {
        if (brand == null || brand.isBlank()) return ClientType.UNKNOWN;
        String b = brand.toLowerCase(Locale.ROOT);
        if (b.contains("lunar")) return ClientType.LUNAR;
        if (b.contains("badlion")) return ClientType.BADLION;
        if (b.contains("feather")) return ClientType.FEATHER;
        if (b.contains("labymod")) return ClientType.LABYMOD;
        if (b.contains("neoforge")) return ClientType.NEOFORGE;
        if (b.contains("quilt")) return ClientType.QUILT;
        if (b.contains("fabric")) return ClientType.FABRIC;
        if (b.contains("forge")) return ClientType.FORGE;
        if (b.contains("vanilla")) return ClientType.VANILLA;
        if (b.contains("geyser") || b.contains("bedrock")) return ClientType.BEDROCK;
        return ClientType.UNKNOWN;
    }

    /** Classifies a raw brand string against the configured rules. */
    public BrandInfo classifyBrand(String rawBrand) {
        String value = rawBrand == null ? "" : rawBrand.trim();
        if (value.isEmpty())
            return new BrandInfo("", "Unknown", ClientType.UNKNOWN, false, "", false);

        ConfigManager cfg = plugin.getConfigManager();

        for (String pattern : cfg.getBrandSuspiciousPatterns()) {
            if (GlobUtil.matches(pattern, value))
                return new BrandInfo(value, value, typeFromBrandKeywords(value), true, pattern, false);
        }
        for (BrandRule rule : cfg.getBrandRules()) {
            if (GlobUtil.matches(rule.getPattern(), value))
                return new BrandInfo(value, rule.getDisplayName(), rule.getType(), false, rule.getPattern(), true);
        }
        return new BrandInfo(value, value, typeFromBrandKeywords(value), false, "", false);
    }

    /** Maps every plugin channel to a known mod. */
    public List<ModMatch> matchMods(Collection<String> channels) {
        ConfigManager cfg = plugin.getConfigManager();
        List<ModMatch> matches = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (channels == null) return matches;
        for (String channel : channels) {
            if (channel == null || channel.isBlank()) continue;
            if (GlobUtil.matchesAny(cfg.getIgnoredChannels(), channel)) continue;

            boolean matched = false;
            for (ChannelModRule rule : cfg.getChannelModRules()) {
                if (!GlobUtil.matches(rule.getPattern(), channel)) continue;
                if (seen.add(rule.getDisplayName().toLowerCase(Locale.ROOT)))
                    matches.add(new ModMatch(rule.getDisplayName(), rule.getPattern(), channel, rule.isSuspicious()));
                matched = true;
                break;
            }
            if (!matched && GlobUtil.matchesAny(cfg.getSuspiciousChannels(), channel)) {
                if (seen.add(channel.toLowerCase(Locale.ROOT)))
                    matches.add(new ModMatch(channel, channel, channel, true));
            }
        }
        return matches;
    }

    /* ================================================================== */
    /*  checks                                                            */
    /* ================================================================== */

    public CheckReport runBrandCheck(Player target, Player initiator, String reason) {
        ClientProfile profile = refreshProfile(target);
        BrandInfo info = classifyBrand(profile.getBrand());
        String checker = checkerName(initiator);

        CheckReport report = new CheckReport(CheckType.BRAND, target.getUniqueId(),
                target.getName(), checker, reason);

        if (profile.hasBrand()) {
            report.add("brand", "Client brand", info.getRaw(),
                    info.isSuspicious() ? CheckOutcome.DETECTED : CheckOutcome.CLEAN);
        } else {
            report.add("brand", "Client brand", "unknown", CheckOutcome.UNKNOWN);
        }
        report.add("client-type", "Client type", profile.getClientType().getDisplay(),
                profile.getClientType() == ClientType.UNKNOWN ? CheckOutcome.UNKNOWN : CheckOutcome.INFO);
        report.add("match", "Matched rule",
                info.isKnown() ? info.getMatchedPattern() : (info.isSuspicious() ? info.getMatchedPattern() : "-"),
                CheckOutcome.INFO);
        report.add("channels", "Plugin channels", String.valueOf(profile.getChannels().size()),
                CheckOutcome.INFO);

        Component headline = profile.hasBrand()
                ? plugin.getMessageManager().get("brand-check-complete", Map.of(
                        "player", target.getName(),
                        "brand", info.getRaw(),
                        "type", profile.getClientType().getDisplay()))
                : plugin.getMessageManager().get("brand-check-unknown", Map.of("player", target.getName()));

        Component warning = info.isSuspicious()
                ? plugin.getMessageManager().get("brand-suspicious", Map.of(
                        "player", target.getName(), "brand", info.getRaw()))
                : null;

        publish(report, target, initiator, headline, warning);
        return report;
    }

    public CheckReport runModListCheck(Player target, Player initiator, String reason) {
        ClientProfile profile = refreshProfile(target);
        List<ModMatch> mods = matchMods(profile.getChannels());
        String checker = checkerName(initiator);

        CheckReport report = new CheckReport(CheckType.MODLIST, target.getUniqueId(),
                target.getName(), checker, reason);

        report.add("client-type", "Client type", profile.getClientType().getDisplay(),
                profile.getClientType() == ClientType.UNKNOWN ? CheckOutcome.UNKNOWN : CheckOutcome.INFO);
        report.add("channel-count", "Plugin channels", String.valueOf(profile.getChannels().size()),
                CheckOutcome.INFO);

        for (ModMatch mod : mods)
            report.add("mod:" + slug(mod.getName()), mod.getName(), mod.getChannel(),
                    mod.isSuspicious() ? CheckOutcome.DETECTED : CheckOutcome.CLEAN);

        if (mods.isEmpty()) report.add("mods", "Detected mods", "none", CheckOutcome.CLEAN);
        if (plugin.getConfigManager().isClientShowChannels())
            report.add("channels", "Channels", joinChannels(profile.getSortedChannels()), CheckOutcome.INFO);

        String modList = mods.stream().map(ModMatch::getName).distinct()
                .collect(Collectors.joining(", "));
        String flaggedNames = mods.stream().filter(ModMatch::isSuspicious)
                .map(ModMatch::getName).distinct().collect(Collectors.joining(", "));

        Component headline = mods.isEmpty()
                ? plugin.getMessageManager().get("modlist-none", Map.of(
                        "player", target.getName(),
                        "channels", String.valueOf(profile.getChannels().size())))
                : plugin.getMessageManager().get("modlist-check-complete", Map.of(
                        "player", target.getName(),
                        "count", String.valueOf(mods.size()),
                        "mods", modList));

        Component warning = flaggedNames.isEmpty() ? null
                : plugin.getMessageManager().get("modlist-suspicious", Map.of(
                        "player", target.getName(), "mods", flaggedNames));

        publish(report, target, initiator, headline, warning);
        return report;
    }

    /* ================================================================== */
    /*  output                                                            */
    /* ================================================================== */

    private void publish(CheckReport report, Player target, Player initiator,
                         Component headline, Component warning) {
        MessageManager mm = plugin.getMessageManager();
        mm.broadcastAlerts(headline);
        notifyInitiator(initiator, headline);

        if (warning != null) {
            mm.broadcastAlerts(warning);
            notifyInitiator(initiator, warning);
        }

        plugin.getLogger().info("[CheckHacks] " + report.getType().getDisplayName()
                + " [" + target.getName() + "] " + flatLog(report));

        save(report);
        webhook(report);
        runCommands(report);
    }

    private String flatLog(CheckReport report) {
        StringBuilder sb = new StringBuilder();
        for (CheckEntry entry : report.getEntries()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(entry.getName()).append('=').append(entry.getValue())
              .append('(').append(entry.getOutcome().name()).append(')');
        }
        return sb.toString();
    }

    private void save(CheckReport report) {
        List<Object[]> rows = new ArrayList<>(report.getEntries().size());
        for (CheckEntry entry : report.getEntries())
            rows.add(new Object[]{entry.getKey(), entry.getName(), entry.getValue(), entry.getOutcome().name()});
        long id = plugin.getDatabaseManager().saveClientScan(
                report.getType().getId(), report.getTargetName(), report.getTargetUUID().toString(),
                report.getCheckerName(), report.getReason(), report.hasFlagged(), rows);
        report.setScanId(id);
    }

    private void webhook(CheckReport report) {
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isClientDiscordEnabled()) return;

        String description = cfg.getClientDiscordMessage()
                .replace("&name&",    report.getTargetName())
                .replace("&checker&", report.getCheckerName())
                .replace("&reason&",  report.getReason() == null ? "" : report.getReason())
                .replace("&check&",   report.getType().getDisplayName())
                .replace("&brand&",   brandValue(report))
                .replace("&mods&",    modNames(report))
                .replace("&results&", report.resultText());

        WebhookUtil.sendRaw(cfg.getClientWebhookUrl(), cfg.getClientEmbedColor(),
                description, cfg.isClientDiscordComponentsV2());
    }

    private void runCommands(CheckReport report) {
        ConfigManager cfg = plugin.getConfigManager();
        boolean flagged = report.hasFlagged();

        if (flagged && cfg.isClientCommandIfSuspiciousEnabled())
            dispatch(applyPlaceholders(cfg.getClientCommandIfSuspicious(), report));
        if (!flagged && cfg.isClientCommandIfCleanEnabled())
            dispatch(applyPlaceholders(cfg.getClientCommandIfClean(), report));

        for (ClientCommandRule rule : cfg.getClientCommandRules()) {
            if (rule.getCheck() != null && rule.getCheck() != report.getType()) continue;
            if (matchesRule(rule, report))
                dispatch(applyPlaceholders(rule.getCommand(), report));
        }
    }

    private boolean matchesRule(ClientCommandRule rule, CheckReport report) {
        for (CheckEntry entry : report.getEntries()) {
            if (GlobUtil.matches(rule.getMatch(), entry.getValue())) return true;
            if (GlobUtil.matches(rule.getMatch(), entry.getName()))  return true;
        }
        return false;
    }

    private void dispatch(String command) {
        if (command == null || command.isBlank()) return;
        FoliaScheduler.runGlobal(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    private String applyPlaceholders(String command, CheckReport report) {
        if (command == null) return "";
        String flagged = report.flaggedText();
        String fallback = report.flaggedValue().isBlank() ? flagged : report.flaggedValue();
        if (fallback.isEmpty()) fallback = brandValue(report);

        return command
                .replace("%player%", report.getTargetName())
                .replace("{player}",  report.getTargetName())
                .replace("%check%",   report.getType().getId())
                .replace("{check}",   report.getType().getId())
                .replace("%brand%",   brandValue(report))
                .replace("{brand}",   brandValue(report))
                .replace("%mods%",    modNames(report))
                .replace("{mods}",    modNames(report))
                .replace("%hack%",    fallback)
                .replace("&hack&",    fallback)
                .replace("{hack}",    fallback);
    }

    private String brandValue(CheckReport report) {
        CheckEntry entry = report.getEntry("brand");
        return entry == null ? "" : entry.getValue();
    }

    private String modNames(CheckReport report) {
        return report.getEntries().stream()
                .filter(e -> e.getKey() != null && e.getKey().startsWith("mod:"))
                .map(CheckEntry::getName)
                .collect(Collectors.joining(", "));
    }

    private void notifyInitiator(Player initiator, Component message) {
        if (initiator == null) return;
        boolean getsAlerts = initiator.hasPermission("checkhacks.alerts")
                && plugin.hasAlertsEnabled(initiator.getUniqueId());
        if (!getsAlerts) initiator.sendMessage(message);
    }

    public String checkerName(Player initiator) {
        return initiator != null ? initiator.getName() : "Console";
    }

    private static String slug(String input) {
        return input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    private static String joinChannels(List<String> channels) {
        String joined = String.join(", ", channels);
        return joined.length() > 400 ? joined.substring(0, 397) + "..." : joined;
    }

    /* ================================================================== */
    /*  /checkclient info                                                 */
    /* ================================================================== */

    public void sendProfile(Player viewer, Player target) {
        ClientProfile profile = plugin.getClientDataManager().get(target.getUniqueId());
        if (profile == null) profile = refreshProfile(target);

        MessageManager mm = plugin.getMessageManager();
        viewer.sendMessage(mm.get("profile-header", Map.of("player", target.getName())));
        viewer.sendMessage(mm.get("profile-brand", Map.of(
                "brand", profile.hasBrand() ? profile.getBrand() : "unknown",
                "type", profile.getClientType().getDisplay())));

        List<ModMatch> mods = matchMods(profile.getChannels());
        String modNames = mods.isEmpty() ? "none" : mods.stream()
                .map(m -> m.getName() + (m.isSuspicious() ? " (!)" : ""))
                .distinct().collect(Collectors.joining(", "));
        viewer.sendMessage(mm.get("profile-mods", Map.of("mods", modNames)));
        viewer.sendMessage(mm.get("profile-channels", Map.of(
                "count", String.valueOf(profile.getChannels().size()),
                "channels", joinChannels(profile.getSortedChannels()))));
        viewer.sendMessage(mm.get("profile-updated", Map.of(
                "time", new java.text.SimpleDateFormat("HH:mm:ss").format(new Date(profile.getLastUpdate())))));
    }

    /**
     * Cached profile as a map, safe to call from the web server thread: it only
     * reads the stored snapshot and never touches the live player.
     */
    public Map<String, Object> cachedProfileToMap(UUID uuid) {
        ClientProfile profile = plugin.getClientDataManager().get(uuid);
        if (profile == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("brand", profile.hasBrand() ? profile.getBrand() : "unknown");
        map.put("client_type", profile.getClientType().name());
        map.put("channels", profile.getSortedChannels());
        List<Map<String, Object>> mods = new ArrayList<>();
        for (ModMatch mod : matchMods(profile.getChannels())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", mod.getName());
            m.put("channel", mod.getChannel());
            m.put("suspicious", mod.isSuspicious());
            mods.add(m);
        }
        map.put("mods", mods);
        return map;
    }
}
