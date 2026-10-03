package me.branduzzo.checkHacks;

/** A known client brand: pattern matched against the raw brand string. */
public class BrandRule {

    private final String id;
    private final String pattern;
    private final String displayName;
    private final ClientType type;

    public BrandRule(String id, String pattern, String displayName, ClientType type) {
        this.id          = id;
        this.pattern     = pattern;
        this.displayName = displayName;
        this.type        = type;
    }

    public String getId()          { return id; }
    public String getPattern()     { return pattern; }
    public String getDisplayName() { return displayName; }
    public ClientType getType()    { return type; }
}
