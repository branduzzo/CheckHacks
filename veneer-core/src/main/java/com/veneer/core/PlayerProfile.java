package com.veneer.core;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What Veneer knows about one online player's client: brand, registered plugin channels and the
 * spoof-detection bookkeeping. Created on first contact, dropped on quit. Modules share it, so the
 * mod scan, placeholders and {@code /veneer info} all see the same brand and channels.
 */
public class PlayerProfile {
    private final Set<String> channels = ConcurrentHashMap.newKeySet();
    private volatile String brand;
    private volatile boolean brandAlerted;
    private volatile boolean alreadyPunished;
    private final long joinTime = System.currentTimeMillis();
    private volatile String versionReal;
    private volatile String versionClaimed;
    private volatile boolean versionMismatch;

    /** An unmodifiable view of the plugin channels the client registered. */
    public Set<String> getChannels() {
        return Collections.unmodifiableSet(channels);
    }

    public void addChannel(String channel) { channels.add(channel); }

    public void removeChannel(String channel) { channels.remove(channel); }

    /** The client brand, or null while none has been received. */
    public String getBrand() { return brand; }

    public void setBrand(String brand) { this.brand = brand; }

    public boolean isBrandAlerted() { return brandAlerted; }

    public void setBrandAlerted(boolean brandAlerted) { this.brandAlerted = brandAlerted; }

    public boolean isAlreadyPunished() { return alreadyPunished; }

    public void setAlreadyPunished(boolean punished) { this.alreadyPunished = punished; }

    /** When the profile was created, in epoch milliseconds. */
    public long getJoinTime() { return joinTime; }

    /** The real version range read by the language-check sign, or null when it has not run. */
    public String getVersionReal()      { return versionReal; }
    /** The protocol version the client connected with when the probe ran. */
    public String getVersionClaimed()   { return versionClaimed; }
    public boolean isVersionMismatch()  { return versionMismatch; }

    public void setVersionProbe(String real, String claimed, boolean mismatch) {
        this.versionReal = real;
        this.versionClaimed = claimed;
        this.versionMismatch = mismatch;
    }

    /** Loader family guessed from the registered channels. */
    public ClientType clientType() {
        for (String ch : channels) {
            String lower = ch.toLowerCase(Locale.ROOT);
            if (lower.startsWith("fabric") || lower.contains("fabric-api")
                    || lower.contains("fabric-networking") || lower.contains("fabric-screen"))
                return ClientType.FABRIC;
        }
        for (String ch : channels) {
            String lower = ch.toLowerCase(Locale.ROOT);
            if (lower.startsWith("fml") || lower.startsWith("forge")
                    || lower.contains("forge:") || lower.contains("fml:"))
                return ClientType.FORGE;
        }
        return channels.isEmpty() ? ClientType.VANILLA : ClientType.UNKNOWN;
    }
}
