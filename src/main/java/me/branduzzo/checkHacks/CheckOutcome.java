package me.branduzzo.checkHacks;

/**
 * Generic result of a single entry inside a {@link CheckReport}.
 * The classic hack check still uses {@link HackResult}, this enum is used by
 * the newer client checks (brand, mod list) where results are informational
 * more often than not.
 */
public enum CheckOutcome {

    DETECTED(true),
    PROTECTED(true),
    CLEAN(false),
    INFO(false),
    UNKNOWN(false),
    SKIPPED(false);

    private final boolean flagged;

    CheckOutcome(boolean flagged) {
        this.flagged = flagged;
    }

    public boolean isFlagged() { return flagged; }
}
