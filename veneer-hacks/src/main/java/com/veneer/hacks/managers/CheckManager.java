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
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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
    private static final int    MAX_CHANNEL_GROUPS = 30;
    private static final int    MAX_CHANNELS_IN_HOVER = 40;

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
        Player gone = Bukkit.getPlayer(uuid);
        plugin.module(HacksModule.class).scanEvent(gone != null ? gone.getName() : uuid.toString(), "hack", "aborted", 0);
        plugin.getLogger().info("[Hacks] Mod scan of " + uuid + " dropped: the player left or did not respawn.");
    }

    public void startCheck(Player target, Player initiator,
                           List<HackDefinition> hacks, boolean autoCheck, String reason) {
        startCheck(target, initiator, hacks, autoCheck, reason, 0, false);
    }

    /** A manual scan that waits for a running language check instead of being refused (/hackcheck all). */
    public void startCheckAfterLang(Player target, Player initiator, List<HackDefinition> hacks, String reason) {
        startCheck(target, initiator, hacks, false, reason, 0, true);
    }

    private void startCheck(Player target, Player initiator,
                            List<HackDefinition> hacks, boolean autoCheck, String reason, int waits, boolean queue) {
        UUID uuid = target.getUniqueId();

        // Both scans use one sign spot and one sign editor: never run them on a player at the same time.
        // A manual scan is refused, an automatic one waits for the language check (a few seconds at most).
        if (plugin.module(HacksModule.class).langChecks().isChecking(uuid)) {
            if (initiator != null && !queue) {
                initiator.sendMessage(plugin.messages().in(initiator).get("already-checking", Map.of("player", target.getName())));
            } else if (waits < 15) {
                FoliaScheduler.runAtEntityLater(plugin, target,
                        () -> { if (target.isOnline()) startCheck(target, initiator, hacks, autoCheck, reason, waits + 1, queue); }, 40L);
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

        var profile = plugin.profileOf(uuid);
        Set<String> channels = profile != null ? profile.getChannels() : Set.of();
        List<HackDefinition> scanHacks = new ArrayList<>(hacks);
        if (!channels.isEmpty()) {
            for (HackDefinition h : plugin.module(HacksModule.class).config().getHacks().values()) {
                if (h.matchesAnyChannel(channels) && !scanHacks.contains(h)) {
                    scanHacks.add(0, h);
                }
            }
        }

        List<List<HackDefinition>> batches = buildBatches(scanHacks, channels);
        if (batches.isEmpty()) return;

        CheckPlayerData data = new CheckPlayerData(uuid,
                initiator != null ? initiator.getUniqueId() : null,
                batches, autoCheck, reason);
        activeChecks.put(uuid, data);

        if (initiator != null)
            initiator.sendMessage(plugin.messages().in(initiator).get("check-started",
                    Map.of("player", target.getName())));

        plugin.module(HacksModule.class).scanEvent(target.getName(), "hack", "started", batches.size());
        FoliaScheduler.runAtEntity(plugin, target, () -> processBatch(target, data));
    }

    private List<List<HackDefinition>> buildBatches(List<HackDefinition> hacks, Set<String> channels) {
        List<HackDefinition> sorted = new ArrayList<>(hacks);
        if (channels != null && !channels.isEmpty()) {
            List<HackDefinition> matching = new ArrayList<>();
            List<HackDefinition> rest = new ArrayList<>();
            for (HackDefinition h : sorted) {
                if (h.matchesAnyChannel(channels)) matching.add(h);
                else rest.add(h);
            }
            sorted = new ArrayList<>(matching);
            sorted.addAll(rest);
        }
        List<List<HackDefinition>> batches = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i += LINES_PER_SIGN)
            batches.add(new ArrayList<>(sorted.subList(i, Math.min(i + LINES_PER_SIGN, sorted.size()))));
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

        boolean classicEp = ctrlResp.equalsIgnoreCase(CTRL_KEYBIND);
        // Every vanilla client names the forward key ("W", or "Not Bound"); an empty control line means the client
        // did not resolve the sign at all, so empty mod lines say nothing either: the scan cannot run on this client
        boolean unsupported = ctrlResp.isEmpty();

        var profile = plugin.profileOf(uuid);
        Set<String> channels = profile != null ? profile.getChannels() : Set.of();

        if (classicEp && profile != null) {
            profile.setExploitPreventer(true);
        }
        if (classicEp) data.setExploitPreventerReason("key");
        if (!unsupported && profile != null) profile.markSignAnswered();

        StringBuilder log = new StringBuilder("[Hacks] Batch ").append(data.getCurrentBatch())
                .append(" from ").append(target.getName());
        if (classicEp) log.append(" [ExploitPreventer DETECTED (raw control key)]");
        if (unsupported) {
            // A built-in keybind failing to resolve on its own is not proof of a protection mod: a headless
            // client (e.g. mineflayer) that never implements lang/keybind resolution looks the same. Registered
            // plugin channels point to a real modded client, so only a client without any is marked as a bot.
            boolean likelyBot = channels.isEmpty();
            if (likelyBot && profile != null) profile.setFakePlayer(true);
            data.setUnsupportedClient(true);
            log.append(": the control line came back empty, the client does not resolve signs (SKIPPED)")
               .append(likelyBot ? "; no plugin channels, marked as FakePlayer" : "");
            plugin.getLogger().info(log.toString());
            finishCheck(uuid);
            return;
        }

        boolean channelSuppressionEp = false;
        for (int i = 0; i < batch.size(); i++) {
            HackDefinition hack = batch.get(i);
            String resp = i < lines.length ? lines[i].strip() : "";
            HackResult result = evaluateResponse(hack, resp, classicEp);
            // The translation/keybind side says clean or PROTECTED, but a channel known for this mod is
            // registered: an exploit preventer that lets built-in keybinds resolve normally (so the
            // CTRL_KEYBIND marker above never trips) still cannot hide the channel handshake at login.
            boolean channelMatches = hack.matchesAnyChannel(channels);
            if ((result == HackResult.NOT_DETECTED || result == HackResult.PROTECTED) && channelMatches) {
                if (!channelSuppressionEp) data.setExploitPreventerReason(hack.getDisplayName());
                channelSuppressionEp = true;
                result = HackResult.SUSPECTED;
            }
            data.getProbes().put(hack.getId(), new String[]{hack.getKey(), resp, matchingChannels(hack, channels)});
            data.getResults().put(hack.getId(), result);
            log.append(' ').append(hack.getDisplayName()).append('=').append(result).append(" ('").append(resp).append("')");
        }

        if (channelSuppressionEp) {
            if (profile != null) profile.setExploitPreventer(true);
            log.append(" [ExploitPreventer DETECTED (channel mod suppressed)]");
        }
        plugin.getLogger().info(log.toString());

        data.incrementBatch();
        plugin.module(HacksModule.class).scanEvent(target.getName(), "hack", "progress", data.getCurrentBatch());
        if (classicEp || channelSuppressionEp) {
            // The client answers every key with the raw key or selectively suppresses mod translations; remaining batches cannot tell anything.
            finishCheck(uuid);
            return;
        }
        scheduleNextOrFinish(uuid);
    }

    private static String matchingChannels(HackDefinition hack, Set<String> channels) {
        List<String> matched = new ArrayList<>();
        for (String channel : channels)
            if (hack.matchesAnyChannel(List.of(channel))) matched.add(channel);
        Collections.sort(matched);
        return String.join(",", matched);
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
        boolean anyUnsure    = false;   // TIMEOUT or SUSPECTED: worth a second look, never a punishment on their own
        boolean allClean     = true;
        for (HackDefinition hack : allHacks) {
            HackResult r = results.getOrDefault(hack.getId(), HackResult.SKIPPED);
            if (r == HackResult.DETECTED)  { anyDetected = true;  allClean = false; }
            if (r == HackResult.PROTECTED) { anyProtected = true; allClean = false; }
            if (r == HackResult.TIMEOUT || r == HackResult.SUSPECTED) { anyUnsure = true; allClean = false; }
            if (r == HackResult.SKIPPED)     allClean = false;
        }

        ConfigManager cfg = plugin.module(HacksModule.class).config();

        // A flagged first pass is only a suspicion: re-scan the flagged mods first and report once, with the
        // confirmed results. Saving and announcing the first pass would leave false positives in the history.
        if (cfg.isDoubleCheckEnabled() && !data.isConfirmScan() && (anyDetected || anyProtected || anyUnsure)) {
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
        String epReason = data.getExploitPreventerReason();
        int skipped = 0;
        for (HackDefinition hack : reportHacks)
            if (report.getOrDefault(hack.getId(), HackResult.SKIPPED) == HackResult.SKIPPED) skipped++;
        String skippedCount = String.valueOf(skipped);
        if ("key".equals(epReason)) {
            sendLine(data, m -> m.get("check-ep-key", Map.of("skipped", skippedCount)));
        } else if (epReason != null) {
            sendLine(data, m -> m.get("check-ep-channel", Map.of("hack", epReason, "skipped", skippedCount)));
        } else if (data.isUnsupportedClient()) {
            sendLine(data, m -> m.get("check-unsupported", Map.of("skipped", skippedCount)));
        }
        boolean stoppedEarly = epReason != null || data.isUnsupportedClient();
        sendLine(data, m -> m.get("check-translation-header"));

        for (HackDefinition hack : reportHacks) {
            HackResult r = report.getOrDefault(hack.getId(), HackResult.SKIPPED);
            if (r == HackResult.DETECTED) { reportDetected = true; detectedNames.add(hack.getDisplayName()); }
            if (hideClean && r == HackResult.NOT_DETECTED) continue;
            if (stoppedEarly && r == HackResult.SKIPPED) continue;  // already told in one line above
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
            if (profile != null) sendVerdicts(data, profile);
            if (cfg.isShowChannels() && profile != null && !profile.getChannels().isEmpty()) {
                String channels = String.join(", ", new java.util.TreeSet<>(profile.getChannels()));
                sendChannels(data, profile.getChannels());
                resultText.append("\nChannels: ").append(channels);
            }
            if (reportDetected && profile != null && profile.getBrand() != null) {
                String brand = profile.getBrand();
                resultText.append("\nSoftware: ").append(brand);
            }
        }

        List<Object[]> dbRows = new ArrayList<>(reportHacks.size());
        for (HackDefinition hack : reportHacks) {
            String[] probe = data.getProbes().getOrDefault(hack.getId(), new String[3]);
            dbRows.add(new Object[]{hack.getId(), hack.getDisplayName(),
                    report.getOrDefault(hack.getId(), HackResult.SKIPPED).name(), hack.getKey(), probe[1], probe[2]});
        }
        // Off the main thread: database I/O
        final boolean detectedForDb = reportDetected;
        FoliaScheduler.runAsync(plugin, () -> {
            plugin.database().scans().saveScanWithResults(
                    "hack", targetName, targetUUID, checkerName, data.getReason(), detectedForDb, dbRows);
            plugin.module(HacksModule.class).scanEvent(targetName, "hack", "done", 0);
        });

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

    private void sendVerdicts(CheckPlayerData data, com.veneer.core.PlayerProfile profile) {
        var ep = profile.exploitPreventerVerdict();
        verdictLine(data, "ExploitPreventer", ep == com.veneer.core.Verdict.DETECTED ? "verdict-warning" : verdictKey(ep), null);
        verdictLine(data, "FakePlayer", verdictKey(profile.fakePlayerVerdict()), null);
        // Plain facts rather than findings: the value itself is the answer
        valueLine(data, "Brand", profile.getBrand(), "verdict-unknown");
        var language = profile.languageVerdict();
        valueLine(data, "Language", profile.getLanguage(), verdictKey(language));
        var protocol = profile.protocolVerdict();
        verdictLine(data, "Protocol Version",
                protocol == com.veneer.core.Verdict.DETECTED ? "verdict-mismatch"
                        : protocol == com.veneer.core.Verdict.NOT_DETECTED ? "verdict-match" : "verdict-unknown",
                protocol == com.veneer.core.Verdict.DETECTED ? profile.getVersionReal() + " / " + profile.getVersionClaimed()
                        : profile.getVersionReal());
    }

    private static String verdictKey(com.veneer.core.Verdict verdict) {
        return "verdict-" + verdict.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private void valueLine(CheckPlayerData data, String check, String value, String missingKey) {
        if (value != null) sendLine(data, m -> m.with("check-value-line", "check", check, "value", value));
        else verdictLine(data, check, missingKey, null);
    }

    /** Channels by namespace, one entry each; hovering an entry lists its channels. Built as components: the client names them. */
    private void sendChannels(CheckPlayerData data, Set<String> channels) {
        Map<String, List<String>> groups = new TreeMap<>();
        for (String channel : new TreeSet<>(channels)) {
            int at = channel.indexOf(':');
            groups.computeIfAbsent(at > 0 ? channel.substring(0, at) : "other", k -> new ArrayList<>()).add(channel);
        }
        sendLine(data, m -> {
            Component line = m.get("check-channels-header");
            int shown = 0;
            for (Map.Entry<String, List<String>> group : groups.entrySet()) {
                if (shown++ == MAX_CHANNEL_GROUPS) {
                    line = line.append(Component.text("... +" + (groups.size() - MAX_CHANNEL_GROUPS), NamedTextColor.GRAY));
                    break;
                }
                List<String> items = group.getValue();
                String hover = String.join("\n", items.subList(0, Math.min(items.size(), MAX_CHANNELS_IN_HOVER)))
                        + (items.size() > MAX_CHANNELS_IN_HOVER ? "\n... +" + (items.size() - MAX_CHANNELS_IN_HOVER) : "");
                if (shown > 1) line = line.append(Component.text(", ", NamedTextColor.GRAY));
                line = line.append(Component.text(group.getKey() + " (" + items.size() + ")", NamedTextColor.AQUA)
                        .hoverEvent(HoverEvent.showText(Component.text(hover))));
            }
            return line;
        });
    }

    private void verdictLine(CheckPlayerData data, String check, String key, String detail) {
        String extra = detail == null ? "" : " (" + detail + ")";
        sendLine(data, m -> m.with("check-verdict-line", "check", check, "verdict", m.raw(key), "detail", extra));
    }

    private void sendLine(CheckPlayerData data, Function<Messages, Component> line) {
        plugin.alerts().send(line);
        plugin.alerts().notifyInitiator(data.getInitiatorUUID(), line);
    }

    private boolean startConfirmScan(Player target, CheckPlayerData original,
                                     List<HackDefinition> allHacks, List<HackDefinition> flagged) {
        var profile = plugin.profileOf(target.getUniqueId());
        Set<String> channels = profile != null ? profile.getChannels() : Set.of();
        List<List<HackDefinition>> batches = buildBatches(flagged, channels);
        if (batches.isEmpty()) return false;

        CheckPlayerData data = new CheckPlayerData(target.getUniqueId(),
                original.getInitiatorUUID(), batches, original.isAutoCheck(), original.getReason(), true);
        data.setFirstPass(allHacks, new LinkedHashMap<>(original.getResults()));
        data.setLastSignLocation(original.getLastSignLocation());
        data.getProbes().putAll(original.getProbes());
        data.setExploitPreventerReason(original.getExploitPreventerReason());
        data.setUnsupportedClient(original.isUnsupportedClient());
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
                .filter(h -> results.getOrDefault(h.getId(), HackResult.SKIPPED) == HackResult.PROTECTED)
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