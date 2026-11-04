package com.veneer.core;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * The client's real protocol version, for skipping clients the sign probe does not work on. PacketEvents is started
 * by the spoof module; when that is off the version is unknown and the caller should scan as before.
 */
public final class ClientVersions {

    private ClientVersions() {}

    /** The player's client version, or null when PacketEvents is not running or does not know it yet. */
    public static ClientVersion of(Player player) {
        try {
            if (PacketEvents.getAPI() == null || !PacketEvents.getAPI().isInitialized()) return null;
            ClientVersion v = PacketEvents.getAPI().getPlayerManager().getClientVersion(player);
            return v == null || !v.isRelease() ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The player's client version for messages ("1.21.4"), or "unknown". */
    public static String name(Player player) {
        ClientVersion v = player == null ? null : of(player);
        return v != null ? v.getReleaseName() : "unknown";
    }

    /** "1.20" or "1.20.1" as a ClientVersion, or null when the text names no known release. */
    public static ClientVersion parse(String text) {
        try {
            return ClientVersion.valueOf("V_" + text.trim().replace('.', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** The version the player's client is below {@code min}, as text for messages; null when it can be scanned. */
    public static String tooOld(Player player, String min) {
        ClientVersion need = parse(min), have = of(player);
        if (need == null || have == null || !have.isOlderThan(need)) return null;
        return have.getReleaseName();
    }
}
