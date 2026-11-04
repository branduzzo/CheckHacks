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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Isolated so GrimAPI classes are only loaded when Grim is installed. */
final class GrimViolationTracker {

    record Violation(UUID playerUuid, String playerName, String check, String verbose, long time) {}

    /** Mouse sensitivity snapshot: [hSens, vSens], isVanillaMath stored as 1.0/0.0 in [2]. */
    record SensitivitySnapshot(double horizontal, double vertical, boolean vanillaMath) {
        int hPct() { return (int) Math.round(horizontal * 200); }
        int vPct() { return (int) Math.round(vertical  * 200); }
        boolean isDefault() { return Math.abs(horizontal - 0.5) < 0.005 && Math.abs(vertical - 0.5) < 0.005; }
    }

    private static final int MAX = 500;
    private final ConcurrentLinkedDeque<Violation> recent = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<UUID, SensitivitySnapshot> sensitivities = new ConcurrentHashMap<>();

    GrimViolationTracker(VeneerPlugin plugin) {
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
        sensitivities.put(user.getUniqueId(),
                new SensitivitySnapshot(user.getHorizontalSensitivity(), user.getVerticalSensitivity(), user.isVanillaMath()));
    }

    private void add(Violation v) {
        recent.addFirst(v);
        while (recent.size() > MAX) recent.removeLast();
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
