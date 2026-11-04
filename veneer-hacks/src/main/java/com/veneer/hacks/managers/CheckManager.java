package com.veneer.hacks.managers;

import com.veneer.hacks.HacksModule;
import com.veneer.core.Messages;
import com.veneer.VeneerPlugin;
import com.veneer.hacks.*;
import com.veneer.core.FoliaScheduler;
import com.veneer.core.ClientVersions;
import com.veneer.hacks.utils.SignUtil;
import com.veneer.core.WrappedTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;

import java.util.function.Function;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CheckManager {

    private static final String CTRL_KEYBIND  = "key.forward";
    private static final int    LINES_PER_SIGN = 3;

    private final VeneerPlugin plugin;
    private final Map<UUID, CheckPlayerData> activeChecks  = new ConcurrentHashMap<>();
    private final Map<UUID, Long>            lastAutoCheck = new ConcurrentHashMap<>();

    public CheckManager(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isChecking(UUID uuid) { return activeChecks.containsKey(uuid); }

    /**
     * Starts the flag cooldown for an automatic scan before it is queued, so flags arriving meanwhile (or a scan
     * that never starts: Bedrock, old client) cannot alert again; false while the cooldown runs.
     */
    public boolean claimAutoCheck(UUID uuid) {
        long now = System.currentTimeMillis();
        long cooldownMs = plugin.module(HacksModule.class).config().getFlagCooldownHours() * 3_600_000L;
        boolean[] claimed = {false};
        lastAutoCheck.compute(uuid, (k, last) -> {
            if (last != null && now - last < cooldownMs) return last;
            claimed[0] = true;
            return now;
        });
        return claimed[0];
    }

    /** True when the edited sign at {@code loc} is this player's current probe sign. */
    public boolean ownsSign(UUID uuid, Location loc) {
        CheckPlayerData data = activeChecks.get(uuid);
        return data != null && SignUtil.sameBlock(data.getSignLocation(), loc);
    }

    /** True when {@code loc} is any running scan's probe sign. */
    public boolean isProbeSign(Location loc) {
        for (CheckPlayerData d : activeChecks.values())
            if (SignUtil.sameBlock(d.getSignLocation(), loc)) return true;
        return false;
    }

    /** The player left: drop the scan without results or punishments and put the blocks back. */
    public void abort(UUID uuid) {
        CheckPlayerData data = activeChecks.remove(uuid);
        if (data == null) return;
        if (data.getSignTimeoutTask() != null) data.getSignTimeoutTask().cancel();
        restoreCurrentSign(data, false);
        plugin.getLogger().info("[Hacks] Mod scan of " + uuid + " dropped: the player left or did not respawn.");
    }

    public void startCheck(Player target, Player initiator,
                           List<HackDefinition> hacks, boolean autoCheck, String reason) {
        startCheck(target, initiator, hacks, autoCheck, reason, 0);
    }

    private void startCheck(Player target, Player initiator,
                            List<HackDefinition> hacks, boolean autoCheck, String reason, int waits) {
        UUID uuid = target.getUniqueId();

        // Both scans use one sign spot and one sign editor: never run them on a player at the same time.
        // A manual scan is refused, an automatic one waits for the language check (a few seconds at most).
        if (plugin.module(HacksModule.class).langChecks().isChecking(uuid)) {
            if (initiator != null) {
                initiator.sendMessage(plugin.messages().in(initiator).get("already-checking", Map.of("player", target.getName())));
            } else if (waits < 15) {
                FoliaScheduler.runAtEntityLater(plugin, target,
                        () -> { if (target.isOnline()) startCheck(target, null, hacks, autoCheck, reason, waits + 1); }, 40L);
            }
            return;
        }

        if (activeChecks.containsKey(uuid)) {
            if (initiator != null)
                initiator.sendMessage(plugin.messages().in(initiator).get("already-checking",
                        Map.of("player", target.getName())));
            return;
        }

        if (plugin.bedrock().isBedrock(target)) {
            Function<Messages, Component> msg = m -> m.get("bedrock-skip",
                    Map.of("player", target.getName()));
            if (initiator != null) initiator.sendMessage(msg.apply(plugin.messages().in(initiator)));
            else plugin.alerts().send(msg);
            return;
        }

        String oldClient = ClientVersions.tooOld(target, plugin.module(HacksModule.class).config().getMinClientVersion());
        if (oldClient != null) {
            Function<Messages, Component> msg = m -> m.get("client-too-old", Map.of("player", target.getName(),
                    "version", oldClient, "min", plugin.module(HacksModule.class).config().getMinClientVersion()));
            if (initiator != null) initiator.sendMessage(msg.apply(plugin.messages().in(initiator)));
            else plugin.alerts().send(msg);
            return;
        }


        if (autoCheck) lastAutoCheck.put(uuid, System.currentTimeMillis());

        List<List<HackDefinition>> batches = buildBatches(hacks);
        if (batches.isEmpty()) return;

        CheckPlayerData data = new CheckPlayerData(uuid,
                initiator != null ? initiator.getUniqueId() : null,
                batches, autoCheck, reason);
        activeChecks.put(uuid, data);

        if (initiator != null)
            initiator.sendMessage(plugin.messages().in(initiator).get("check-started",
                    Map.of("player", target.getName())));

        FoliaScheduler.runAtEntity(plugin, target, () -> processBatch(target, data));
    }

    private List<List<HackDefinition>> buildBatches(List<HackDefinition> hacks) {
        List<List<HackDefinition>> batches = new ArrayList<>();
        for (int i = 0; i < hacks.size(); i += LINES_PER_SIGN)
            batches.add(new ArrayList<>(hacks.subList(i, Math.min(i + LINES_PER_SIGN, hacks.size()))));
        return batches;
    }

    private void processBatch(Player target, CheckPlayerData data) {
        UUID uuid = target.getUniqueId();
        if (activeChecks.get(uuid) != data) return;
        // A dead or sleeping player cannot open the sign: wait instead of letting it time out, which would
        // count as PROTECTED and may punish
        if (SignUtil.cannotOpen(target)) {
            if (data.waitWhileBusy() > 60) {
                abort(uuid);
                return;
            }
            FoliaScheduler.runAtEntityLater(plugin, target, () -> processBatch(target, data), 20L);
            return;
        }
        List<HackDefinition> batch = data.getCurrentBatchHacks();

        Location signLoc = SignUtil.findAirBlock(target, data.getLastSignLocation());
        if (signLoc == null) {
            finishCheck(uuid);
            return;
        }

        Block block = signLoc.getBlock();
        BlockState originalState = block.getState();

        block.setType(Material.OAK_SIGN, false);
        BlockState freshState = block.getState();
        if (!(freshState instanceof Sign sign)) {
            originalState.update(true, false);
            finishCheck(uuid);
            return;
        }

        var front = sign.getSide(Side.FRONT);
        for (int i = 0; i < LINES_PER_SIGN; i++)
            front.line(i, i < batch.size() ? buildComponent(batch.get(i)) : Component.empty());
        front.line(3, Component.keybind(CTRL_KEYBIND));
        sign.update(true, false);

        data.setSignLocation(signLoc);
        data.setLastSignLocation(signLoc);
        data.setOriginalState(originalState);

        SignUtil.setAllowedEditor(signLoc, uuid, plugin);

        FoliaScheduler.runAtEntity(plugin, target, () -> {
            if (!activeChecks.containsKey(uuid)) return;
            SignUtil.sendBlockEntityPacket(target, signLoc, plugin);
            FoliaScheduler.runAtEntityLater(plugin, target, () -> {
                if (!activeChecks.containsKey(uuid)) return;
                SignUtil.sendOpenSignPacket(target, signLoc, plugin);
                target.sendBlockChange(signLoc, Material.AIR.createBlockData());
            }, 1L);
        });

        WrappedTask timeout = FoliaScheduler.runAtLocationLater(plugin, signLoc, () -> {
            CheckPlayerData d = activeChecks.get(uuid);
            if (d == null) return;
            Location asked = d.getSignLocation();
            restoreCurrentSign(d, false);
            Player again = Bukkit.getPlayer(uuid);
            if (again != null && again.isOnline() && SignUtil.lostSign(again, asked)) {
                // Died, slept, changed world or walked off with the sign open: no answer is not a refusal,
                // ask again (counted, so a player who keeps doing it does not keep the check alive forever)
                if (d.waitWhileBusy() > 60) { abort(uuid); return; }
                FoliaScheduler.runAtEntity(plugin, again, () -> processBatch(again, d));
                return;
            }
            if (plugin.module(HacksModule.class).config().isRetryOnTimeout() && !d.isRetried() && again != null && again.isOnline()) {
                d.markRetried();
                FoliaScheduler.runAtEntity(plugin, again, () -> processBatch(again, d));
                return;
            }
            for (HackDefinition h : batch)
                d.getResults().put(h.getId(), HackResult.TIMEOUT);
            d.incrementBatch();
            scheduleNextOrFinish(uuid);
        }, timeoutFor(target, data.isRetried()));

        data.setSignTimeoutTask(timeout);
    }

    /** Ticks to wait for the sign answer: ping plus a margin (capped by timeout-ticks), doubled on the retry. */
    private long timeoutFor(Player target, boolean retry) {
        ConfigManager cfg = plugin.module(HacksModule.class).config();
        long max = cfg.getTimeoutTicks();
        if (!cfg.isAdaptiveTimeout()) return max;
        long pingTicks = (target.getPing() + 49) / 50;
        long wait = Math.max(cfg.getAdaptiveMinTicks(), pingTicks + cfg.getAdaptiveMarginTicks());
        if (retry) wait *= 2;
        return Math.min(wait, max);
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

        if (data.getSignTimeoutTask() != null) data.getSignTimeoutTask().cancel();
        restoreCurrentSign(data, false);

        List<HackDefinition> batch = data.getCurrentBatchHacks();
        String ctrlResp = lines.length > 3 ? lines[3].strip() : "";

        boolean exploitPreventer = ctrlResp.equalsIgnoreCase(CTRL_KEYBIND);
        // Every vanilla client names the forward key ("W", or "Not Bound"); an empty control line means the client
        // did not resolve the sign at all, so empty mod lines say nothing either: the scan cannot run on this client
        boolean unsupported = ctrlResp.isEmpty();

        var profile = plugin.profileOf(uuid);
        Set<String> channels = profile != null ? profile.getChannels() : Set.of();

        StringBuilder log = new StringBuilder("[Hacks] Batch ").append(data.getCurrentBatch())
                .append(" from ").append(target.getName());
        if (exploitPreventer) log.append(" [ExploitPreventer DETECTED]");
        if (unsupported) {
            // A built-in keybind failing to resolve on its own is not proof of a protection mod: a headless
            // client (e.g. mineflayer) that never implements lang/keybind resolution looks the same. No plugin
            // channels at all is the extra tell, since a real client registers them before any player logic runs.
            boolean likelyBot = channels.isEmpty();
            log.append(": the control line came back empty, the client does not resolve signs; the scan stops (SKIPPED)")
               .append(likelyBot ? " [likely headless/bot client, no plugin channels]" : "");
            plugin.getLogger().info(log.toString());
            finishCheck(uuid);
            return;
        }
        for (int i = 0; i < batch.size(); i++) {
            HackDefinition hack = batch.get(i);
            String resp = i < lines.length ? lines[i].strip() : "";
            HackResult result = evaluateResponse(hack, resp, exploitPreventer);
            // The translation/keybind side says clean or PROTECTED, but a channel known for this mod is
            // registered: an exploit preventer that lets built-in keybinds resolve normally (so the
            // CTRL_KEYBIND marker above never trips) still cannot hide the channel handshake at login.
            if ((result == HackResult.NOT_DETECTED || result == HackResult.PROTECTED) && hack.matchesAnyChannel(channels))
                result = HackResult.SUSPECTED;
            data.getResults().put(hack.getId(), result);
            log.append(' ').append(hack.getDisplayName()).append('=').append(result).append(" ('").append(resp).append("')");
        }
        plugin.getLogger().info(log.toString());

        data.incrementBatch();
        if (exploitPreventer) {
            // The client answers every key with the raw key, so the remaining batches cannot tell anything; they stay SKIPPED.
            finishCheck(uuid);
            return;
        }
        scheduleNextOrFinish(uuid);
    }

    private void scheduleNextOrFinish(UUID uuid) {
        CheckPlayerData data = activeChecks.get(uuid);
        if (data == null) return;
        if (data.hasMoreBatches()) {
            Player target = Bukkit.getPlayer(uuid);
            long delay = plugin.module(HacksModule.class).config().getBetweenSignTicks();
            Runnable next = () -> {
                Player t = Bukkit.getPlayer(uuid);
                if (t != null && t.isOnline()) processBatch(t, data);
                else finishCheck(uuid);
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
        for (HackDefinition hack : allHacks) {
            HackResult r = results.getOrDefault(hack.getId(), HackResult.SKIPPED);
            if (r == HackResult.DETECTED)  { anyDetected = true;  allClean = false; }
            if (r == HackResult.PROTECTED || r == HackResult.TIMEOUT || r == HackResult.SUSPECTED) { anyProtected = true; allClean = false; }
            if (r == HackResult.SKIPPED)     allClean = false;
        }

        ConfigManager cfg = plugin.module(HacksModule.class).config();

        // A flagged first pass is only a suspicion: re-scan the flagged mods first and report once, with the
        // confirmed results. Saving and announcing the first pass would leave false positives in the history.
        if (cfg.isDoubleCheckEnabled() && !data.isConfirmScan() && (anyDetected || anyProtected)) {
            List<HackDefinition> flagged = new ArrayList<>();
            for (HackDefinition hack : allHacks) {
                HackResult r = results.getOrDefault(hack.getId(), HackResult.SKIPPED);
                if (r == HackResult.DETECTED || r == HackResult.PROTECTED || r == HackResult.TIMEOUT || r == HackResult.SUSPECTED) flagged.add(hack);
            }
            if (!flagged.isEmpty() && targetPlayer != null && targetPlayer.isOnline()
                    && startConfirmScan(targetPlayer, data, allHacks, flagged)) return;
        }

        // The report: on a confirmation scan, the first pass with the flagged mods replaced by their re-scan
        List<HackDefinition> reportHacks = data.getFirstPassHacks() != null ? data.getFirstPassHacks() : allHacks;
        Map<String, HackResult> report = new LinkedHashMap<>();
        if (data.getFirstPassResults() != null) report.putAll(data.getFirstPassResults());
        report.putAll(results);
        boolean reportDetected = false;
        StringBuilder resultText = new StringBuilder();
        List<String> detectedNames = new ArrayList<>();

        Function<Messages, Component> header = m -> m.get("check-complete", Map.of("player", targetName));
        plugin.alerts().send(header);
        plugin.alerts().notifyInitiator(data.getInitiatorUUID(), header);

        boolean hideClean = cfg.isHideClean();
        int shown = 0;
        sendLine(data, m -> m.get("check-translation-header"));

        for (HackDefinition hack : reportHacks) {
            HackResult r = report.getOrDefault(hack.getId(), HackResult.SKIPPED);
            if (r == HackResult.DETECTED) { reportDetected = true; detectedNames.add(hack.getDisplayName()); }
            if (hideClean && r == HackResult.NOT_DETECTED) continue;
            shown++;
            resultText.append(hack.getDisplayName()).append(": ").append(r.name()).append("\n");

            Function<Messages, Component> line = m -> m.with("check-result-line", "hack", hack.getDisplayName(),
                    "result", m.raw("result-" + r.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
            sendLine(data, line);
        }
        if (shown == 0) sendLine(data, m -> m.get("check-nothing-found"));
        if (resultText.isEmpty()) resultText.append("-");

        if (targetPlayer != null) {
            var profile = plugin.profileOf(uuid);
            if (cfg.isShowChannels() && profile != null && !profile.getChannels().isEmpty()) {
                String channels = String.join(", ", new java.util.TreeSet<>(profile.getChannels()));
                sendLine(data, m -> m.get("check-channels", Map.of("channels", channels)));
                resultText.append("\nChannels: ").append(channels);
            }
            if (reportDetected && profile != null && profile.getBrand() != null) {
                String brand = profile.getBrand();
                sendLine(data, m -> m.get("check-brand", Map.of("brand", brand)));
                resultText.append("\nSoftware: ").append(brand);
            }
        }

        List<Object[]> dbRows = new ArrayList<>(reportHacks.size());
        for (HackDefinition hack : reportHacks)
            dbRows.add(new Object[]{hack.getId(), hack.getDisplayName(),
                    report.getOrDefault(hack.getId(), HackResult.SKIPPED).name()});
        // Off the main thread: database I/O
        final boolean detectedForDb = reportDetected;
        FoliaScheduler.runAsync(plugin, () -> plugin.database().scans().saveScanWithResults(
                "hack", targetName, targetUUID, checkerName, data.getReason(), detectedForDb, dbRows));

        if (cfg.isDiscordEnabled()) {
            String hacksChecked = reportHacks.stream()
                    .map(HackDefinition::getDisplayName)
                    .reduce((a, b) -> a + ", " + b).orElse("none");
            var profile = plugin.profileOf(uuid);
            Map<String, String> values = new java.util.HashMap<>();
            values.put("player", targetName);
            values.put("name", targetName); // &name& of older configs
            values.put("uuid", targetUUID);
            values.put("checker", checkerName);
            values.put("reason", String.valueOf(data.getReason()));
            values.put("hacks", hacksChecked);
            values.put("results", resultText.toString().trim());
            values.put("detected", detectedNames.isEmpty() ? "none" : String.join(", ", detectedNames));
            values.put("brand", profile != null && profile.getBrand() != null ? profile.getBrand() : "unknown");
            values.put("channels", profile == null || profile.getChannels().isEmpty() ? "none"
                    : String.join(", ", new java.util.TreeSet<>(profile.getChannels())));
            values.put("version", com.veneer.core.ClientVersions.name(targetPlayer));
            plugin.webhook().report(cfg.getWebhookUrl(), cfg.getEmbedColor(), cfg.getDiscordTitle(),
                    cfg.getDiscordMessage(), cfg.isDiscordUseComponentsV2(), Bukkit.getOfflinePlayer(uuid), values);
        }

        executeResultActions(data, targetName, anyDetected, anyProtected, allClean, results, allHacks);
    }

    private void sendLine(CheckPlayerData data, Function<Messages, Component> line) {
        plugin.alerts().send(line);
        plugin.alerts().notifyInitiator(data.getInitiatorUUID(), line);
    }

    private boolean startConfirmScan(Player target, CheckPlayerData original,
                                     List<HackDefinition> allHacks, List<HackDefinition> flagged) {
        List<List<HackDefinition>> batches = buildBatches(flagged);
        if (batches.isEmpty()) return false;

        CheckPlayerData data = new CheckPlayerData(target.getUniqueId(),
                original.getInitiatorUUID(), batches, original.isAutoCheck(), original.getReason(), true);
        data.setFirstPass(allHacks, new LinkedHashMap<>(original.getResults()));
        data.setLastSignLocation(original.getLastSignLocation());
        activeChecks.put(target.getUniqueId(), data);

        Function<Messages, Component> msg = m -> m.get("doublecheck", Map.of("player", target.getName()));
        plugin.alerts().send(msg);
        plugin.alerts().notifyInitiator(data.getInitiatorUUID(), msg);

        FoliaScheduler.runAtEntity(plugin, target, () -> processBatch(target, data));
        return true;
    }

    private void executeResultActions(CheckPlayerData data, String targetName,
                                      boolean anyDetected, boolean anyProtected, boolean allClean,
                                      Map<String, HackResult> results, List<HackDefinition> allHacks) {
        if (data.isConfirmScan() && allClean) return;

        ConfigManager cfg = plugin.module(HacksModule.class).config();
        final String tn = targetName;

        String detectedList = allHacks.stream()
                .filter(h -> results.getOrDefault(h.getId(), HackResult.SKIPPED) == HackResult.DETECTED)
                .map(HackDefinition::getDisplayName)
                .reduce((a, b) -> a + ", " + b).orElse("");
        String protectedList = allHacks.stream()
                .filter(h -> {
                    HackResult r = results.getOrDefault(h.getId(), HackResult.SKIPPED);
                    return r == HackResult.PROTECTED || r == HackResult.TIMEOUT || r == HackResult.SUSPECTED;
                })
                .map(HackDefinition::getDisplayName)
                .reduce((a, b) -> a + ", " + b).orElse("");
        String detectedOrProtectedList = allHacks.stream()
                .filter(h -> {
                    HackResult r = results.getOrDefault(h.getId(), HackResult.SKIPPED);
                    return r == HackResult.DETECTED || r == HackResult.PROTECTED || r == HackResult.TIMEOUT || r == HackResult.SUSPECTED;
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
                    String cmd = applyCommandPlaceholders(rule.getCommand(), tn, hack.getDisplayName());
                    FoliaScheduler.runGlobal(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
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


    private void restoreCurrentSign(CheckPlayerData data, boolean now) {
        Location loc = data.getSignLocation();
        if (loc == null) return;
        SignUtil.restore(plugin, loc, data.getOriginalState(), now);
        data.setSignLocation(null);
    }

    public void cleanup() {
        for (CheckPlayerData d : activeChecks.values()) {
            if (d.getSignTimeoutTask() != null) d.getSignTimeoutTask().cancel();
            // On shutdown the scheduler refuses new tasks, so the blocks are put back right here
            restoreCurrentSign(d, true);
        }
        activeChecks.clear();
        lastAutoCheck.clear();
    }

}