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
    static final int MAX_CHANNELS = 1024;
    static final int MAX_CHANNEL_LENGTH = 256;

    private final Set<String> channels = ConcurrentHashMap.newKeySet();
    private volatile String brand;
    private volatile boolean brandAlerted;
    private volatile boolean alreadyPunished;
    private final long joinTime = System.currentTimeMillis();
    private volatile String versionReal;
    private volatile String versionClaimed;
    private volatile boolean versionMismatch;
    private volatile boolean fakePlayer;
    private volatile boolean exploitPreventer;

    private volatile boolean signAnswered;
    private volatile boolean languageAnswered;
    private volatile String language;

    /** The client answered a probe sign with a resolved control line, so the checks below that rely on it ran. */
    public void markSignAnswered() { signAnswered = true; }

    /** The language check got an answer; {@code language} is null when it named no known language. */
    public void setLanguageProbe(String language) {
        this.languageAnswered = true;
        this.language = language;
    }

    public String getLanguage() { return language; }

    public Verdict exploitPreventerVerdict() { return Verdict.of(exploitPreventer, signAnswered); }
    public Verdict fakePlayerVerdict()       { return Verdict.of(fakePlayer, signAnswered); }
    public Verdict languageVerdict()         { return Verdict.of(language != null, languageAnswered); }
    /** DETECTED: the real client version does not fit the protocol it connects with. */
    public Verdict protocolVerdict()         { return Verdict.of(versionMismatch, versionReal != null); }
    /** NOT_DETECTED: the client has been online for a while and sent none. */
    public Verdict brandVerdict() {
        return Verdict.of(brand != null, System.currentTimeMillis() - joinTime > 10_000);
    }

    public boolean isFakePlayer() { return fakePlayer; }
    public void setFakePlayer(boolean fakePlayer) { this.fakePlayer = fakePlayer; }

    public boolean isExploitPreventer() { return exploitPreventer; }
    public void setExploitPreventer(boolean exploitPreventer) { this.exploitPreventer = exploitPreventer; }

    /** An unmodifiable view of the plugin channels the client registered. */
    public Set<String> getChannels() {
        return Collections.unmodifiableSet(channels);
    }

    /**
     * Records a channel the client registered; false when it was known already or over a limit. The client chooses
     * these, so their number and length are capped well above what real modpacks use.
     */
    public boolean addChannel(String channel) {
        if (channel.length() > MAX_CHANNEL_LENGTH || channels.size() >= MAX_CHANNELS) return false;
        return channels.add(channel);
    }

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
