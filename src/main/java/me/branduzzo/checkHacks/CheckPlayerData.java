package me.branduzzo.checkHacks;

import me.branduzzo.checkHacks.utils.WrappedTask;
import me.branduzzo.checkHacks.utils.SignPlacement;
import org.bukkit.Location;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable state for one hack scan and its current temporary sign.
 * Close acceptingResponse on the first reply and detach the lease before cleanup.
 */
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

    private SignPlacement.Lease signLease;
    private boolean acceptingResponse;
    private WrappedTask signTimeoutTask;

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
    public void incrementBatch()                       { currentBatch++; }
    public Map<String, HackResult> getResults()        { return results; }
    public boolean isAutoCheck()                       { return autoCheck; }
    public boolean isConfirmScan()                     { return confirmScan; }
    public String getReason()                          { return reason; }
    public boolean hasMoreBatches()                    { return currentBatch < batches.size(); }
    public List<HackDefinition> getCurrentBatchHacks() { return batches.get(currentBatch); }
    public long getScanId()                            { return scanId; }
    public void setScanId(long id)                     { this.scanId = id; }

    public Location getSignLocation() { return signLease == null ? null : signLease.location(); }
    public SignPlacement.Lease getSignLease() { return signLease; }
    public void setSignLease(SignPlacement.Lease lease) { signLease = lease; }
    public boolean isAcceptingResponse() { return acceptingResponse; }
    public void setAcceptingResponse(boolean accepting) { acceptingResponse = accepting; }
    public WrappedTask getSignTimeoutTask()       { return signTimeoutTask; }
    public void setSignTimeoutTask(WrappedTask t) { this.signTimeoutTask = t; }
}
