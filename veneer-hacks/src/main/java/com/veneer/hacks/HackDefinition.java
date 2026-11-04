package com.veneer.hacks;

import java.util.Locale;

public class HackDefinition {

    private final String id;
    private final String displayName;
    private final String key;
    private final DetectionMode mode;
    private final String fallback;
    private final String lowerKey;
    private final String lowerFallback;

    public HackDefinition(String id, String displayName, String key, DetectionMode mode) {
        this.id            = id;
        this.displayName   = displayName;
        this.key           = key;
        this.mode          = mode;
        this.fallback      = "\u27e6NO_" + id.toUpperCase(Locale.ROOT).replace("-", "_") + "\u27e7";
        this.lowerKey      = key.toLowerCase(Locale.ROOT);
        this.lowerFallback = this.fallback.toLowerCase(Locale.ROOT);
    }

    public String getId()           { return id; }
    public String getDisplayName()  { return displayName; }
    public String getKey()          { return key; }
    public DetectionMode getMode()  { return mode; }
    public String getFallback()     { return fallback; }
    public String getLowerKey()     { return lowerKey; }
    public String getLowerFallback(){ return lowerFallback; }
}