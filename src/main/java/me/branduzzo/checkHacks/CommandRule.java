package me.branduzzo.checkHacks;

import java.util.List;

/**
 * One result-specific action rule. Commands are copied on construction so a
 * configuration reload cannot mutate a list already queued for execution.
 * The single-command constructor and getter preserve the original Java API.
 */
public class CommandRule {

    private final String name;
    private final String mod;
    private final HackResult result;
    private final List<String> commands;

    public CommandRule(String name, String mod, HackResult result, String command) {
        this(name, mod, result, List.of(command));
    }

    public CommandRule(String name, String mod, HackResult result, List<String> commands) {
        this.name    = name;
        this.mod     = mod;
        this.result  = result;
        this.commands = List.copyOf(commands);
    }

    public String getName()        { return name; }
    public String getMod()         { return mod; }
    public HackResult getResult()  { return result; }
    /** Retained for callers that used the original single-command rule API. */
    public String getCommand()     { return commands.isEmpty() ? "" : commands.getFirst(); }
    /** Immutable commands in their configured console execution order. */
    public List<String> getCommands() { return commands; }
}
