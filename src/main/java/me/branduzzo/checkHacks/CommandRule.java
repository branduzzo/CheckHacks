package me.branduzzo.checkHacks;

public class CommandRule {

    private final String name;
    private final String mod;
    private final HackResult result;
    private final String command;

    public CommandRule(String name, String mod, HackResult result, String command) {
        this.name    = name;
        this.mod     = mod;
        this.result  = result;
        this.command = command;
    }

    public String getName()        { return name; }
    public String getMod()         { return mod; }
    public HackResult getResult()  { return result; }
    public String getCommand()     { return command; }
}
