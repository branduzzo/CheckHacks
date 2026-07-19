package me.branduzzo.checkHacks;

public class HackDefinition {

    private final String id;
    private final String displayName;
    private final String key;
    private final String message;
    private final DetectionMode mode;

    public HackDefinition(String id, String displayName, String key, String message, DetectionMode mode) {
        this.id = id;
        this.displayName = displayName;
        this.key = key;
        this.message = message;
        this.mode = mode;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getKey() { return key; }
    public String getMessage() { return message; }
    public DetectionMode getMode() { return mode; }

    public String getFallback() { return displayName; }
}