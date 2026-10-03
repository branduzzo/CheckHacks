package me.branduzzo.checkHacks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything we know about a client: its brand, its plugin channels and the
 * client type/loader we inferred from them. Filled on join and live updated
 * through the channel register/unregister events.
 */
public class ClientProfile {

    private final UUID uuid;
    private final Set<String> channels = ConcurrentHashMap.newKeySet();

    private volatile String name = "";
    private volatile String brand = "";
    private volatile ClientType clientType = ClientType.UNKNOWN;
    private volatile long firstSeen  = System.currentTimeMillis();
    private volatile long lastUpdate = firstSeen;

    public ClientProfile(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name == null ? "" : name;
    }

    public UUID getUuid()               { return uuid; }
    public String getName()             { return name; }
    public String getBrand()            { return brand; }
    public boolean hasBrand()           { return brand != null && !brand.isBlank(); }
    public ClientType getClientType()   { return clientType; }
    public long getFirstSeen()          { return firstSeen; }
    public long getLastUpdate()         { return lastUpdate; }
    public Set<String> getChannels()    { return Collections.unmodifiableSet(channels); }

    public List<String> getSortedChannels() {
        List<String> list = new ArrayList<>(channels);
        Collections.sort(list);
        return list;
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) this.name = name;
        touch();
    }

    public void setBrand(String brand) {
        if (brand == null) brand = "";
        if (!brand.isBlank() && !brand.equals(this.brand)) {
            this.brand = brand;
            touch();
        }
    }

    public void setClientType(ClientType type) {
        if (type != null && type != this.clientType) {
            this.clientType = type;
            touch();
        }
    }

    public void setChannels(Collection<String> newChannels) {
        channels.clear();
        if (newChannels != null) channels.addAll(newChannels);
        touch();
    }

    public void addChannel(String channel) {
        if (channel == null || channel.isBlank()) return;
        if (channels.add(channel)) touch();
    }

    public void removeChannel(String channel) {
        if (channel == null) return;
        if (channels.remove(channel)) touch();
    }

    public boolean hasChannel(String channel) {
        return channel != null && channels.contains(channel);
    }

    private void touch() {
        lastUpdate = System.currentTimeMillis();
    }
}
