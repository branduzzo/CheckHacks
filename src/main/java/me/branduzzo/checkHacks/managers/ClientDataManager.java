package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.ClientProfile;
import me.branduzzo.checkHacks.ClientType;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the client profile (brand, plugin channels, inferred client type) of
 * every player that is online. Profiles are filled on join and kept in sync by
 * the channel register/unregister events.
 */
public class ClientDataManager {

    private final Map<UUID, ClientProfile> profiles = new ConcurrentHashMap<>();

    public ClientProfile getOrCreate(Player player) {
        return getOrCreate(player.getUniqueId(), player.getName());
    }

    public ClientProfile getOrCreate(UUID uuid, String name) {
        return profiles.computeIfAbsent(uuid, id -> new ClientProfile(id, name));
    }

    public ClientProfile get(UUID uuid) { return profiles.get(uuid); }

    public Collection<ClientProfile> all() { return profiles.values(); }

    public void remove(UUID uuid) { profiles.remove(uuid); }

    public void clear() { profiles.clear(); }

    /* ------------------------------------------------------------------ */
    /*  small helpers, kept because older code used them directly          */
    /* ------------------------------------------------------------------ */

    public void setClientType(UUID uuid, ClientType type) {
        getOrCreate(uuid, "").setClientType(type);
    }

    public ClientType getClientType(UUID uuid) {
        ClientProfile profile = profiles.get(uuid);
        return profile == null ? ClientType.UNKNOWN : profile.getClientType();
    }
}
