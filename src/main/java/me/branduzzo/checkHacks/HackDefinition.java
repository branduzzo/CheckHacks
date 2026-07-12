package me.branduzzo.checkHacks;

import java.util.List;

public class HackDefinition {

    private final String id;
    private final String displayName;
    private final String key;
    private final DetectionMode mode;
    private final String fallback;
    private final List<String> matchKeywords;

    public HackDefinition(String id, String displayName, String key, DetectionMode mode) {
        this(id, displayName, key, mode, List.of());
    }

    public HackDefinition(String id, String displayName, String key, DetectionMode mode,
                          List<String> matchKeywords) {
        this.id          = id;
        this.displayName = displayName;
        this.key         = key;
        this.mode        = mode;
        this.fallback    = "\u27e6NO_" + id.toUpperCase().replace("-", "_") + "\u27e7";
        this.matchKeywords = matchKeywords == null || matchKeywords.isEmpty()
                ? List.of()
                : List.copyOf(matchKeywords);
    }

    public String getId()          { return id; }
    public String getDisplayName() { return displayName; }
    public String getKey()         { return key; }
    public DetectionMode getMode() { return mode; }
    public String getFallback()    { return fallback; }
    public List<String> getMatchKeywords() { return matchKeywords; }
    public boolean hasMatchKeywords() { return !matchKeywords.isEmpty(); }
}