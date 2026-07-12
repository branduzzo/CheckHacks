package me.branduzzo.checkHacks.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record HackResultRow(String hackId, String hackName, String result) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hack_name", hackName);
        m.put("result", result);
        return m;
    }
}
