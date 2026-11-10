package me.branduzzo.checkHacks;

import me.branduzzo.checkHacks.utils.WrappedTask;
import me.branduzzo.checkHacks.utils.SignLayout;
import me.branduzzo.checkHacks.utils.SignPlacement;
import org.bukkit.Location;

import java.util.Map;
import java.util.UUID;

/**
 * Mutable state for one language probe and its current temporary sign.
 * Close acceptingResponse on the first reply and detach the lease before cleanup.
 */
public class LangCheckData {

    private final UUID targetUUID;
    private final UUID initiatorUUID;
    private final Map<String, String> languages;

    private SignPlacement.Lease signLease;
    private SignLayout layout = SignLayout.LANG_DEFAULT;
    private boolean acceptingResponse;
    private WrappedTask timeoutTask;

    public LangCheckData(UUID targetUUID, UUID initiatorUUID, Map<String, String> languages) {
        this.targetUUID    = targetUUID;
        this.initiatorUUID = initiatorUUID;
        this.languages     = languages;
    }

    public UUID getTargetUUID()                { return targetUUID; }
    public UUID getInitiatorUUID()             { return initiatorUUID; }
    public Map<String, String> getLanguages()  { return languages; }
    public Location getSignLocation() { return signLease == null ? null : signLease.location(); }
    public SignPlacement.Lease getSignLease() { return signLease; }
    public void setSignLease(SignPlacement.Lease lease) { signLease = lease; }
    public SignLayout getLayout() { return layout; }
    public void setLayout(SignLayout layout) { this.layout = layout; }
    public boolean isAcceptingResponse() { return acceptingResponse; }
    public void setAcceptingResponse(boolean accepting) { acceptingResponse = accepting; }
    public WrappedTask getTimeoutTask()         { return timeoutTask; }
    public void setTimeoutTask(WrappedTask t)   { this.timeoutTask = t; }
}
