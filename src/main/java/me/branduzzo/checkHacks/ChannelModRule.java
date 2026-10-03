package me.branduzzo.checkHacks;

/** Maps a plugin channel (wildcards allowed) to a mod name. */
public class ChannelModRule {

    private final String id;
    private final String pattern;
    private final String displayName;
    private final boolean suspicious;

    public ChannelModRule(String id, String pattern, String displayName, boolean suspicious) {
        this.id          = id;
        this.pattern     = pattern;
        this.displayName = displayName;
        this.suspicious  = suspicious;
    }

    public String getId()          { return id; }
    public String getPattern()     { return pattern; }
    public String getDisplayName() { return displayName; }
    public boolean isSuspicious()  { return suspicious; }
}
