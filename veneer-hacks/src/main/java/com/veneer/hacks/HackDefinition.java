package com.veneer.hacks;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class HackDefinition {

    private final String id;
    private final String displayName;
    private final String key;
    private final DetectionMode mode;
    private final String fallback;
    private final String lowerKey;
    private final String lowerFallback;
    private final List<Pattern> channels;
    private final int level;

    public HackDefinition(String id, String displayName, String key, DetectionMode mode) {
        this(id, displayName, key, mode, List.of(), 1);
    }

    public HackDefinition(String id, String displayName, String key, DetectionMode mode, List<String> channels) {
        this(id, displayName, key, mode, channels, 1);
    }

    /**
     * @param channels plugin-channel regexes known to belong to this mod (optional). A channel match on a
     *                  clean or protected translation result raises it to {@link HackResult#SUSPECTED}: channel
     *                  registration happens at login handshake, before client-side logic can selectively hide it,
     *                  so it survives a translation/keybind exploit-preventer that the sign check alone misses.
     * @param level    how serious the mod is: 1 = cheat, 2 = forbidden mod, 3 = tolerated (see config.yml)
     */
    public HackDefinition(String id, String displayName, String key, DetectionMode mode, List<String> channels, int level) {
        this.id            = id;
        this.level         = Math.max(1, Math.min(3, level));
        this.displayName   = displayName;
        this.key           = key;
        this.mode          = mode;
        this.fallback      = "\u27e6NO_" + id.toUpperCase(Locale.ROOT).replace("-", "_") + "\u27e7";
        this.lowerKey      = key.toLowerCase(Locale.ROOT);
        this.lowerFallback = this.fallback.toLowerCase(Locale.ROOT);
        this.channels      = channels.stream().flatMap(p -> {
            try { return java.util.stream.Stream.of(Pattern.compile(p, Pattern.CASE_INSENSITIVE)); }
            catch (PatternSyntaxException e) { return java.util.stream.Stream.empty(); }
        }).toList();
    }

    public String getId()           { return id; }
    public String getDisplayName()  { return displayName; }
    public String getKey()          { return key; }
    public DetectionMode getMode()  { return mode; }
    public String getFallback()     { return fallback; }
    public String getLowerKey()     { return lowerKey; }
    public String getLowerFallback(){ return lowerFallback; }
    /** 1 = cheat, 2 = forbidden mod, 3 = tolerated. */
    public int getLevel()           { return level; }

    /** Whether any of this hack's known channels is among the player's registered plugin channels. */
    public boolean matchesAnyChannel(java.util.Collection<String> playerChannels) {
        if (channels.isEmpty() || playerChannels.isEmpty()) return false;
        for (String channel : playerChannels)
            for (Pattern p : channels)
                if (p.matcher(channel).find()) return true;
        return false;
    }
}