package me.branduzzo.checkHacks.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record EditorTokenInfo(String playerUuid, String playerName) {
    public Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("player_uuid", playerUuid);
        m.put("player_name", playerName);
        return m;
    }
}
