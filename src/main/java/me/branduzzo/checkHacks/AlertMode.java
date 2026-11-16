package me.branduzzo.checkHacks;

import java.util.Locale;

public enum AlertMode {
    ALL,
    ONLY_DETECTED,
    OFF;

    public AlertMode next() {
        return switch (this) {
            case ALL -> ONLY_DETECTED;
            case ONLY_DETECTED -> OFF;
            case OFF -> ALL;
        };
    }

    public boolean wants(boolean flagged) {
        return this == ALL || (this == ONLY_DETECTED && flagged);
    }

    public static AlertMode parse(String raw, AlertMode fallback) {
        if (raw == null) return fallback;
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (value) {
            case "all", "on", "true", "enabled" -> ALL;
            case "only-detected", "onlydetected", "detected", "only-detect" -> ONLY_DETECTED;
            case "off", "false", "disabled", "none" -> OFF;
            default -> fallback;
        };
    }
}
