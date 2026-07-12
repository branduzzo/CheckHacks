package me.branduzzo.checkHacks.model;

import me.branduzzo.checkHacks.HackDefinition;
import me.branduzzo.checkHacks.HackResult;

import java.util.List;
import java.util.Map;

public record CheckOutcome(
        String targetName,
        String targetUuid,
        String checkerName,
        String reason,
        List<HackDefinition> hacks,
        Map<String, HackResult> results,
        boolean anyDetected,
        boolean anyProtected,
        boolean allClean
) {
    public static CheckOutcome from(
            String targetName,
            String targetUuid,
            String checkerName,
            String reason,
            List<HackDefinition> hacks,
            Map<String, HackResult> results) {

        boolean anyDetected = false;
        boolean anyProtected = false;
        boolean allClean = true;
        for (HackDefinition hack : hacks) {
            HackResult r = results.getOrDefault(hack.getId(), HackResult.SKIPPED);
            if (r == HackResult.DETECTED) {
                anyDetected = true;
                allClean = false;
            }
            if (r == HackResult.PROTECTED) {
                anyProtected = true;
                allClean = false;
            }
            if (r == HackResult.SKIPPED) allClean = false;
        }
        return new CheckOutcome(targetName, targetUuid, checkerName, reason,
                hacks, results, anyDetected, anyProtected, allClean);
    }

    public String resultText() {
        StringBuilder sb = new StringBuilder();
        for (HackDefinition hack : hacks) {
            HackResult r = results.getOrDefault(hack.getId(), HackResult.SKIPPED);
            sb.append(hack.getDisplayName()).append(": ").append(r.name()).append('\n');
        }
        return sb.toString().trim();
    }

    public List<HackResultRow> toRows() {
        return hacks.stream()
                .map(h -> new HackResultRow(
                        h.getId(),
                        h.getDisplayName(),
                        results.getOrDefault(h.getId(), HackResult.SKIPPED).name()))
                .toList();
    }
}
