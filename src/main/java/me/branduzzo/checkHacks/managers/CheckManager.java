package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.AlertMode;
import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.CheckPlayerData;
import me.branduzzo.checkHacks.CommandRule;
import me.branduzzo.checkHacks.HackDefinition;
import me.branduzzo.checkHacks.HackResult;
import com.github.retrooper.packetevents.util.Vector3i;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import me.branduzzo.checkHacks.utils.PacketSignProbe;
import me.branduzzo.checkHacks.utils.WebhookUtil;
import me.branduzzo.checkHacks.utils.WrappedTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CheckManager {

    private static final String CTRL_KEYBIND = "key.forward";
    private static final int LINES_PER_SIGN = 3;
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final List<String> DEFAULT_BEDROCK_PREFIXES = List.of(".", "*");

    private static volatile boolean geyserResolved;
    private static volatile boolean geyserAvailable;
    private static volatile boolean floodgateResolved;
    private static volatile boolean floodgateAvailable;

    private final CheckHacksPlugin plugin;
    private final Map<UUID, CheckPlayerData> activeChecks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAutoCheck = new ConcurrentHashMap<>();

    public CheckManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isChecking(UUID uuid) { return activeChecks.containsKey(uuid); }

    public boolean canAutoCheck(UUID uuid) {
        long cooldownMs = plugin.getConfigManager().getFlagCooldownHours() * 3_600_000L;
        return System.currentTimeMillis() - lastAutoCheck.getOrDefault(uuid, 0L) >= cooldownMs;
    }

    public void startCheck(Player target, Player initiator,
                           List<HackDefinition> hacks, boolean autoCheck, String reason) {
        if (hacks == null || hacks.isEmpty()) return;
        UUID uuid = target.getUniqueId();

        if (activeChecks.containsKey(uuid)) {
            if (initiator != null) {
                initiator.sendMessage(plugin.getMessageManager().get("already-checking",
                        Map.of("player", target.getName())));
            }
            return;
        }

        if (isBedrockPlayer(target)) {
            Component msg = plugin.getMessageManager().get("bedrock-skip",
                    Map.of("player", target.getName()));
            if (initiator != null) initiator.sendMessage(msg);
            else plugin.getMessageManager().broadcastAlerts(msg, false);
            return;
        }

        if (autoCheck && reason != null && reason.startsWith("Anticheat")) {
            lastAutoCheck.put(uuid, System.currentTimeMillis());
        }

        List<HackDefinition> probe = new ArrayList<>(hacks);
        List<List<HackDefinition>> batches = buildBatches(probe);
        CheckPlayerData data = new CheckPlayerData(uuid,
                initiator != null ? initiator.getUniqueId() : null,
                new ArrayList<>(hacks), batches, autoCheck, reason, false);
        activeChecks.put(uuid, data);

        if (initiator != null) {
            initiator.sendMessage(plugin.getMessageManager().get("check-started",
                    Map.of("player", target.getName())));
        }

        if (batches.isEmpty()) {
            finishCheck(uuid);
            return;
        }

        FoliaScheduler.runAtEntity(plugin, target, () -> processBatch(target, data));
    }

    private List<List<HackDefinition>> buildBatches(List<HackDefinition> hacks) {
        List<List<HackDefinition>> batches = new ArrayList<>();
        for (int i = 0; i < hacks.size(); i += LINES_PER_SIGN) {
            batches.add(new ArrayList<>(hacks.subList(i, Math.min(i + LINES_PER_SIGN, hacks.size()))));
        }
        return batches;
    }

    private void processBatch(Player target, CheckPlayerData data) {
        UUID uuid = target.getUniqueId();
        List<HackDefinition> batch = data.getCurrentBatchHacks();

        Location signLoc = PacketSignProbe.findProbeLocation(target);
        List<Component> lines = new ArrayList<>(4);
        for (int i = 0; i < LINES_PER_SIGN; i++) {
            lines.add(i < batch.size() ? batch.get(i).getComponent() : Component.empty());
        }
        lines.add(Component.keybind(CTRL_KEYBIND));

        data.setSignLocation(signLoc);
        data.setAwaitingResponse(true);
        PacketSignProbe.open(target, signLoc, lines);
        PacketSignProbe.close(target);

        WrappedTask timeout = FoliaScheduler.runAtEntityLater(plugin, target, () -> {
            CheckPlayerData current = activeChecks.get(uuid);
            if (current != data || !current.beginResponse()) return;
            PacketSignProbe.restore(target, signLoc);
            current.setSignLocation(null);
            for (HackDefinition hack : batch) current.getResults().put(hack.getId(), HackResult.PROTECTED);
            current.incrementBatch();
            scheduleNextOrFinish(uuid);
        }, plugin.getConfigManager().getTimeoutTicks());

        data.setSignTimeoutTask(timeout);
    }

    public boolean acceptSignUpdate(Player target, Vector3i position, String[] lines) {
        return acceptSignUpdate(target, position.getX(), position.getY(), position.getZ(), lines);
    }

    public boolean acceptSignUpdate(Player target, int x, int y, int z, String[] lines) {
        CheckPlayerData data = activeChecks.get(target.getUniqueId());
        if (data == null) return false;
        Location loc = data.getSignLocation();
        if (loc == null) return false;
        if (loc.getBlockX() != x || loc.getBlockY() != y || loc.getBlockZ() != z) return false;
        if (!data.beginResponse()) return false;
        FoliaScheduler.runAtEntity(plugin, target, () -> handlePacketResponse(target, data, lines));
        return true;
    }

    private void handlePacketResponse(Player target, CheckPlayerData data, String[] lines) {
        UUID uuid = target.getUniqueId();
        if (activeChecks.get(uuid) != data) return;

        if (data.getSignTimeoutTask() != null) data.getSignTimeoutTask().cancel();
        Location loc = data.getSignLocation();
        if (loc != null) PacketSignProbe.restore(target, loc);
        data.setSignLocation(null);

        List<HackDefinition> batch = data.getCurrentBatchHacks();
        String ctrlResp = lines.length > 3 ? lines[3].strip() : "";
        boolean exploitPreventer = ctrlResp.equalsIgnoreCase(CTRL_KEYBIND);

        StringBuilder log = new StringBuilder("[CheckHacks] Batch ").append(data.getCurrentBatch())
                .append(" from ").append(target.getName());
        if (exploitPreventer) log.append(" [ExploitPreventer DETECTED]");
        for (int i = 0; i < batch.size(); i++) {
            HackDefinition hack = batch.get(i);
            String response = i < lines.length ? lines[i].strip() : "";
            HackResult result = evaluateResponse(hack, response, exploitPreventer);
            data.getResults().put(hack.getId(), result);
            log.append(' ').append(hack.getDisplayName()).append('=').append(result)
                    .append(" ('").append(response).append("')");
        }
        plugin.getLogger().info(log.toString());

        data.incrementBatch();
        scheduleNextOrFinish(uuid);
    }

    private void scheduleNextOrFinish(UUID uuid) {
        CheckPlayerData data = activeChecks.get(uuid);
        if (data == null) return;
        if (data.hasMoreBatches()) {
            Player target = Bukkit.getPlayer(uuid);
            long delay = plugin.getConfigManager().getBetweenSignTicks();
            Runnable next = () -> {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null && online.isOnline()) processBatch(online, data);
                else finishCheck(uuid);
            };
            if (target != null) FoliaScheduler.runAtEntityLater(plugin, target, next, Math.max(1L, delay));
            else FoliaScheduler.runGlobalLater(plugin, next, Math.max(1L, delay));
        } else {
            finishCheck(uuid);
        }
    }

    private HackResult evaluateResponse(HackDefinition hack, String response, boolean exploitPreventer) {
        if (response.isEmpty()) return HackResult.NOT_DETECTED;

        String lowerKey = hack.getLowerKey();
        int keyLength = lowerKey.length();
        if (response.length() == keyLength + 1
                && response.regionMatches(true, 0, lowerKey, 0, keyLength)
                && Character.isLetter(response.charAt(keyLength))) {
            return HackResult.NOT_DETECTED;
        }

        return switch (hack.getMode()) {
            case METEOR -> {
                if (response.equalsIgnoreCase(hack.getKey())) yield HackResult.DETECTED;
                if (regionStartsWith(response, hack.getLowerFallback())) yield HackResult.NOT_DETECTED;
                yield HackResult.DETECTED;
            }
            case TRANSLATE -> {
                if (regionStartsWith(response, hack.getLowerFallback())) yield HackResult.NOT_DETECTED;
                if (response.equalsIgnoreCase(hack.getKey())) yield HackResult.PROTECTED;
                yield HackResult.DETECTED;
            }
            case KEYBIND -> {
                if (exploitPreventer && response.equalsIgnoreCase(hack.getKey())) yield HackResult.PROTECTED;
                if (response.equalsIgnoreCase(hack.getKey())) yield HackResult.NOT_DETECTED;
                yield HackResult.DETECTED;
            }
        };
    }

    private static boolean regionStartsWith(String value, String prefix) {
        return prefix.length() <= value.length() && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private void finishCheck(UUID uuid) {
        CheckPlayerData data = activeChecks.remove(uuid);
        if (data == null) return;

        Player targetPlayer = Bukkit.getPlayer(uuid);
        String targetName = targetPlayer != null ? targetPlayer.getName() : uuid.toString();
        String targetUUID = uuid.toString();
        String checkerName = data.getInitiatorUUID() != null
                ? Optional.ofNullable(Bukkit.getPlayer(data.getInitiatorUUID()))
                .map(Player::getName).orElse("Console")
                : (data.isAutoCheck() ? "AutoCheck" : "Console");

        List<HackDefinition> allHacks = data.getAllHacks();
        Map<String, HackResult> results = data.getResults();
        boolean anyDetected = false;
        boolean anyProtected = false;
        boolean allClean = true;
        StringBuilder resultText = new StringBuilder();

        Component header = plugin.getMessageManager().get("check-complete", Map.of("player", targetName));
        plugin.getMessageManager().broadcastAlerts(header, false);
        notifyInitiator(data, header, false);

        for (HackDefinition hack : allHacks) {
            HackResult result = results.getOrDefault(hack.getId(), HackResult.SKIPPED);
            if (result == HackResult.DETECTED) { anyDetected = true; allClean = false; }
            if (result == HackResult.PROTECTED) { anyProtected = true; allClean = false; }
            if (result == HackResult.SKIPPED) allClean = false;
            resultText.append(hack.getDisplayName()).append(": ").append(result.name()).append("\n");

            String color = switch (result) {
                case DETECTED -> "<red>";
                case NOT_DETECTED -> "<green>";
                case PROTECTED -> "<yellow>";
                case SKIPPED -> "<gray>";
            };
            Component line = MM.deserialize(plugin.getConfigManager().getPrefix()
                    + "  <white>" + hack.getDisplayName() + ": " + color + result.name());
            boolean flagged = result == HackResult.DETECTED || result == HackResult.PROTECTED;
            plugin.getMessageManager().broadcastAlerts(line, flagged);
            notifyInitiator(data, line, flagged);
        }

        List<Object[]> dbRows = new ArrayList<>(allHacks.size());
        for (HackDefinition hack : allHacks) {
            dbRows.add(new Object[]{hack.getId(), hack.getDisplayName(),
                    results.getOrDefault(hack.getId(), HackResult.SKIPPED).name()});
        }
        List<Object[]> rows = List.copyOf(dbRows);
        boolean detectedFlag = anyDetected;
        String reason = data.getReason();
        FoliaScheduler.runAsync(plugin, () -> plugin.getDatabaseManager()
                .saveScanWithResults("hack", targetName, targetUUID, checkerName, reason, detectedFlag, rows));

        ConfigManager cfg = plugin.getConfigManager();

        if (cfg.isDiscordEnabled()) {
            String hacksChecked = allHacks.stream()
                    .map(HackDefinition::getDisplayName)
                    .reduce((a, b) -> a + ", " + b).orElse("none");
            WebhookUtil.sendResult(cfg.getWebhookUrl(), cfg.getEmbedColor(),
                    cfg.getDiscordMessage(), targetName, checkerName,
                    reason, hacksChecked, resultText.toString().trim(),
                    cfg.isDiscordUseComponentsV2());
        }

        if (cfg.isDoubleCheckEnabled() && !data.isConfirmScan() && (anyDetected || anyProtected)) {
            List<HackDefinition> flagged = new ArrayList<>();
            for (HackDefinition hack : allHacks) {
                HackResult result = results.getOrDefault(hack.getId(), HackResult.SKIPPED);
                if (result == HackResult.DETECTED || result == HackResult.PROTECTED) flagged.add(hack);
            }
            if (!flagged.isEmpty()) {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null && online.isOnline() && startConfirmScan(online, data, flagged)) return;
            }
        }

        executeResultActions(data, targetName, anyDetected, anyProtected, allClean, results, allHacks);
    }

    private boolean startConfirmScan(Player target, CheckPlayerData original, List<HackDefinition> flagged) {
        if (flagged.isEmpty()) return false;

        List<List<HackDefinition>> batches = buildBatches(flagged);
        if (batches.isEmpty()) return false;

        CheckPlayerData data = new CheckPlayerData(target.getUniqueId(),
                original.getInitiatorUUID(), new ArrayList<>(flagged), batches,
                false, original.getReason(), true);
        activeChecks.put(target.getUniqueId(), data);

        Component msg = plugin.getMessageManager().get("doublecheck", Map.of("player", target.getName()));
        plugin.getMessageManager().broadcastAlerts(msg, false);
        notifyInitiator(data, msg, false);

        FoliaScheduler.runAtEntity(plugin, target, () -> processBatch(target, data));
        return true;
    }

    private void executeResultActions(CheckPlayerData data, String targetName,
                                      boolean anyDetected, boolean anyProtected, boolean allClean,
                                      Map<String, HackResult> results, List<HackDefinition> allHacks) {
        if (data.isConfirmScan() && allClean) return;

        ConfigManager cfg = plugin.getConfigManager();
        final String name = targetName;

        String detectedList = allHacks.stream()
                .filter(hack -> results.getOrDefault(hack.getId(), HackResult.SKIPPED) == HackResult.DETECTED)
                .map(HackDefinition::getDisplayName)
                .reduce((a, b) -> a + ", " + b).orElse("");
        String protectedList = allHacks.stream()
                .filter(hack -> results.getOrDefault(hack.getId(), HackResult.SKIPPED) == HackResult.PROTECTED)
                .map(HackDefinition::getDisplayName)
                .reduce((a, b) -> a + ", " + b).orElse("");
        String flaggedList = allHacks.stream()
                .filter(hack -> {
                    HackResult result = results.getOrDefault(hack.getId(), HackResult.SKIPPED);
                    return result == HackResult.DETECTED || result == HackResult.PROTECTED;
                })
                .map(HackDefinition::getDisplayName)
                .reduce((a, b) -> a + ", " + b).orElse("");

        if (anyDetected && cfg.isCommandIfPositiveEnabled()) {
            String command = applyCommandPlaceholders(cfg.getPositiveCommand(), name,
                    detectedList.isEmpty() ? flaggedList : detectedList);
            dispatch(command);
        }
        if (anyProtected && !anyDetected && cfg.isCommandIfProtectedEnabled()) {
            String command = applyCommandPlaceholders(cfg.getProtectedCommand(), name,
                    protectedList.isEmpty() ? flaggedList : protectedList);
            dispatch(command);
        }
        if (allClean && cfg.isCommandIfCleanEnabled()) {
            dispatch(applyCommandPlaceholders(cfg.getCleanCommand(), name, ""));
        }

        for (HackDefinition hack : allHacks) {
            HackResult result = results.getOrDefault(hack.getId(), HackResult.SKIPPED);
            for (CommandRule rule : cfg.getRulesForHack(hack.getId())) {
                if (rule.matchesResult(result)) {
                    dispatch(applyCommandPlaceholders(rule.getCommand(), name, hack.getDisplayName()));
                }
            }
        }
    }

    private void dispatch(String command) {
        if (command == null || command.isBlank()) return;
        FoliaScheduler.runGlobal(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    private String applyCommandPlaceholders(String command, String playerName, String hackName) {
        return command
                .replace("%player%", playerName)
                .replace("%hack%", hackName)
                .replace("&hack&", hackName)
                .replace("{hack}", hackName)
                .replace("{player}", playerName);
    }

    private void notifyInitiator(CheckPlayerData data, Component msg, boolean flagged) {
        if (data.getInitiatorUUID() == null) return;
        Player initiator = Bukkit.getPlayer(data.getInitiatorUUID());
        if (initiator == null || !initiator.isOnline()) return;
        boolean permission = initiator.hasPermission("checkhacks.alerts");
        if (!permission) {
            initiator.sendMessage(msg);
            return;
        }
        AlertMode mode = plugin.getAlertMode(initiator.getUniqueId());
        if (mode == AlertMode.OFF) {
            initiator.sendMessage(msg);
            return;
        }
        if (mode == AlertMode.ONLY_DETECTED && !flagged) return;
    }

    private void restoreCurrentSign(CheckPlayerData data) {
        Location loc = data.getSignLocation();
        if (loc == null) return;
        Player player = Bukkit.getPlayer(data.getTargetUUID());
        if (player != null && player.isOnline()) PacketSignProbe.restore(player, loc);
        data.setSignLocation(null);
        data.setAwaitingResponse(false);
    }

    public void forget(UUID uuid) {
        lastAutoCheck.remove(uuid);
        CheckPlayerData data = activeChecks.remove(uuid);
        if (data == null) return;
        if (data.getSignTimeoutTask() != null) data.getSignTimeoutTask().cancel();
        restoreCurrentSign(data);
    }

    public void cleanup() {
        for (CheckPlayerData data : activeChecks.values()) {
            if (data.getSignTimeoutTask() != null) data.getSignTimeoutTask().cancel();
            restoreCurrentSign(data);
        }
        activeChecks.clear();
        lastAutoCheck.clear();
    }

    private boolean isBedrockPlayer(Player target) {
        ConfigManager cfg = plugin.getConfigManager();
        if (cfg.isBedrockEnabled()) {
            String name = target.getName();
            for (String prefix : cfg.getBedrockPrefixes().isEmpty()
                    ? DEFAULT_BEDROCK_PREFIXES : cfg.getBedrockPrefixes()) {
                String trimmed = prefix.trim();
                if (!trimmed.isEmpty() && name.startsWith(trimmed)) return true;
            }
        }
        return isGeyserPlayer(target.getUniqueId()) || isFloodgatePlayer(target.getUniqueId());
    }

    private static boolean isGeyserPlayer(UUID uuid) {
        if (!geyserResolved) {
            geyserResolved = true;
            try {
                Class.forName("org.geysermc.geyser.api.GeyserApi").getMethod("api").invoke(null);
                geyserAvailable = true;
            } catch (Throwable t) {
                geyserAvailable = false;
            }
        }
        if (!geyserAvailable) return false;
        try {
            Class<?> type = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Object api = type.getMethod("api").invoke(null);
            return (Boolean) type.getMethod("isBedrockPlayer", UUID.class).invoke(api, uuid);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isFloodgatePlayer(UUID uuid) {
        if (!floodgateResolved) {
            floodgateResolved = true;
            try {
                Class.forName("org.geysermc.floodgate.api.FloodgateApi")
                        .getMethod("getInstance").invoke(null);
                floodgateAvailable = true;
            } catch (Throwable t) {
                floodgateAvailable = false;
            }
        }
        if (!floodgateAvailable) return false;
        try {
            Class<?> type = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = type.getMethod("getInstance").invoke(null);
            return (Boolean) type.getMethod("isFloodgatePlayer", UUID.class).invoke(api, uuid);
        } catch (Throwable t) {
            return false;
        }
    }
}
