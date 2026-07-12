package me.branduzzo.checkHacks.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record LangResultRow(String language, String response) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("language", language);
        m.put("response", response);
        return m;
    }
}
