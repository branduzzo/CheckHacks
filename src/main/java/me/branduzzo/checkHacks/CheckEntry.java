package me.branduzzo.checkHacks;

/** A single line of a {@link CheckReport} (a brand, a mod, a channel, ...). */
public class CheckEntry {

    private final String key;
    private final String name;
    private final String value;
    private final CheckOutcome outcome;

    public CheckEntry(String key, String name, String value, CheckOutcome outcome) {
        this.key     = key;
        this.name    = name;
        this.value   = value == null ? "" : value;
        this.outcome = outcome;
    }

    public String getKey()          { return key; }
    public String getName()         { return name; }
    public String getValue()        { return value; }
    public CheckOutcome getOutcome(){ return outcome; }
}
