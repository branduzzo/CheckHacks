package me.branduzzo.checkHacks.listeners;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.ClientType;
import me.branduzzo.checkHacks.HackDefinition;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates join-triggered probes for each player connection. Readiness and
 * configured delays are separate gates; once ready, hack scans run before the
 * language probe. Pending callbacks check connection identity so a quick rejoin
 * cannot resume work from the previous connection.
 */
public class JoinListener implements Listener {

    private final CheckHacksPlugin plugin;
    // "Only first join" retains the existing meaning: first check since plugin startup.
    private final Set<UUID> alreadyHackChecked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alreadyLangChecked = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PendingJoin> pendingJoins = new ConcurrentHashMap<>();
    private final boolean hasWorldLoadedEvent;

    public JoinListener(CheckHacksPlugin plugin) {
        this.plugin = plugin;
        hasWorldLoadedEvent = registerWorldLoadedEvent();
    }

    // Reflection keeps the movement fallback usable on older Paper/Spigot versions.
    private boolean registerWorldLoadedEvent() {
        try {
            Class<? extends Event> eventClass = Class.forName(
                    "io.papermc.paper.event.player.PlayerClientLoadedWorldEvent").asSubclass(Event.class);
            Method isTimeout = eventClass.getMethod("isTimeout");
            plugin.getServer().getPluginManager().registerEvent(eventClass, this, EventPriority.MONITOR,
                    (listener, event) -> {
                        try {
                            Player player = ((PlayerEvent) event).getPlayer();
                            PendingJoin pending = pendingJoins.get(player.getUniqueId());
                            if (pending == null || pending.player != player) return;
                            if ((boolean) isTimeout.invoke(event)) {
                                // Paper also fires this after 60 ticks without client acknowledgement.
                                // A timeout alone is not evidence that the client can edit a sign yet.
                                pending.movementFallback = true;
                            } else {
                                worldReady(pending);
                            }
                        } catch (ReflectiveOperationException ex) {
                            throw new EventException(ex);
                        }
                    }, plugin);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException ex) {
            return false;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (plugin.getUpdateManager() != null && plugin.getUpdateManager().isUpdateAvailable()
                && player.hasPermission("checkhacks.update")) {
            plugin.getUpdateManager().notifyIfAvailable(player);
        }

        PendingJoin pending = new PendingJoin(player,
                plugin.getConfigManager().isJoinCheckEnabled()
                        && (!plugin.getConfigManager().isOnlyFirstJoin() || !alreadyHackChecked.contains(uuid)),
                plugin.getConfigManager().isLangJoinCheckEnabled()
                        && (!plugin.getConfigManager().isLangOnlyFirstJoin() || !alreadyLangChecked.contains(uuid)),
                plugin.getConfigManager().isJoinCheckWaitForWorldReady(),
                plugin.getConfigManager().isLangJoinCheckWaitForWorldReady(),
                !hasWorldLoadedEvent);
        pendingJoins.put(uuid, pending);
        updateClientType(player);
        // Preserve the original join-relative timers unless readiness waiting is opted into.
        if (pending.hacksPending && !pending.waitHacks) {
            scheduleReady(pending, true, plugin.getConfigManager().getJoinCheckDelayTicks());
        }
        if (pending.langPending && !pending.waitLang) {
            scheduleReady(pending, false, plugin.getConfigManager().getLangJoinCheckDelayTicks());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        PendingJoin pending = pendingJoins.get(event.getPlayer().getUniqueId());
        if (pending == null || pending.player != event.getPlayer() || pending.worldReady
                || !pending.movementFallback || event.getTo() == null) return;
        if (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ()) {
            worldReady(pending);
        }
    }

    private void worldReady(PendingJoin pending) {
        if (!isCurrent(pending) || pending.worldReady) return;
        pending.worldReady = true;
        updateClientType(pending.player);

        if (pending.hacksPending && pending.waitHacks) {
            scheduleReady(pending, true, plugin.getConfigManager().getJoinCheckDelayTicks());
        }
        if (pending.langPending && pending.waitLang) {
            scheduleReady(pending, false, plugin.getConfigManager().getLangJoinCheckDelayTicks());
        }
        if (!pending.hacksPending && !pending.langPending) {
            pendingJoins.remove(pending.player.getUniqueId(), pending);
        }
    }

    private void scheduleReady(PendingJoin pending, boolean hacks, long delayTicks) {
        Runnable ready = () -> {
            if (!isCurrent(pending)) return;
            if (hacks) pending.hacksReady = true;
            else pending.langReady = true;
            pump(pending);
        };
        if (delayTicks <= 0) ready.run();
        else FoliaScheduler.runAtEntityLater(plugin, pending.player, ready, delayTicks);
    }

    private void pump(PendingJoin pending) {
        // A running manual scan or confirmation pass owns the sign editor too.
        // Keep one retry queued until both managers release that player.
        if (!isCurrent(pending)) return;
        UUID uuid = pending.player.getUniqueId();
        if (plugin.getCheckManager().isChecking(uuid) || plugin.getLangCheckManager().isChecking(uuid)) {
            schedulePump(pending);
            return;
        }

        if (pending.hacksPending) {
            if (!pending.hacksReady) return;
            pending.hacksPending = false;
            List<HackDefinition> hacks = plugin.getConfigManager().getJoinCheckHacks();
            if (!hacks.isEmpty()) {
                alreadyHackChecked.add(uuid);
                plugin.getMessageManager().broadcastAlerts(
                        plugin.getMessageManager().get("join-check", Map.of("player", pending.player.getName())));
                plugin.getCheckManager().startCheck(pending.player, null, hacks, true, "Auto-join check");
            }
            // The language check waits for every hack batch and confirmation scan to finish.
            if (pending.langPending) schedulePump(pending);
            else pendingJoins.remove(uuid, pending);
            return;
        }

        if (pending.langPending) {
            if (!pending.langReady) return;
            pending.langPending = false;
            Map<String, String> langs = plugin.getConfigManager().getLanguages();
            if (!langs.isEmpty()) {
                alreadyLangChecked.add(uuid);
                plugin.getMessageManager().broadcastAlerts(
                        plugin.getMessageManager().get("lang-join-check", Map.of("player", pending.player.getName())));
                plugin.getLangCheckManager().startCheck(pending.player, null, langs);
            }
        }
        pendingJoins.remove(uuid, pending);
    }

    private void schedulePump(PendingJoin pending) {
        if (!pending.pumpScheduled.compareAndSet(false, true)) return;
        FoliaScheduler.runAtEntityLater(plugin, pending.player, () -> {
            pending.pumpScheduled.set(false);
            pump(pending);
        }, 1L);
    }

    private boolean isCurrent(PendingJoin pending) {
        return pending.player.isOnline()
                && pendingJoins.get(pending.player.getUniqueId()) == pending;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingJoins.remove(uuid);
        plugin.getCheckManager().cancelCheck(uuid);
        plugin.getLangCheckManager().cancelCheck(uuid);
        plugin.getClientDataManager().remove(uuid);
    }

    private void updateClientType(Player player) {
        Set<String> channels = player.getListeningPluginChannels();
        plugin.getClientDataManager().setClientType(player.getUniqueId(), detectClientType(channels));
    }

    private ClientType detectClientType(Set<String> channels) {
        for (String ch : channels) {
            String lower = ch.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("fabric") || lower.contains("fabric-api")
                    || lower.contains("fabric-networking") || lower.contains("fabric-screen")) {
                return ClientType.FABRIC;
            }
        }
        for (String ch : channels) {
            String lower = ch.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("fml") || lower.startsWith("forge")
                    || lower.contains("forge:") || lower.contains("fml:")) {
                return ClientType.FORGE;
            }
        }
        if (channels.isEmpty()) return ClientType.VANILLA;
        return ClientType.UNKNOWN;
    }

    private static final class PendingJoin {
        private final Player player;
        private final AtomicBoolean pumpScheduled = new AtomicBoolean();
        private final boolean waitHacks;
        private final boolean waitLang;
        private boolean hacksPending;
        private boolean langPending;
        private boolean hacksReady;
        private boolean langReady;
        private boolean worldReady;
        private boolean movementFallback;

        private PendingJoin(Player player, boolean hacksPending, boolean langPending,
                            boolean waitHacks, boolean waitLang, boolean movementFallback) {
            this.player = player;
            this.hacksPending = hacksPending;
            this.langPending = langPending;
            this.waitHacks = waitHacks;
            this.waitLang = waitLang;
            this.movementFallback = movementFallback;
        }
    }
}
