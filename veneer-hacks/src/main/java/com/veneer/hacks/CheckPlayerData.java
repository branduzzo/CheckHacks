package com.veneer.hacks;

import com.veneer.core.WrappedTask;
import org.bukkit.Location;
import org.bukkit.block.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CheckPlayerData {

    private final UUID targetUUID;
    private final UUID initiatorUUID;
    private final List<List<HackDefinition>> batches;
    private int currentBatch;
    private final Map<String, HackResult> results;
    private final boolean autoCheck;
    private final boolean confirmScan;
    private final String reason;
    private long scanId = -1;
    private boolean retried;
    private int deadWaits;

    /**
     * Counts one more wait (a second while the player cannot open a sign, or one sign asked again because the
     * player died, slept, changed world or moved away); the check gives up after 60 of them.
     */
    public int waitWhileBusy() { return ++deadWaits; }

    private Location signLocation;
    private BlockState originalState;
    private WrappedTask signTimeoutTask;
    private Location lastSignLocation;
    private List<HackDefinition> firstPassHacks;
    private Map<String, HackResult> firstPassResults;

    public CheckPlayerData(UUID targetUUID, UUID initiatorUUID,
                           List<List<HackDefinition>> batches,
                           boolean autoCheck, String reason) {
        this(targetUUID, initiatorUUID, batches, autoCheck, reason, false);
    }

    public CheckPlayerData(UUID targetUUID, UUID initiatorUUID,
                           List<List<HackDefinition>> batches,
                           boolean autoCheck, String reason, boolean confirmScan) {
        this.targetUUID    = targetUUID;
        this.initiatorUUID = initiatorUUID;
        this.batches       = batches;
        this.currentBatch  = 0;
        this.results       = new LinkedHashMap<>();
        this.autoCheck     = autoCheck;
        this.confirmScan   = confirmScan;
        this.reason        = reason;
    }

    public UUID getTargetUUID()                        { return targetUUID; }
    public UUID getInitiatorUUID()                     { return initiatorUUID; }
    public List<List<HackDefinition>> getBatches()     { return batches; }
    public int getCurrentBatch()                       { return currentBatch; }
    public void incrementBatch()                       { currentBatch++; retried = false; }
    public boolean isRetried()                         { return retried; }
    public void markRetried()                          { retried = true; }
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
    public BlockState getOriginalState()         { return originalState; }
    public void setOriginalState(BlockState s)   { this.originalState = s; }
    public WrappedTask getSignTimeoutTask()       { return signTimeoutTask; }
    public void setSignTimeoutTask(WrappedTask t) { this.signTimeoutTask = t; }
    public Location getLastSignLocation()         { return lastSignLocation; }
    public void setLastSignLocation(Location l)   { this.lastSignLocation = l; }

    /** On a confirmation scan: the first pass it re-checks, so the report covers every mod, not only the flagged ones. */
    public List<HackDefinition> getFirstPassHacks()        { return firstPassHacks; }
    public Map<String, HackResult> getFirstPassResults()   { return firstPassResults; }
    public void setFirstPass(List<HackDefinition> hacks, Map<String, HackResult> results) {
        this.firstPassHacks = hacks;
        this.firstPassResults = results;
    }
}
