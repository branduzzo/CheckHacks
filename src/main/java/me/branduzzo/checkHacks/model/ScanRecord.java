package me.branduzzo.checkHacks.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ScanRecord(
        long id,
        String type,
        String targetName,
        String targetUuid,
        String checkerName,
        String reason,
        long timestamp,
        boolean hasDetected,
        List<?> results
) {
    public ScanRecord withResults(List<?> childResults) {
        return new ScanRecord(id, type, targetName, targetUuid, checkerName, reason,
                timestamp, hasDetected, childResults);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("type", type);
        map.put("target_name", targetName);
        map.put("target_uuid", targetUuid);
        map.put("checker_name", checkerName);
        map.put("reason", reason);
        map.put("timestamp", timestamp);
        map.put("has_detected", hasDetected ? 1 : 0);
        if (results != null) {
            List<Map<String, Object>> resultMaps = new ArrayList<>();
            for (Object r : results) {
                if (r instanceof HackResultRow h) resultMaps.add(h.toMap());
                else if (r instanceof LangResultRow l) resultMaps.add(l.toMap());
            }
            map.put("results", resultMaps);
        }
        return map;
    }
}
