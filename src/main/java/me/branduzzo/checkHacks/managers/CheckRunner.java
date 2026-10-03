package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.CheckType;
import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.HackDefinition;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Runs several check types one after another on the same player, which is what
 * /checkclient does. Instant checks (brand, mod list) run right away, sign
 * based checks (hack, language) are started and then awaited.
 */
public class CheckRunner {

    private static final long POLL_TICKS = 10L;

    private final CheckHacksPlugin plugin;
    private final Map<UUID, Deque<CheckType>> queues   = new ConcurrentHashMap<>();
    private final Map<UUID, String>           reasons  = new ConcurrentHashMap<>();
    private final Map<UUID, UUID>             starters = new ConcurrentHashMap<>();
    private final Set<UUID>                   announced = ConcurrentHashMap.newKeySet();

    public CheckRunner(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning(UUID uuid) { return queues.containsKey(uuid); }

    /** Automatic checks stay quiet: only the check results are broadcast. */
    public void runAuto(Player target, List<CheckType> types, String reason) {
        start(target, null, types, reason, false);
    }

    public void start(Player target, Player initiator, List<CheckType> types,
                      String reason, boolean announce) {
        UUID uuid = target.getUniqueId();
        if (types == null || types.isEmpty()) return;

        if (queues.containsKey(uuid) || plugin.getCheckManager().isChecking(uuid)
                || plugin.getLangCheckManager().isChecking(uuid)) {
            if (initiator != null)
                initiator.sendMessage(plugin.getMessageManager().get("already-checking",
                        Map.of("player", target.getName())));
            return;
        }

        List<CheckType> allowed = types.stream().filter(this::isEnabled).collect(Collectors.toList());
        if (allowed.isEmpty()) {
            if (initiator != null)
                initiator.sendMessage(plugin.getMessageManager().get("client-check-disabled",
                        Map.of("player", target.getName())));
            return;
        }

        queues.put(uuid, new ArrayDeque<>(allowed));
        reasons.put(uuid, reason == null ? "Client check" : reason);
        if (initiator != null) starters.put(uuid, initiator.getUniqueId());

        if (announce) {
            announced.add(uuid);
            String names = allowed.stream().map(CheckType::getDisplayName)
                    .collect(Collectors.joining(", "));
            Component msg = plugin.getMessageManager().get("client-check-started",
                    Map.of("player", target.getName(), "types", names));
            plugin.getMessageManager().broadcastAlerts(msg);
            notifyInitiator(initiator, msg);
        }

        step(target, 1L);
    }

    public void cleanup() {
        announced.clear();
        queues.clear();
        reasons.clear();
        starters.clear();
    }

    /* ------------------------------------------------------------------ */

    private void step(Player target, long delayTicks) {
        FoliaScheduler.runAtEntityLater(plugin, target, () -> next(target), Math.max(1L, delayTicks));
    }

    private void next(Player target) {
        UUID uuid = target.getUniqueId();
        Deque<CheckType> queue = queues.get(uuid);
        if (queue == null) return;
        if (!target.isOnline()) { finish(target); return; }

        CheckType type = queue.poll();
        if (type == null) { finish(target); return; }

        String reason  = reasons.getOrDefault(uuid, "Client check");
        Player starter = initiator(uuid);
        long between   = Math.max(1L, plugin.getConfigManager().getBetweenChecksTicks());

        boolean waiting;
        switch (type) {
            case BRAND -> {
                plugin.getClientCheckManager().runBrandCheck(target, starter, reason);
                waiting = false;
            }
            case MODLIST -> {
                plugin.getClientCheckManager().runModListCheck(target, starter, reason);
                waiting = false;
            }
            case HACK -> waiting = startHack(target, starter, reason);
            case LANG -> waiting = startLang(target, starter, reason);
            default    -> waiting = false;
        }

        if (waiting) waitFor(target, uuid, type, 0L);
        else         step(target, between);
    }

    private boolean startHack(Player target, Player starter, String reason) {
        List<HackDefinition> hacks = plugin.getConfigManager().getDefaultCheckHacks();
        if (hacks.isEmpty()) return false;
        if (plugin.getCheckManager().isChecking(target.getUniqueId())) return false;
        plugin.getCheckManager().startCheck(target, starter, hacks, false, reason);
        return true;
    }

    private boolean startLang(Player target, Player starter, String reason) {
        Map<String, String> languages = plugin.getConfigManager().getLanguages();
        if (languages.isEmpty()) return false;
        if (plugin.getLangCheckManager().isChecking(target.getUniqueId())) return false;
        plugin.getLangCheckManager().startCheck(target, starter, languages);
        return true;
    }

    private void waitFor(Player target, UUID uuid, CheckType type, long waitedTicks) {
        FoliaScheduler.runAtEntityLater(plugin, target, () -> {
            if (!target.isOnline()) { finish(target); return; }
            if (!queues.containsKey(uuid)) return;

            if (!isBusy(uuid, type)) {
                step(target, Math.max(1L, plugin.getConfigManager().getBetweenChecksTicks()));
                return;
            }
            if (waitedTicks >= plugin.getConfigManager().getChainTimeoutTicks()) {
                finish(target);
                return;
            }
            waitFor(target, uuid, type, waitedTicks + POLL_TICKS);
        }, POLL_TICKS);
    }

    private boolean isBusy(UUID uuid, CheckType type) {
        return switch (type) {
            case HACK -> plugin.getCheckManager().isChecking(uuid);
            case LANG -> plugin.getLangCheckManager().isChecking(uuid);
            default   -> false;
        };
    }

    private void finish(Player target) {
        UUID uuid = target.getUniqueId();
        Player starter = initiator(uuid);
        boolean wasAnnounced = announced.remove(uuid);

        queues.remove(uuid);
        reasons.remove(uuid);
        starters.remove(uuid);

        if (wasAnnounced && target.isOnline()) {
            Component msg = plugin.getMessageManager().get("client-check-finished",
                    Map.of("player", target.getName()));
            plugin.getMessageManager().broadcastAlerts(msg);
            notifyInitiator(starter, msg);
        }
    }

    private boolean isEnabled(CheckType type) {
        ConfigManager cfg = plugin.getConfigManager();
        return switch (type) {
            case BRAND   -> cfg.isBrandCheckEnabled();
            case MODLIST -> cfg.isModListCheckEnabled();
            case LANG    -> !cfg.getLanguages().isEmpty();
            case HACK    -> true;
        };
    }

    private Player initiator(UUID uuid) {
        UUID starter = starters.get(uuid);
        return starter == null ? null : Bukkit.getPlayer(starter);
    }

    private void notifyInitiator(Player initiator, Component message) {
        if (initiator == null) return;
        boolean getsAlerts = initiator.hasPermission("checkhacks.alerts")
                && plugin.hasAlertsEnabled(initiator.getUniqueId());
        if (!getsAlerts) initiator.sendMessage(message);
    }
}
