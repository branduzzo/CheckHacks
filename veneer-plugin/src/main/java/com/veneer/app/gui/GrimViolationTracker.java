package com.veneer.app.gui;

import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.event.events.FlagEvent;
import ac.grim.grimac.api.event.events.GrimJoinEvent;
import com.veneer.VeneerPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated so GrimAPI classes are only loaded when Grim is installed. */
final class GrimViolationTracker {

    record Violation(UUID playerUuid, String playerName, String check, String verbose, long time) {}

    /** Mouse sensitivity snapshot: [hSens, vSens], isVanillaMath stored as 1.0/0.0 in [2]. */
    record SensitivitySnapshot(double horizontal, double vertical, boolean vanillaMath) {
        int hPct() { return (int) Math.round(horizontal * 200); }
        int vPct() { return (int) Math.round(vertical  * 200); }
        boolean isDefault() { return Math.abs(horizontal - 0.5) < 0.005 && Math.abs(vertical - 0.5) < 0.005; }
    }

    static final String UNSUPPORTED_MESSAGE = "Installed GrimAC version is not supported! Disabling hooks for mouse...";

    private static final int MAX = 500;
    /** Players whose mouse sensitivity is kept: the most recently flagged ones, so the map cannot grow with every player ever seen. */
    private static final int MAX_SENSITIVITIES = 2000;
    private final VeneerPlugin plugin;
    private volatile boolean sensitivitySupported = true;
    private final ConcurrentLinkedDeque<Violation> recent = new ConcurrentLinkedDeque<>();
    /** Length of {@link #recent}: the deque counts by walking every element, and this runs on every flag. */
    private final AtomicInteger recentSize = new AtomicInteger();
    private final Map<UUID, SensitivitySnapshot> sensitivities = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<UUID, SensitivitySnapshot> eldest) {
                    return size() > MAX_SENSITIVITIES;
                }
            });

    GrimViolationTracker(VeneerPlugin plugin) {
        this.plugin = plugin;
        GrimAbstractAPI api = GrimAPIProvider.get();

        api.getEventBus().get(GrimJoinEvent.class).onJoin(api.getGrimPlugin(plugin), user -> {
            updateSensitivity(user);
        });

        api.getEventBus().get(FlagEvent.class).onFlag(api.getGrimPlugin(plugin), (user, check, verbose, cancelled) -> {
            Player p = Bukkit.getPlayer(user.getUniqueId());
            String name = p != null ? p.getName() : user.getUniqueId().toString().substring(0, 8);
            add(new Violation(user.getUniqueId(), name, String.valueOf(check), verbose != null ? verbose : "", System.currentTimeMillis()));
            updateSensitivity(user);
            return cancelled;
        });
    }

    private void updateSensitivity(GrimUser user) {
        if (!sensitivitySupported) return;
        try {
            sensitivities.put(user.getUniqueId(),
                    new SensitivitySnapshot(user.getHorizontalSensitivity(), user.getVerticalSensitivity(), user.isVanillaMath()));
        } catch (LinkageError e) {
            sensitivitySupported = false;
            plugin.getLogger().warning(UNSUPPORTED_MESSAGE);
        }
    }

    private void add(Violation v) {
        recent.addFirst(v);
        if (recentSize.incrementAndGet() > MAX && recent.pollLast() != null) recentSize.decrementAndGet();
    }

    SensitivitySnapshot getSensitivity(UUID uuid) {
        return sensitivities.get(uuid);
    }

    List<Violation> getRecent(int limit) {
        List<Violation> out = new ArrayList<>(Math.min(limit, recent.size()));
        for (Violation v : recent) {
            out.add(v);
            if (out.size() >= limit) break;
        }
        return out;
    }

    List<Violation> getByPlayer(UUID uuid, int limit) {
        List<Violation> out = new ArrayList<>();
        for (Violation v : recent) {
            if (uuid.equals(v.playerUuid())) {
                out.add(v);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    boolean hasViolations(UUID uuid) {
        for (Violation v : recent) if (uuid.equals(v.playerUuid())) return true;
        return false;
    }
}
