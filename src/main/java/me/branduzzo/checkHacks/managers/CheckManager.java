package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.*;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import me.branduzzo.checkHacks.utils.SignUtil;
import me.branduzzo.checkHacks.utils.WebhookUtil;
import me.branduzzo.checkHacks.utils.SignPlacement;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CheckManager {

    private static final String CTRL_KEYBIND  = "key.forward";
    private static final int LINES_PER_SIGN = 3;
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final List<String> DEFAULT_BEDROCK_PREFIXES = List.of(".", "*");

    private static volatile boolean geyserResolved;
    private static volatile boolean geyserAvailable;
    private static volatile boolean floodgateResolved;
    private static volatile boolean floodgateAvailable;

    private final CheckHacksPlugin plugin;
    private final Map<UUID, CheckPlayerData> activeChecks  = new ConcurrentHashMap<>();
    private final Map<UUID, Long>            lastAutoCheck = new ConcurrentHashMap<>();

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
        UUID uuid = target.getUniqueId();

        if (activeChecks.containsKey(uuid) || plugin.getLangCheckManager().isChecking(uuid)) {
            if (initiator != null)
                initiator.sendMessage(plugin.getMessageManager().get("already-checking",
                        Map.of("player", target.getName())));
            return;
        }

        if (isBedrockPlayer(target)) {
            Component msg = plugin.getMessageManager().get("bedrock-skip",
                    Map.of("player", target.getName()));
            if (initiator != null) initiator.sendMessage(msg);
            else plugin.getMessageManager().broadcastAlerts(msg);
            return;
        }

        if (autoCheck) lastAutoCheck.put(uuid, System.currentTimeMillis());

        List<List<HackDefinition>> batches = buildBatches(hacks, LINES_PER_SIGN);
        if (batches.isEmpty()) return;

        CheckPlayerData data = new CheckPlayerData(uuid,
                initiator != null ? initiator.getUniqueId() : null,
                batches, autoCheck, reason);
        activeChecks.put(uuid, data);

        if (initiator != null)
            initiator.sendMessage(plugin.getMessageManager().get("check-started",
                    Map.of("player", target.getName())));

        FoliaScheduler.runAtEntity(plugin, target, () -> processBatch(target, data));
    }

    private List<List<HackDefinition>> buildBatches(List<HackDefinition> hacks, int slots) {
        List<List<HackDefinition>> batches = new ArrayList<>();
        for (int i = 0; i < hacks.size(); i += slots)
            batches.add(new ArrayList<>(hacks.subList(i, Math.min(i + slots, hacks.size()))));
        return batches;
    }

    private void processBatch(Player target, CheckPlayerData data) {
        UUID uuid = target.getUniqueId();
        // A delayed callback must still own both this scan and its current lease.
        if (activeChecks.get(uuid) != data || !target.isOnline()) return;
        List<HackDefinition> batch = data.getCurrentBatchHacks();
        SignPlacement.Lease lease = SignPlacement.place(target,
                plugin.getConfigManager().getSignPlacementSettings(), plugin.getLogger()::warning);
        if (lease == null) {
            finishCheck(uuid);
            return;
        }
        Location signLoc = lease.location();
        Sign sign = lease.sign();
        data.setSignLease(lease);
        var front = sign.getSide(Side.FRONT);
        for (int row = 0; row < LINES_PER_SIGN; row++)
            front.line(row, row < batch.size() ? buildComponent(batch.get(row)) : Component.empty());
        front.line(3, Component.keybind(CTRL_KEYBIND));
        sign.update(true, false);
        SignUtil.setAllowedEditor(signLoc, uuid, plugin);
        data.setSignTimeoutTask(FoliaScheduler.runAtEntityLater(plugin, target, () -> {
            if (activeChecks.get(uuid) != data || data.getSignLease() != lease) return;
            restoreCurrentSign(data);
            for (HackDefinition hack : batch) data.getResults().put(hack.getId(), HackResult.PROTECTED);
            data.incrementBatch();
            scheduleNextOrFinish(uuid);
        }, plugin.getConfigManager().getTimeoutTicks()));
        FoliaScheduler.runAtEntityLater(plugin, target, () -> {
            if (activeChecks.get(uuid) != data || data.getSignLease() != lease || !target.isOnline()) return;
            if (!SignUtil.sendSignSnapshot(target, signLoc, sign, plugin)) {
                if (data.getSignTimeoutTask() != null) data.getSignTimeoutTask().cancel();
                restoreCurrentSign(data);
                for (HackDefinition hack : batch) data.getResults().put(hack.getId(), HackResult.SKIPPED);
                data.incrementBatch();
                scheduleNextOrFinish(uuid);
                return;
            }
            FoliaScheduler.runAtEntityLater(plugin, target, () -> {
                if (activeChecks.get(uuid) != data || data.getSignLease() != lease || !target.isOnline()) return;
                data.setAcceptingResponse(true);
                SignUtil.sendOpenSignPacket(target, signLoc, plugin);
                target.sendBlockChange(signLoc, Material.AIR.createBlockData());
            }, 1L);
        }, 1L);
    }

    private Component buildComponent(HackDefinition hack) {
        return switch (hack.getMode()) {
            case METEOR, TRANSLATE -> Component.translatable(hack.getKey(), hack.getFallback());
            case KEYBIND           -> Component.keybind(hack.getKey());
        };
    }

    /** Accepts only the current editor's block while its first response is pending. */
    public boolean isExpectedSign(UUID uuid, Location location) {
        CheckPlayerData data = activeChecks.get(uuid);
        return data != null && data.isAcceptingResponse() && SignUtil.isSameBlock(data.getSignLocation(), location);
    }

    public void handleBatchResponse(Player target, String[] lines) {
        UUID uuid = target.getUniqueId();
        CheckPlayerData data = activeChecks.get(uuid);
        if (data == null || !data.isAcceptingResponse()) return;

        data.setAcceptingResponse(false);
        if (data.getSignTimeoutTask() != null) data.getSignTimeoutTask().cancel();
        String[] reply = lines == null ? null : lines.clone();
        if (activeChecks.get(uuid) == data) processBatchResponse(target, data, reply);
    }

    private void processBatchResponse(Player target, CheckPlayerData data, String[] lines) {
        UUID uuid = target.getUniqueId();
        restoreCurrentSign(data);
        List<HackDefinition> batch = data.getCurrentBatchHacks();
        String ctrlResp = lines != null && lines.length > 3 ? lines[3].strip() : "";
        boolean exploitPreventer = ctrlResp.equalsIgnoreCase(CTRL_KEYBIND);
        StringBuilder log = new StringBuilder("[CheckHacks] Batch ").append(data.getCurrentBatch())
                .append(" from ").append(target.getName());
        if (exploitPreventer) log.append(" [ExploitPreventer DETECTED]");
        for (int index = 0; index < batch.size(); index++) {
            HackDefinition hack = batch.get(index);
            String response = lines != null && index < lines.length ? lines[index].strip() : "";
            HackResult result = evaluateResponse(hack, response, exploitPreventer);
            data.getResults().put(hack.getId(), result);
            log.append(' ').append(hack.getDisplayName()).append('=').append(result).append(" ('").append(response).append("')");
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
                Player t = Bukkit.getPlayer(uuid);
                if (activeChecks.get(uuid) != data) return;
                if (t != null && t.isOnline()) processBatch(t, data);
                else cancelCheck(uuid);
            };
            if (target != null) FoliaScheduler.runAtEntityLater(plugin, target, next, delay);
            else                FoliaScheduler.runGlobalLater(plugin, next, delay);
        } else {
            finishCheck(uuid);
        }
    }

    private HackResult evaluateResponse(HackDefinition hack, String resp, boolean exploitPreventer) {
        if (resp.isEmpty()) return HackResult.NOT_DETECTED;

        String lowerKey = hack.getLowerKey();
        int keyLen = lowerKey.length();
        if (resp.length() == keyLen + 1
                && resp.regionMatches(true, 0, lowerKey, 0, keyLen)
                && Character.isLetter(resp.charAt(keyLen))) {
            return HackResult.NOT_DETECTED;
        }

        return switch (hack.getMode()) {
            case METEOR -> {
                if (resp.equalsIgnoreCase(hack.getKey()))                       yield HackResult.DETECTED;
                if (regionStartsWith(resp, hack.getLowerFallback()))            yield HackResult.NOT_DETECTED;
                yield HackResult.DETECTED;
            }
            case TRANSLATE -> {
                if (regionStartsWith(resp, hack.getLowerFallback()))            yield HackResult.NOT_DETECTED;
                if (resp.equalsIgnoreCase(hack.getKey()))                       yield HackResult.PROTECTED;
                yield HackResult.DETECTED;
            }
            case KEYBIND -> {
                if (exploitPreventer && resp.equalsIgnoreCase(hack.getKey()))   yield HackResult.PROTECTED;
                if (resp.equalsIgnoreCase(hack.getKey()))                       yield HackResult.NOT_DETECTED;
                yield HackResult.DETECTED;
            }
        };
    }

    private static boolean regionStartsWith(String s, String prefix) {
        return prefix.length() <= s.length() && s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private void finishCheck(UUID uuid) {
        CheckPlayerData data = activeChecks.remove(uuid);
        if (data == null) return;

        Player targetPlayer = Bukkit.getPlayer(uuid);
        String targetName   = targetPlayer != null ? targetPlayer.getName() : uuid.toString();
        String targetUUID   = uuid.toString();
        String checkerName  = data.getInitiatorUUID() != null
                ? Optional.ofNullable(Bukkit.getPlayer(data.getInitiatorUUID()))
                .map(Player::getName).orElse("Console")
                : (data.isAutoCheck() ? "AutoCheck" : "Console");

        List<HackDefinition> allHacks = data.getBatches().stream().flatMap(List::stream).toList();
        Map<String, HackResult> results = data.getResults();
        boolean anyDetected  = false;
        boolean anyProtected = false;
        boolean allClean     = true;
        StringBuilder resultText = new StringBuilder();

        Component header = plugin.getMessageManager().get("check-complete", Map.of("player", targetName));
        plugin.getMessageManager().broadcastAlerts(header);
        notifyInitiator(data, header);

        for (HackDefinition hack : allHacks) {
            HackResult r = results.getOrDefault(hack.getId(), HackResult.SKIPPED);
            if (r == HackResult.DETECTED)  { anyDetected = true;  allClean = false; }
            if (r == HackResult.PROTECTED) { anyProtected = true; allClean = false; }
            if (r == HackResult.SKIPPED)     allClean = false;
            resultText.append(hack.getDisplayName()).append(": ").append(r.name()).append("\n");

            String color = switch (r) {
                case DETECTED     -> "<red>";
                case NOT_DETECTED -> "<green>";
                case PROTECTED    -> "<yellow>";
                case SKIPPED      -> "<gray>";
            };
            Component line = MM.deserialize(
                    plugin.getConfigManager().getPrefix()
                            + "  <white>" + hack.getDisplayName() + ": " + color + r.name());
            plugin.getMessageManager().broadcastAlerts(line);
            notifyInitiator(data, line);
        }

        List<Object[]> dbRows = new ArrayList<>(allHacks.size());
        for (HackDefinition hack : allHacks)
            dbRows.add(new Object[]{hack.getId(), hack.getDisplayName(),
                    results.getOrDefault(hack.getId(), HackResult.SKIPPED).name()});
        plugin.getDatabaseManager().saveScanWithResults(
                "hack", targetName, targetUUID, checkerName, data.getReason(), anyDetected, dbRows);

        ConfigManager cfg = plugin.getConfigManager();

        if (cfg.isDiscordEnabled()) {
            String hacksChecked = allHacks.stream()
                    .map(HackDefinition::getDisplayName)
                    .reduce((a, b) -> a + ", " + b).orElse("none");
            WebhookUtil.sendResult(cfg.getWebhookUrl(), cfg.getEmbedColor(),
                    cfg.getDiscordMessage(), targetName, checkerName,
                    data.getReason(), hacksChecked, resultText.toString().trim(),
                    cfg.isDiscordUseComponentsV2());
        }

        if (cfg.isDoubleCheckEnabled() && !data.isConfirmScan() && (anyDetected || anyProtected)) {
            List<HackDefinition> flagged = new ArrayList<>();
            for (HackDefinition hack : allHacks) {
                HackResult r = results.getOrDefault(hack.getId(), HackResult.SKIPPED);
                if (r == HackResult.DETECTED || r == HackResult.PROTECTED) flagged.add(hack);
            }
            if (!flagged.isEmpty()) {
                Player tp = Bukkit.getPlayer(uuid);
                if (tp != null && tp.isOnline() && startConfirmScan(tp, data, flagged)) return;
            }
        }

        executeResultActions(data, targetName, anyDetected, anyProtected, allClean, results, allHacks);
    }

    private boolean startConfirmScan(Player target, CheckPlayerData original, List<HackDefinition> flagged) {
        List<List<HackDefinition>> batches = buildBatches(flagged, LINES_PER_SIGN);
        if (batches.isEmpty()) return false;

        CheckPlayerData data = new CheckPlayerData(target.getUniqueId(),
                original.getInitiatorUUID(), batches, false, original.getReason(), true);
        activeChecks.put(target.getUniqueId(), data);

        Component msg = plugin.getMessageManager().get("doublecheck", Map.of("player", target.getName()));
        plugin.getMessageManager().broadcastAlerts(msg);
        notifyInitiator(data, msg);

        FoliaScheduler.runAtEntity(plugin, target, () -> processBatch(target, data));
        return true;
    }

    private void executeResultActions(CheckPlayerData data, String targetName,
                                      boolean anyDetected, boolean anyProtected, boolean allClean,
                                      Map<String, HackResult> results, List<HackDefinition> allHacks) {
        if (data.isConfirmScan() && allClean) return;

        ConfigManager cfg = plugin.getConfigManager();
        final String tn = targetName;

        String detectedList = allHacks.stream()
                .filter(h -> results.getOrDefault(h.getId(), HackResult.SKIPPED) == HackResult.DETECTED)
                .map(HackDefinition::getDisplayName)
                .reduce((a, b) -> a + ", " + b).orElse("");
        String protectedList = allHacks.stream()
                .filter(h -> results.getOrDefault(h.getId(), HackResult.SKIPPED) == HackResult.PROTECTED)
                .map(HackDefinition::getDisplayName)
                .reduce((a, b) -> a + ", " + b).orElse("");
        String detectedOrProtectedList = allHacks.stream()
                .filter(h -> {
                    HackResult r = results.getOrDefault(h.getId(), HackResult.SKIPPED);
                    return r == HackResult.DETECTED || r == HackResult.PROTECTED;
                })
                .map(HackDefinition::getDisplayName)
                .reduce((a, b) -> a + ", " + b).orElse("");

        if (anyDetected && cfg.isCommandIfPositiveEnabled()) {
            String cmd = applyCommandPlaceholders(cfg.getPositiveCommand(), tn, detectedList.isEmpty() ? detectedOrProtectedList : detectedList);
            FoliaScheduler.runGlobal(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        }
        if (anyProtected && !anyDetected && cfg.isCommandIfProtectedEnabled()) {
            String cmd = applyCommandPlaceholders(cfg.getProtectedCommand(), tn, protectedList.isEmpty() ? detectedOrProtectedList : protectedList);
            FoliaScheduler.runGlobal(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        }
        if (allClean && cfg.isCommandIfCleanEnabled()) {
            String cmd = applyCommandPlaceholders(cfg.getCleanCommand(), tn, "");
            FoliaScheduler.runGlobal(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        }

        for (HackDefinition hack : allHacks) {
            HackResult r = results.getOrDefault(hack.getId(), HackResult.SKIPPED);
            for (CommandRule rule : cfg.getRulesForHack(hack.getId())) {
                if (rule.getResult() == r) {
                    List<String> commands = rule.getCommands().stream()
                            .map(command -> applyCommandPlaceholders(command, tn, hack.getDisplayName())).toList();
                    // One scheduled action preserves the configured list order on Paper and Folia.
                    FoliaScheduler.runGlobal(plugin, () -> {
                        for (String command : commands)
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                    });
                }
            }
        }
    }

    private String applyCommandPlaceholders(String command, String playerName, String hackName) {
        return command
                .replace("%player%", playerName)
                .replace("%hack%", hackName)
                .replace("&hack&", hackName)
                .replace("{hack}", hackName)
                .replace("{player}", playerName);
    }

    private void notifyInitiator(CheckPlayerData data, Component msg) {
        if (data.getInitiatorUUID() == null) return;
        Player ini = Bukkit.getPlayer(data.getInitiatorUUID());
        if (ini == null || !ini.isOnline()) return;
        boolean gets = ini.hasPermission("checkhacks.alerts") && plugin.hasAlertsEnabled(ini.getUniqueId());
        if (!gets) ini.sendMessage(msg);
    }

    private void restoreCurrentSign(CheckPlayerData data) {
        // Detach first so delayed entity callbacks cannot reuse this lease;
        // actual block restoration belongs to the sign's current region.
        data.setAcceptingResponse(false);
        SignPlacement.Lease lease = data.getSignLease();
        data.setSignLease(null);
        if (lease == null) return;
        Runnable restore = lease::restore;
        if ((!FoliaScheduler.isFolia() && Bukkit.isPrimaryThread())
                || (FoliaScheduler.isFolia() && Bukkit.getServer().isOwnedByCurrentRegion(lease.location()))) restore.run();
        else {
            try { FoliaScheduler.runAtLocation(plugin, lease.location(), restore); }
            catch (RuntimeException ex) {
                plugin.getLogger().severe("Cannot restore temporary sign at " + lease.location()
                        + ": " + ex.getMessage());
            }
        }
    }

    /** Aborts without recording a completed scan or executing result actions. */
    public void cancelCheck(UUID uuid) {
        CheckPlayerData data = activeChecks.remove(uuid);
        if (data == null) return;
        if (data.getSignTimeoutTask() != null) data.getSignTimeoutTask().cancel();
        restoreCurrentSign(data);
    }

    public void cleanup() {
        for (CheckPlayerData d : activeChecks.values()) {
            if (d.getSignTimeoutTask() != null) d.getSignTimeoutTask().cancel();
            restoreCurrentSign(d);
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
                String p = prefix.trim();
                if (!p.isEmpty() && name.startsWith(p)) return true;
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
            Class<?> c = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Object api = c.getMethod("api").invoke(null);
            return (Boolean) c.getMethod("isBedrockPlayer", UUID.class).invoke(api, uuid);
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
            Class<?> c = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = c.getMethod("getInstance").invoke(null);
            return (Boolean) c.getMethod("isFloodgatePlayer", UUID.class).invoke(api, uuid);
        } catch (Throwable t) {
            return false;
        }
    }
}
