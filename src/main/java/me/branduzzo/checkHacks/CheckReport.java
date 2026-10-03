package me.branduzzo.checkHacks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Result of an instant client check (brand, mod list, ...).
 * It is generic on purpose: every check type fills the same structure so the
 * database, the web editor and the Discord webhook only need one code path.
 */
public class CheckReport {

    private final CheckType type;
    private final UUID targetUUID;
    private final String targetName;
    private final String checkerName;
    private final String reason;
    private final List<CheckEntry> entries = new ArrayList<>();
    private final long timestamp = System.currentTimeMillis();
    private long scanId = -1;

    public CheckReport(CheckType type, UUID targetUUID, String targetName,
                       String checkerName, String reason) {
        this.type        = type;
        this.targetUUID  = targetUUID;
        this.targetName  = targetName;
        this.checkerName = checkerName;
        this.reason      = reason;
    }

    public CheckReport add(String key, String name, String value, CheckOutcome outcome) {
        entries.add(new CheckEntry(key, name, value, outcome));
        return this;
    }

    public CheckType getType()             { return type; }
    public UUID getTargetUUID()            { return targetUUID; }
    public String getTargetName()          { return targetName; }
    public String getCheckerName()         { return checkerName; }
    public String getReason()              { return reason; }
    public List<CheckEntry> getEntries()   { return entries; }
    public long getTimestamp()             { return timestamp; }
    public long getScanId()                { return scanId; }
    public void setScanId(long scanId)     { this.scanId = scanId; }

    public CheckEntry getEntry(String key) {
        for (CheckEntry entry : entries)
            if (entry.getKey().equals(key)) return entry;
        return null;
    }

    public boolean hasFlagged() {
        for (CheckEntry entry : entries)
            if (entry.getOutcome().isFlagged()) return true;
        return false;
    }

    public List<CheckEntry> getFlagged() {
        return entries.stream().filter(e -> e.getOutcome().isFlagged()).collect(Collectors.toList());
    }

    /** Only the flagged entry names, comma separated (used for commands/alerts). */
    public String flaggedText() {
        return getFlagged().stream().map(CheckEntry::getName).collect(Collectors.joining(", "));
    }

    /** Value of the first flagged entry, or an empty string. */
    public String flaggedValue() {
        for (CheckEntry entry : getFlagged()) return entry.getValue();
        return "";
    }

    /** Full report, one line per entry - used by the Discord webhook. */
    public String resultText() {
        StringBuilder sb = new StringBuilder();
        for (CheckEntry entry : entries) {
            sb.append(entry.getName()).append(": ")
              .append(entry.getValue().isBlank() ? "-" : entry.getValue())
              .append(" (").append(entry.getOutcome().name()).append(")\n");
        }
        return sb.toString().trim();
    }
}
