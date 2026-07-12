package me.branduzzo.checkHacks;

import me.branduzzo.checkHacks.session.SignSession;

import java.util.Map;
import java.util.UUID;

public class LangCheckData {

    private final UUID targetUUID;
    private final UUID initiatorUUID;
    private final Map<String, String> languages;
    private SignSession signSession;

    public LangCheckData(UUID targetUUID, UUID initiatorUUID, Map<String, String> languages) {
        this.targetUUID    = targetUUID;
        this.initiatorUUID = initiatorUUID;
        this.languages     = languages;
    }

    public UUID getTargetUUID()                { return targetUUID; }
    public UUID getInitiatorUUID()             { return initiatorUUID; }
    public Map<String, String> getLanguages()  { return languages; }
    public SignSession getSignSession()        { return signSession; }
    public void setSignSession(SignSession s)  { this.signSession = s; }
}