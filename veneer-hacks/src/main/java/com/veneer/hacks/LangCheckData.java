package com.veneer.hacks;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.veneer.core.WrappedTask;
import org.bukkit.Location;
import org.bukkit.block.BlockState;

import java.util.Map;
import java.util.UUID;

public class LangCheckData {

    private final UUID targetUUID;
    private final UUID initiatorUUID;
    private final Map<String, String> languages;

    private Location signLocation;
    private BlockState originalState;
    private WrappedTask timeoutTask;
    private int deadWaits;
    private volatile String[] rawLines;
    private ClientVersion probedFor;

    /**
     * Counts one more wait (a second while the player cannot open a sign, or one sign asked again because the
     * player died, slept, changed world or moved away); the check gives up after 60 of them.
     */
    public int waitWhileBusy() { return ++deadWaits; }

    public LangCheckData(UUID targetUUID, UUID initiatorUUID, Map<String, String> languages) {
        this.targetUUID    = targetUUID;
        this.initiatorUUID = initiatorUUID;
        this.languages     = languages;
    }

    public UUID getTargetUUID()                { return targetUUID; }
    public UUID getInitiatorUUID()             { return initiatorUUID; }
    public Map<String, String> getLanguages()  { return languages; }
    public Location getSignLocation()          { return signLocation; }
    public void setSignLocation(Location l)    { this.signLocation = l; }
    public BlockState getOriginalState()       { return originalState; }
    public void setOriginalState(BlockState s) { this.originalState = s; }
    public WrappedTask getTimeoutTask()         { return timeoutTask; }
    public void setTimeoutTask(WrappedTask t)   { this.timeoutTask = t; }

    /** The sign lines as the client sent them, before Paper cuts them to 80 characters; null without PacketEvents. */
    public String[] getRawLines()               { return rawLines; }
    public void setRawLines(String[] lines)     { this.rawLines = lines; }

    /** The protocol version the version probe lines were built for; null when the sign carries none. */
    public ClientVersion getProbedFor()         { return probedFor; }
    public void setProbedFor(ClientVersion v)   { this.probedFor = v; }
}
