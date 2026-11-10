package com.veneer.core.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small tables staff and players set: client brand verdicts and per-player message languages. */
public final class SettingsStore {

    private final Database db;

    SettingsStore(Database db) {
        this.db = db;
    }

    /** All rows of {@code brand_verdicts}, oldest first. */
    public List<Map<String, Object>> getBrandVerdicts() {
        return db.read("getBrandVerdicts", new ArrayList<>(), c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM brand_verdicts ORDER BY id")) {
                return Rows.all(ps);
            }
        });
    }

    public void saveBrandVerdict(int id, String brand, String verdict, String decidedBy, long decidedAt,
                                 long firstSeen, String lastPlayer) {
        db.write("saveBrandVerdict", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO brand_verdicts (id, brand, verdict, decided_by, decided_at, first_seen, last_player)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET verdict=excluded.verdict, decided_by=excluded.decided_by,
                        decided_at=excluded.decided_at, last_player=excluded.last_player""")) {
                ps.setInt(1, id);
                ps.setString(2, brand);
                ps.setString(3, verdict);
                ps.setString(4, decidedBy);
                if (decidedAt > 0) ps.setLong(5, decidedAt); else ps.setNull(5, Types.INTEGER);
                ps.setLong(6, firstSeen);
                ps.setString(7, lastPlayer);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void deleteBrandVerdicts(java.util.Collection<Integer> ids) {
        if (ids.isEmpty()) return;
        db.transaction("deleteBrandVerdicts", null, c -> {
            Rows.updateIn(c, "DELETE FROM brand_verdicts WHERE id IN (%s)", ids);
            return null;
        });
    }

    /** {@code /veneer lang} choices: uuid to language code or {@code auto}. */
    public Map<String, String> getLanguageChoices() {
        return db.read("getLanguageChoices", new HashMap<>(), c -> {
            Map<String, String> map = new HashMap<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT uuid, language FROM player_language")) {
                while (rs.next()) map.put(rs.getString(1), rs.getString(2));
            }
            return map;
        });
    }

    /** Stores a player's language choice; null deletes it. */
    public void saveLanguageChoice(String uuid, String language) {
        db.write("saveLanguageChoice", null, c -> {
            try (PreparedStatement ps = c.prepareStatement(language == null
                    ? "DELETE FROM player_language WHERE uuid = ?"
                    : "INSERT INTO player_language (uuid, language) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET language = excluded.language")) {
                ps.setString(1, uuid);
                if (language != null) ps.setString(2, language);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
