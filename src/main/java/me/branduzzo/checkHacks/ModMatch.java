package me.branduzzo.checkHacks;

/** A mod that was matched from one of the client's plugin channels. */
public class ModMatch {

    private final String name;
    private final String pattern;
    private final String channel;
    private final boolean suspicious;

    public ModMatch(String name, String pattern, String channel, boolean suspicious) {
        this.name       = name;
        this.pattern    = pattern;
        this.channel    = channel;
        this.suspicious = suspicious;
    }

    public String getName()        { return name; }
    public String getPattern()     { return pattern; }
    public String getChannel()     { return channel; }
    public boolean isSuspicious()  { return suspicious; }
}
