package me.branduzzo.checkHacks;

import me.branduzzo.checkHacks.session.SignSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class CheckPlayerData {

    private final UUID targetUUID;
    private final UUID initiatorUUID;
    private final List<List<HackDefinition>> batches;
    private int currentBatch;
    private final Map<String, HackResult> results;
    private final boolean autoCheck;
    private final String reason;
    private SignSession signSession;
    private final AtomicBoolean batchClaimed = new AtomicBoolean(false);

    public CheckPlayerData(UUID targetUUID, UUID initiatorUUID,
                           List<List<HackDefinition>> batches,
                           boolean autoCheck, String reason) {
        this.targetUUID    = targetUUID;
        this.initiatorUUID = initiatorUUID;
        this.batches       = batches;
        this.currentBatch  = 0;
        this.results       = new LinkedHashMap<>();
        this.autoCheck     = autoCheck;
        this.reason        = reason;
    }

    public UUID getTargetUUID()                        { return targetUUID; }
    public UUID getInitiatorUUID()                     { return initiatorUUID; }
    public List<List<HackDefinition>> getBatches()     { return batches; }
    public int getCurrentBatch()                       { return currentBatch; }
    public Map<String, HackResult> getResults()        { return results; }
    public boolean isAutoCheck()                       { return autoCheck; }
    public String getReason()                          { return reason; }
    public boolean hasMoreBatches()                    { return currentBatch < batches.size(); }

    public List<HackDefinition> getCurrentBatchHacks() {
        if (currentBatch < 0 || currentBatch >= batches.size()) return List.of();
        return batches.get(currentBatch);
    }

    public boolean claimCurrentBatch() {
        return batchClaimed.compareAndSet(false, true);
    }

    public void advanceBatch() {
        currentBatch++;
        batchClaimed.set(false);
    }

    public SignSession getSignSession() { return signSession; }
    public void setSignSession(SignSession session) { this.signSession = session; }
}