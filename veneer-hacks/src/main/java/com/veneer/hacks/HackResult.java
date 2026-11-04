package com.veneer.hacks;

public enum HackResult {
    DETECTED,
    NOT_DETECTED,
    /** The sign exploit was blocked for this mod specifically (its own keybind echoed back unresolved). */
    PROTECTED,
    TIMEOUT,
    SKIPPED,
    /**
     * The translation/keybind check came back clean or {@link #PROTECTED}, but the player has a plugin channel
     * known to belong to this mod — a sign that an exploit preventer passed built-in keybinds through normally
     * (so the usual PROTECTED marker never triggered) while still hiding the mod's own keybind.
     */
    SUSPECTED
}
