package me.branduzzo.checkHacks.utils;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/** Tiny case insensitive glob matcher used by the client check rules. */
public final class GlobUtil {

    private GlobUtil() {}

    /**
     * Matches {@code input} against a glob pattern.
     * <ul>
     *   <li>{@code *} matches any amount of characters</li>
     *   <li>without wildcards the pattern also matches {@code pattern:anything}
     *       and {@code pattern/anything}, which is handy for plugin channels</li>
     * </ul>
     */
    public static boolean matches(String pattern, String input) {
        if (pattern == null || input == null) return false;
        String p = pattern.trim().toLowerCase(Locale.ROOT);
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty() || s.isEmpty()) return false;

        if (!p.contains("*") && !p.contains("?")) {
            if (s.equals(p)) return true;
            return s.startsWith(p + ":") || s.startsWith(p + "/") || s.startsWith(p + ".");
        }

        StringBuilder regex = new StringBuilder();
        for (char c : p.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default  -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return s.matches(regex.toString());
    }

    public static boolean matchesAny(Collection<String> patterns, String input) {
        if (patterns == null || input == null) return false;
        for (String pattern : patterns)
            if (matches(pattern, input)) return true;
        return false;
    }
}
