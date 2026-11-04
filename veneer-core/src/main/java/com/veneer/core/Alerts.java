package com.veneer.core;

import com.veneer.VeneerPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Staff alerts for every module. A player gets them with the {@code veneer.alerts} permission unless they
 * switched them off with {@code /veneer alerts}; whether alerts start on is {@code general.alerts.default-enabled}.
 */
public final class Alerts {

    public static final String PERMISSION = "veneer.alerts";

    private final VeneerPlugin plugin;
    /** Players whose setting differs from the default. */
    private final Set<UUID> toggled = ConcurrentHashMap.newKeySet();

    public Alerts(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled(UUID uuid) {
        return plugin.config().alertsDefault() != toggled.contains(uuid);
    }

    /** Flips the player's setting and returns the new state. */
    public boolean toggle(UUID uuid) {
        if (!toggled.remove(uuid)) toggled.add(uuid);
        return isEnabled(uuid);
    }

    public boolean receives(Player player) {
        return player.hasPermission(PERMISSION) && isEnabled(player.getUniqueId());
    }

    /**
     * To every online recipient and the console. The message is built per language from the {@link Messages}
     * the recipient reads ({@link Messages#in}), so each staff member gets it in their {@code /veneer lang}.
     */
    public void send(Function<Messages, Component> message) {
        sendToPlayers(message);
        Bukkit.getConsoleSender().sendMessage(message.apply(plugin.messages()));
    }

    /** To every online recipient only (the caller logs to the console itself). */
    public void sendToPlayers(Function<Messages, Component> message) {
        Map<Messages, Component> built = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers())
            if (receives(p)) p.sendMessage(built.computeIfAbsent(plugin.messages().in(p), message));
    }

    /** Same as {@link #sendToPlayers(Function)} for a string already run through {@link Text#legacy}. */
    public void sendToPlayers(String legacy) {
        for (Player p : Bukkit.getOnlinePlayers())
            if (receives(p)) p.sendMessage(legacy);
    }

    /** Sends {@code message} to the player who started a scan, unless the broadcast already reached them. */
    public void notifyInitiator(UUID initiator, Function<Messages, Component> message) {
        if (initiator == null) return;
        Player p = Bukkit.getPlayer(initiator);
        if (p != null && p.isOnline() && !receives(p)) p.sendMessage(message.apply(plugin.messages().in(p)));
    }
}
