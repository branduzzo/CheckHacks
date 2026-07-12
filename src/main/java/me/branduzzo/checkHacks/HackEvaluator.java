package me.branduzzo.checkHacks;

import java.util.Locale;

public final class HackEvaluator {

    private HackEvaluator() {}

    public static HackResult evaluate(HackDefinition hack, String resp, boolean exploitPreventer) {
        if (resp == null || resp.isEmpty()) return HackResult.NOT_DETECTED;

        String lower = resp.toLowerCase(Locale.ROOT).trim();
        String keyLower = hack.getKey() == null ? "" : hack.getKey().toLowerCase(Locale.ROOT);

        if (isUnresolvedLiteral(lower, keyLower)) {
            return switch (hack.getMode()) {
                case KEYBIND -> exploitPreventer ? HackResult.PROTECTED : HackResult.NOT_DETECTED;
                case TRANSLATE -> HackResult.PROTECTED;
                case METEOR -> HackResult.DETECTED;
            };
        }

        if (hack.hasMatchKeywords() && !matchesKeywords(lower, hack)) {
            return HackResult.NOT_DETECTED;
        }

        return switch (hack.getMode()) {
            case METEOR -> {
                if (lower.startsWith(hack.getFallback().toLowerCase(Locale.ROOT)))
                    yield HackResult.NOT_DETECTED;
                yield HackResult.DETECTED;
            }
            case TRANSLATE -> {
                if (lower.startsWith(hack.getFallback().toLowerCase(Locale.ROOT)))
                    yield HackResult.NOT_DETECTED;
                yield HackResult.DETECTED;
            }
            case KEYBIND -> HackResult.DETECTED;
        };
    }

    private static boolean matchesKeywords(String lowerResponse, HackDefinition hack) {
        for (String term : hack.getMatchKeywords()) {
            if (term == null || term.isBlank()) continue;
            if (lowerResponse.contains(term.toLowerCase(Locale.ROOT).trim())) return true;
        }
        return false;
    }

    private static boolean isUnresolvedLiteral(String lowerResponse, String keyLower) {
        if (!keyLower.isEmpty() && lowerResponse.equals(keyLower)) return true;
        if (lowerResponse.startsWith("key.") && lowerResponse.contains(".")) return true;
        return false;
    }
}
