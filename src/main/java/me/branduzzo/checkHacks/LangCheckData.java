package me.branduzzo.checkHacks;

import me.branduzzo.checkHacks.utils.WrappedTask;
import org.bukkit.Location;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class LangCheckData {

    private final UUID targetUUID;
    private final UUID initiatorUUID;
    private final Map<String, String> languages;

    private volatile Location signLocation;
    private final AtomicBoolean awaitingResponse = new AtomicBoolean(false);
    private WrappedTask timeoutTask;

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
    public boolean isAwaitingResponse()           { return awaitingResponse.get(); }
    public void setAwaitingResponse(boolean value) { awaitingResponse.set(value); }
    public boolean beginResponse()                { return awaitingResponse.compareAndSet(true, false); }
    public WrappedTask getTimeoutTask()         { return timeoutTask; }
    public void setTimeoutTask(WrappedTask t)   { this.timeoutTask = t; }
}