package me.branduzzo.checkHacks.utils;

import net.kyori.adventure.text.Component;

import java.util.UUID;

/**
 * Adds a per-sign nonce and row index around the component the client resolves.
 * This detects stale replies, reordered rows and ordinary boundary edits. It
 * does not authenticate the contents: a modified client can retain the envelope
 * while fabricating a translation. Create a fresh instance for every sign.
 */
public final class SignChallenge {
    private final String nonce = UUID.randomUUID().toString().substring(0, 8);

    private String prefix(int row) { return "[" + nonce + row + ":"; }
    private String suffix(int row) { return ":" + row + "]"; }

    public Component wrap(int row, Component content) {
        return Component.text(prefix(row)).append(content).append(Component.text(suffix(row)));
    }

    /**
     * Returns all four decoded rows, or {@code null} if any envelope is invalid.
     * No trimming: even a space inserted outside the envelope is an edit.
     * Literal/blank row validation remains the caller's responsibility.
     */
    public String[] unwrap(String[] lines) {
        if (lines == null || lines.length != 4) return null;
        String[] result = new String[4];
        for (int row = 0; row < 4; row++) {
            String value = lines[row];
            String start = prefix(row), end = suffix(row);
            if (value == null || !value.startsWith(start) || !value.endsWith(end)
                    || value.length() < start.length() + end.length()) return null;
            result[row] = value.substring(start.length(), value.length() - end.length());
        }
        return result;
    }
}
