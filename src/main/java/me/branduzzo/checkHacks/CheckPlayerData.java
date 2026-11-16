package me.branduzzo.checkHacks;

import me.branduzzo.checkHacks.utils.WrappedTask;
import org.bukkit.Location;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class CheckPlayerData {

    private final UUID targetUUID;
    private final UUID initiatorUUID;
    private final List<HackDefinition> allHacks;
    private final List<List<HackDefinition>> batches;
    private int currentBatch;
    private final Map<String, HackResult> results;
    private final boolean autoCheck;
    private final boolean confirmScan;
    private final String reason;
    private long scanId = -1;

    private volatile Location signLocation;
    private final AtomicBoolean awaitingResponse = new AtomicBoolean(false);
    private WrappedTask signTimeoutTask;

    public CheckPlayerData(UUID targetUUID, UUID initiatorUUID,
                           List<HackDefinition> allHacks,
                           List<List<HackDefinition>> batches,
                           boolean autoCheck, String reason, boolean confirmScan) {
        this.targetUUID    = targetUUID;
        this.initiatorUUID = initiatorUUID;
        this.allHacks      = allHacks;
        this.batches       = batches;
        this.currentBatch  = 0;
        this.results       = new LinkedHashMap<>();
        this.autoCheck     = autoCheck;
        this.confirmScan   = confirmScan;
        this.reason        = reason;
    }

    public UUID getTargetUUID()                        { return targetUUID; }
    public UUID getInitiatorUUID()                     { return initiatorUUID; }
    public List<HackDefinition> getAllHacks()           { return allHacks; }
    public List<List<HackDefinition>> getBatches()     { return batches; }
    public int getCurrentBatch()                       { return currentBatch; }
    public void incrementBatch()                       { currentBatch++; }
    public Map<String, HackResult> getResults()        { return results; }
    public boolean isAutoCheck()                       { return autoCheck; }
    public boolean isConfirmScan()                     { return confirmScan; }
    public String getReason()                          { return reason; }
    public boolean hasMoreBatches()                    { return currentBatch < batches.size(); }
    public List<HackDefinition> getCurrentBatchHacks() { return batches.get(currentBatch); }
    public long getScanId()                            { return scanId; }
    public void setScanId(long id)                     { this.scanId = id; }

    public Location getSignLocation()            { return signLocation; }
    public void setSignLocation(Location l)      { this.signLocation = l; }
    public boolean isAwaitingResponse()           { return awaitingResponse.get(); }
    public void setAwaitingResponse(boolean value) { awaitingResponse.set(value); }
    public boolean beginResponse()                { return awaitingResponse.compareAndSet(true, false); }
    public WrappedTask getSignTimeoutTask()       { return signTimeoutTask; }
    public void setSignTimeoutTask(WrappedTask t) { this.signTimeoutTask = t; }
}
