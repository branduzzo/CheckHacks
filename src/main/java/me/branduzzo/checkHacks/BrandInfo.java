package me.branduzzo.checkHacks;

/** Classification of a raw client brand string. */
public class BrandInfo {

    private final String raw;
    private final String displayName;
    private final ClientType type;
    private final boolean suspicious;
    private final String matchedPattern;
    private final boolean known;

    public BrandInfo(String raw, String displayName, ClientType type,
                     boolean suspicious, String matchedPattern, boolean known) {
        this.raw            = raw == null ? "" : raw;
        this.displayName    = displayName;
        this.type           = type;
        this.suspicious     = suspicious;
        this.matchedPattern = matchedPattern;
        this.known          = known;
    }

    public String getRaw()             { return raw; }
    public String getDisplayName()     { return displayName; }
    public ClientType getType()        { return type; }
    public boolean isSuspicious()      { return suspicious; }
    public String getMatchedPattern()  { return matchedPattern; }
    public boolean isKnown()           { return known; }
}
