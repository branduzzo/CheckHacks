package com.veneer.core;

import com.veneer.VeneerPlugin;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * The one place that decides whether a player joined from Bedrock: Floodgate, Geyser, or a name prefix
 * ({@code general.bedrock}). Both APIs are reached by reflection, so neither has to be installed.
 */
public final class Bedrock {

    private final VeneerPlugin plugin;
    private final Api floodgate = new Api("org.geysermc.floodgate.api.FloodgateApi", "getInstance", "isFloodgatePlayer");
    private final Api geyser = new Api("org.geysermc.geyser.api.GeyserApi", "api", "isBedrockPlayer");

    public Bedrock(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isBedrock(Player player) {
        if (player == null) return false;
        UUID uuid = player.getUniqueId();
        if (floodgate.test(uuid)) {
            plugin.debug("Player " + player.getName() + " identified as Bedrock via Floodgate");
            return true;
        }
        if (geyser.test(uuid)) {
            plugin.debug("Player " + player.getName() + " identified as Bedrock via Geyser");
            return true;
        }
        // The prefix alone is not enough: an offline-mode server lets a Java player pick the name ".Steve".
        // Floodgate (also on a proxy in front of this server) gives Bedrock players a uuid whose upper half is 0.
        if (uuid.getMostSignificantBits() == 0 && hasBedrockPrefix(player.getName())) {
            plugin.debug("Player " + player.getName() + " identified as Bedrock via name prefix");
            return true;
        }
        return false;
    }

    private boolean hasBedrockPrefix(String name) {
        VeneerConfig cfg = plugin.config();
        if (!cfg.bedrockPrefixCheck()) return false;
        List<String> prefixes = cfg.bedrockPrefixes();
        for (String prefix : prefixes) {
            String p = prefix.trim();
            if (!p.isEmpty() && name.startsWith(p)) return true;
        }
        return false;
    }

    /** Reflective "is this UUID a Bedrock player" call on an optional plugin API. */
    private static final class Api {
        private final String className, getter, check;
        private volatile Object api;
        private volatile java.lang.reflect.Method method;
        private volatile boolean resolved;

        Api(String className, String getter, String check) {
            this.className = className;
            this.getter = getter;
            this.check = check;
        }

        boolean test(UUID uuid) {
            if (!resolved) resolve();
            if (api == null) return false;
            try {
                return (Boolean) method.invoke(api, uuid);
            } catch (Throwable t) {
                return false;
            }
        }

        private synchronized void resolve() {
            if (resolved) return;
            try {
                Class<?> c = Class.forName(className);
                Object instance = c.getMethod(getter).invoke(null);
                if (instance == null) return;   // not initialised yet, try again on the next call
                method = c.getMethod(check, UUID.class);
                api = instance;
            } catch (Throwable t) {
                api = null;
            }
            resolved = true;
        }
    }
}
