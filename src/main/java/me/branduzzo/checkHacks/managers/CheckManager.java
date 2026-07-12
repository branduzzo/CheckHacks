package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.*;
import me.branduzzo.checkHacks.model.CheckOutcome;
import me.branduzzo.checkHacks.session.SignSession;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import me.branduzzo.checkHacks.utils.WebhookUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CheckManager {

    private static final String CTRL_KEYBIND  = "key.forward";
    private static final int    LINES_PER_SIGN = 3;

    private final CheckHacksPlugin plugin;
    private final Map<UUID, CheckPlayerData> activeChecks  = new ConcurrentHashMap<>();
    private final Map<UUID, Long>            lastAutoCheck = new ConcurrentHashMap<>();

    public CheckManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isChecking(UUID uuid) { return activeChecks.containsKey(uuid); }

    public Location getActiveSignLocation(UUID uuid) {
        CheckPlayerData data = activeChecks.get(uuid);
        if (data == null || data.getSignSession() == null) return null;
        return data.getSignSession().getSignLocation();
    }

    public boolean canAutoCheck(UUID uuid) {
        long cooldownMs = plugin.getConfigManager().getFlagCooldownHours() * 3_600_000L;
        return System.currentTimeMillis() - lastAutoCheck.getOrDefault(uuid, 0L) >= cooldownMs;
    }

    public void startCheck(Player target, Player initiator,
                           List<HackDefinition> hacks, boolean autoCheck, String reason) {
        UUID uuid = target.getUniqueId();

        if (plugin.hasActiveSignSession(uuid)) {
            if (initiator != null)
                initiator.sendMessage(plugin.getMessageManager().get("already-checking",
                        Map.of("player", target.getName())));
            return;
        }

        if (plugin.getConfigManager().isBedrockEnabled()) {
            for (String prefix : plugin.getConfigManager().getBedrockPrefixes()) {
                if (target.getName().startsWith(prefix)) {
                    Component msg = plugin.getMessageManager().get("bedrock-skip",
                            Map.of("player", target.getName()));
                    if (initiator != null) initiator.sendMessage(msg);
                    else plugin.getMessageManager().broadcastAlerts(msg);
                    return;
                }
            }
        }

        if (autoCheck) lastAutoCheck.put(uuid, System.currentTimeMillis());

        List<List<HackDefinition>> batches = buildBatches(hacks);
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

    public void abort(UUID uuid) {
        CheckPlayerData data = activeChecks.remove(uuid);
        if (data == null) return;
        if (data.getSignSession() != null) data.getSignSession().restore();
    }

    private List<List<HackDefinition>> buildBatches(List<HackDefinition> hacks) {
        List<List<HackDefinition>> batches = new ArrayList<>();
        for (int i = 0; i < hacks.size(); i += LINES_PER_SIGN)
            batches.add(new ArrayList<>(hacks.subList(i, Math.min(i + LINES_PER_SIGN, hacks.size()))));
        return batches;
    }

    private List<Component> buildLines(List<HackDefinition> batch) {
        List<Component> lines = new ArrayList<>(4);
        for (int i = 0; i < LINES_PER_SIGN; i++)
            lines.add(i < batch.size() ? buildComponent(batch.get(i)) : Component.empty());
        lines.add(Component.keybind(CTRL_KEYBIND));
        return lines;
    }

    private void processBatch(Player target, CheckPlayerData data) {
        UUID uuid = target.getUniqueId();
        if (!activeChecks.containsKey(uuid) || !data.hasMoreBatches()) return;

        final int batchIndex = data.getCurrentBatch();
        List<HackDefinition> batch = data.getCurrentBatchHacks();
        if (batch.isEmpty()) {
            finishCheck(uuid);
            return;
        }

        List<Component> lines = buildLines(batch);
        long timeout = plugin.getConfigManager().getTimeoutTicks();
        SupplierTimeouts callbacks = new SupplierTimeouts(uuid, batchIndex, batch);

        SignSession existing = data.getSignSession();
        if (existing != null && existing.isActive()
                && existing.reopen(target, lines, timeout, callbacks::stillActive, callbacks::onTimeout)) {
            return;
        }

        if (existing != null) {
            existing.restore();
            data.setSignSession(null);
        }

        Optional<SignSession> session = SignSession.open(
                plugin, target, lines, timeout, callbacks::stillActive, callbacks::onTimeout);

        if (session.isEmpty()) {
            finishCheck(uuid);
            return;
        }
        data.setSignSession(session.get());
    }

    private final class SupplierTimeouts {
        private final UUID uuid;
        private final int batchIndex;
        private final List<HackDefinition> batch;

        SupplierTimeouts(UUID uuid, int batchIndex, List<HackDefinition> batch) {
            this.uuid = uuid;
            this.batchIndex = batchIndex;
            this.batch = batch;
        }

        boolean stillActive() {
            CheckPlayerData d = activeChecks.get(uuid);
            return d != null && d.getCurrentBatch() == batchIndex;
        }

        void onTimeout() {
            CheckPlayerData d = activeChecks.get(uuid);
            if (d == null) return;
            if (d.getCurrentBatch() != batchIndex) return;
            if (!d.claimCurrentBatch()) return;

            d.setSignSession(null);
            for (HackDefinition h : batch)
                d.getResults().put(h.getId(), HackResult.PROTECTED);
            d.advanceBatch();
            scheduleNextOrFinish(uuid);
        }
    }

    private Component buildComponent(HackDefinition hack) {
        return switch (hack.getMode()) {
            case METEOR, TRANSLATE -> Component.translatable(hack.getKey(), hack.getFallback());
            case KEYBIND           -> Component.keybind(hack.getKey());
        };
    }

    public void handleBatchResponse(Player target, String[] lines) {
        UUID uuid = target.getUniqueId();
        CheckPlayerData data = activeChecks.get(uuid);
        if (data == null) return;

        SignSession session = data.getSignSession();
        if (session == null || !session.claimForResponse()) return;
        if (!data.claimCurrentBatch()) return;

        List<HackDefinition> batch = data.getCurrentBatchHacks();
        String ctrlResp = lines.length > 3 ? lines[3].strip() : "";
        boolean exploitPreventer = ctrlResp.equalsIgnoreCase(CTRL_KEYBIND);

        plugin.getLogger().info("[CheckHacks] Batch " + data.getCurrentBatch()
                + " from " + target.getName()
                + " L0='" + (lines.length > 0 ? lines[0] : "")
                + "' L1='" + (lines.length > 1 ? lines[1] : "")
                + "' L2='" + (lines.length > 2 ? lines[2] : "")
                + "' CTRL='" + ctrlResp + "'"
                + (exploitPreventer ? " [ExploitPreventer DETECTED]" : ""));

        if (exploitPreventer) {
            Component epMsg = plugin.getMessageManager().get("exploitpreventer-detected",
                    Map.of("player", target.getName()));
            plugin.getMessageManager().broadcastAlerts(epMsg);
            notifyInitiator(data, epMsg);
        }

        for (int i = 0; i < batch.size(); i++) {
            HackDefinition hack = batch.get(i);
            String resp = i < lines.length ? lines[i].strip() : "";
            HackResult result = HackEvaluator.evaluate(hack, resp, exploitPreventer);
            data.getResults().put(hack.getId(), result);
            plugin.getLogger().info("[CheckHacks] " + hack.getDisplayName()
                    + " -> " + result + " (resp='" + resp + "')");
        }

        data.advanceBatch();
        if (data.hasMoreBatches()) {
            scheduleNextOrFinish(uuid);
        } else {
            session.restore();
            data.setSignSession(null);
            finishCheck(uuid);
        }
    }

    private void scheduleNextOrFinish(UUID uuid) {
        CheckPlayerData data = activeChecks.get(uuid);
        if (data == null) return;
        if (!data.hasMoreBatches()) {
            finishCheck(uuid);
            return;
        }

        Player target = Bukkit.getPlayer(uuid);
        Runnable next = () -> {
            Player t = Bukkit.getPlayer(uuid);
            if (t != null && t.isOnline()) processBatch(t, data);
            else finishCheck(uuid);
        };
        if (target != null) FoliaScheduler.runAtEntityLater(plugin, target, next, 1L);
        else                FoliaScheduler.runGlobalLater(plugin, next, 1L);
    }

    private void finishCheck(UUID uuid) {
        CheckPlayerData data = activeChecks.remove(uuid);
        if (data == null) return;
        if (data.getSignSession() != null) {
            data.getSignSession().restore();
            data.setSignSession(null);
        }

        Player targetPlayer = Bukkit.getPlayer(uuid);
        String targetName = targetPlayer != null ? targetPlayer.getName() : uuid.toString();
        String checkerName = resolveCheckerName(data);

        List<HackDefinition> allHacks = data.getBatches().stream().flatMap(List::stream).toList();
        CheckOutcome outcome = CheckOutcome.from(
                targetName, uuid.toString(), checkerName, data.getReason(),
                allHacks, data.getResults());

        notifyResults(data, outcome);
        plugin.getDatabaseManager().runAsync(() -> persist(outcome));
        dispatchWebhook(outcome);
        dispatchCommands(outcome);
    }

    private String resolveCheckerName(CheckPlayerData data) {
        if (data.getInitiatorUUID() != null) {
            return Optional.ofNullable(Bukkit.getPlayer(data.getInitiatorUUID()))
                    .map(Player::getName).orElse("Console");
        }
        return data.isAutoCheck() ? "AutoCheck" : "Console";
    }

    private void notifyResults(CheckPlayerData data, CheckOutcome outcome) {
        Component header = plugin.getMessageManager().get("check-complete",
                Map.of("player", outcome.targetName()));
        plugin.getMessageManager().broadcastAlerts(header);
        notifyInitiator(data, header);

        String prefix = plugin.getConfigManager().getPrefix();
        for (HackDefinition hack : outcome.hacks()) {
            HackResult r = outcome.results().getOrDefault(hack.getId(), HackResult.SKIPPED);
            String color = switch (r) {
                case DETECTED     -> "<red>";
                case NOT_DETECTED -> "<green>";
                case PROTECTED    -> "<yellow>";
                case SKIPPED      -> "<gray>";
            };
            Component line = MiniMessage.miniMessage().deserialize(
                    prefix + "  <white>" + hack.getDisplayName() + ": " + color + r.name());
            plugin.getMessageManager().broadcastAlerts(line);
            notifyInitiator(data, line);
        }
    }

    private void persist(CheckOutcome outcome) {
        plugin.getDatabaseManager().saveHackScan(
                outcome.targetName(),
                outcome.targetUuid(),
                outcome.checkerName(),
                outcome.reason(),
                outcome.anyDetected(),
                outcome.toRows());
    }

    private void dispatchWebhook(CheckOutcome outcome) {
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isDiscordEnabled()) return;
        String hacksChecked = outcome.hacks().stream()
                .map(HackDefinition::getDisplayName)
                .reduce((a, b) -> a + ", " + b).orElse("none");
        WebhookUtil.sendResult(cfg.getWebhookUrl(), cfg.getEmbedColor(),
                cfg.getDiscordMessage(), outcome.targetName(), outcome.checkerName(),
                outcome.reason(), hacksChecked, outcome.resultText());
    }

    private void dispatchCommands(CheckOutcome outcome) {
        ConfigManager cfg = plugin.getConfigManager();
        String tn = outcome.targetName();
        if (outcome.anyDetected() && cfg.isCommandIfPositiveEnabled()) {
            String cmd = cfg.getPositiveCommand().replace("%player%", tn);
            FoliaScheduler.runGlobal(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        }
        if (outcome.anyProtected() && !outcome.anyDetected() && cfg.isCommandIfProtectedEnabled()) {
            String cmd = cfg.getProtectedCommand().replace("%player%", tn);
            FoliaScheduler.runGlobal(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        }
        if (outcome.allClean() && cfg.isCommandIfCleanEnabled()) {
            String cmd = cfg.getCleanCommand().replace("%player%", tn);
            FoliaScheduler.runGlobal(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        }
    }

    private void notifyInitiator(CheckPlayerData data, Component msg) {
        if (data.getInitiatorUUID() == null) return;
        Player ini = Bukkit.getPlayer(data.getInitiatorUUID());
        if (ini == null || !ini.isOnline()) return;
        boolean gets = ini.hasPermission("checkhacks.alerts") && plugin.hasAlertsEnabled(ini.getUniqueId());
        if (!gets) ini.sendMessage(msg);
    }

    public void cleanup() {
        for (UUID uuid : List.copyOf(activeChecks.keySet())) {
            abort(uuid);
        }
    }
}