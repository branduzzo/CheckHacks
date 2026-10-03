package me.branduzzo.checkHacks;

/** Rule executed when a brand or mod list entry matches. */
public class ClientCommandRule {

    private final String name;
    private final CheckType check;
    private final String match;
    private final String command;

    public ClientCommandRule(String name, CheckType check, String match, String command) {
        this.name    = name;
        this.check   = check;
        this.match   = match;
        this.command = command;
    }

    public String getName()     { return name; }
    public CheckType getCheck() { return check; }
    public String getMatch()    { return match; }
    public String getCommand()  { return command; }
}
