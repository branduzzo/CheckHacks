package com.veneer.core.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mod scans ("hack") and language checks ("lang") with their per-mod / per-key results. */
public final class ScanStore {

    private final Database db;

    ScanStore(Database db) {
        this.db = db;
    }

    // ------------------------------------------------------------------ writes

    /** A scan without results; its id, or -1. */
    public long saveScan(String type, String targetName, String targetUUID, String checkerName, String reason, boolean hasDetected) {
        return db.write("saveScan", -1L, c -> insertScan(c, type, targetName, targetUUID, checkerName, reason, hasDetected));
    }

    /** A mod scan and its results ({@code {hack_id, hack_name, result}} each) in one transaction; its id, or -1. */
    public long saveScanWithResults(String type, String targetName, String targetUUID, String checkerName, String reason,
                                    boolean hasDetected, List<Object[]> hackResults) {
        return db.transaction("saveScanWithResults", -1L, c -> {
            long scanId = insertScan(c, type, targetName, targetUUID, checkerName, reason, hasDetected);
            if (scanId == -1) throw new SQLException("no scan id");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO hack_results (scan_id,hack_id,hack_name,result) VALUES (?,?,?,?)")) {
                for (Object[] r : hackResults) {
                    ps.setLong(1, scanId);
                    ps.setString(2, (String) r[0]);
                    ps.setString(3, (String) r[1]);
                    ps.setString(4, (String) r[2]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return scanId;
        });
    }

    /** A language check and its one result in one transaction; its id, or -1. */
    public long saveLangScan(String targetName, String targetUUID, String checkerName, String reason,
                             String language, String response) {
        return db.transaction("saveLangScan", -1L, c -> {
            long scanId = insertScan(c, "lang", targetName, targetUUID, checkerName, reason, false);
            if (scanId == -1) throw new SQLException("no scan id");
            insertLangResult(c, scanId, language, response);
            return scanId;
        });
    }

    public void saveHackResult(long scanId, String hackId, String hackName, String result) {
        db.write("saveHackResult", null, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO hack_results (scan_id,hack_id,hack_name,result) VALUES (?,?,?,?)")) {
                ps.setLong(1, scanId);
                ps.setString(2, hackId);
                ps.setString(3, hackName);
                ps.setString(4, result);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void saveLangResult(long scanId, String language, String response) {
        db.write("saveLangResult", null, c -> {
            insertLangResult(c, scanId, language, response);
            return null;
        });
    }

    public boolean deleteScan(long id) {
        return db.write("deleteScan", false, c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM scans WHERE id=?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    private static long insertScan(Connection c, String type, String targetName, String targetUUID,
                                   String checkerName, String reason, boolean hasDetected) throws SQLException {
        long now = System.currentTimeMillis();
        long id = -1;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO scans (type,target_name,target_uuid,checker_name,reason,timestamp,has_detected) VALUES (?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type);
            ps.setString(2, targetName);
            ps.setString(3, targetUUID);
            ps.setString(4, checkerName);
            ps.setString(5, reason);
            ps.setLong(6, now);
            ps.setInt(7, hasDetected ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) id = rs.getLong(1);
            }
        }
        Rows.touchAccount(c, targetUUID, targetName, now);
        return id;
    }

    private static void insertLangResult(Connection c, long scanId, String language, String response) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO lang_results (scan_id,language,response) VALUES (?,?,?)")) {
            ps.setLong(1, scanId);
            ps.setString(2, language);
            ps.setString(3, response);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------- reads

    public List<Map<String, Object>> getRecentScans(String type, int limit) {
        return getRecentScans(type, limit, 0, null, null);
    }

    /**
     * A page of the scan history, newest first: {@code type} "hack", "lang" or null for both, {@code detected} true
     * keeps scans that found a mod, false the clean ones, null all, {@code player} (uuid or name) keeps one
     * account's scans. Each row carries its {@code results}.
     */
    public List<Map<String, Object>> getRecentScans(String type, int limit, int offset, Boolean detected, String player) {
        StringBuilder sql = new StringBuilder("SELECT * FROM scans WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (type != null) { sql.append(" AND type = ?"); args.add(type); }
        if (detected != null) sql.append(detected ? " AND has_detected = 1" : " AND has_detected = 0");
        if (player != null) {
            sql.append(" AND (target_uuid = ? OR LOWER(target_name) = ?)");
            args.add(player);
            args.add(Rows.lower(player));
        }
        sql.append(" ORDER BY timestamp DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(Math.max(0, offset));
        return db.read("getRecentScans", new ArrayList<>(), c -> {
            List<Map<String, Object>> rows;
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
                rows = Rows.all(ps);
            }
            attachResults(c, rows);
            return rows;
        });
    }

    public Map<String, Object> getScan(long id) {
        return db.read("getScan", null, c -> {
            List<Map<String, Object>> rows;
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM scans WHERE id=?")) {
                ps.setLong(1, id);
                rows = Rows.all(ps);
            }
            if (rows.isEmpty()) return null;
            attachResults(c, rows);
            return rows.get(0);
        });
    }

    /** Latest 100 scans of an account: by uuid when it is known (names change hands), else by name. */
    public List<Map<String, Object>> getPlayerScans(String uuid, String playerName) {
        boolean byUuid = uuid != null && !uuid.isEmpty();
        if (!byUuid && playerName == null) return new ArrayList<>();
        return db.read("getPlayerScans", new ArrayList<>(), c -> {
            List<Map<String, Object>> rows;
            try (PreparedStatement ps = c.prepareStatement(byUuid
                    ? "SELECT * FROM scans WHERE target_uuid=? ORDER BY timestamp DESC LIMIT 100"
                    : "SELECT * FROM scans WHERE LOWER(target_name)=? ORDER BY timestamp DESC LIMIT 100")) {
                ps.setString(1, byUuid ? uuid : Rows.lower(playerName));
                rows = Rows.all(ps);
            }
            attachResults(c, rows);
            return rows;
        });
    }

    /**
     * Puts each scan's results under {@code results}. Two queries for the whole page (mod results, language
     * results) instead of one per scan: a page of 100 scans used to cost 101 queries.
     */
    private static void attachResults(Connection c, List<Map<String, Object>> rows) throws SQLException {
        if (rows.isEmpty()) return;
        List<Long> hackIds = new ArrayList<>(), langIds = new ArrayList<>();
        Map<Long, List<Map<String, Object>>> byScan = new HashMap<>();
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            List<Map<String, Object>> results = new ArrayList<>();
            byScan.put(id, results);
            row.put("results", results);
            ("hack".equals(row.get("type")) ? hackIds : langIds).add(id);
        }
        Rows.eachIn(c, "SELECT scan_id, hack_name, result FROM hack_results WHERE scan_id IN (%s) ORDER BY id", hackIds, rs -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("hack_name", rs.getString(2));
            r.put("result", rs.getString(3));
            byScan.get(rs.getLong(1)).add(r);
        });
        Rows.eachIn(c, "SELECT scan_id, language, response FROM lang_results WHERE scan_id IN (%s) ORDER BY id", langIds, rs -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("language", rs.getString(2));
            r.put("response", rs.getString(3));
            byScan.get(rs.getLong(1)).add(r);
        });
    }

    /** Counts for the panel's overview: scans by type, players whose latest mod scan found something, known accounts. */
    public Map<String, Object> getStats() {
        return db.read("getStats", new LinkedHashMap<>(), c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            // One pass over the (type, has_detected) data instead of three COUNT(*) scans of the table
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("""
                    SELECT COALESCE(SUM(type = 'hack'), 0), COALESCE(SUM(type = 'lang'), 0),
                           COALESCE(SUM(type = 'hack' AND has_detected = 1), 0)
                    FROM scans""")) {
                rs.next();
                out.put("hack_scans", rs.getLong(1));
                out.put("lang_scans", rs.getLong(2));
                out.put("detected_scans", rs.getLong(3));
            }
            out.put("detected_players", count(c, """
                    SELECT COUNT(*) FROM scans
                    WHERE id IN (SELECT MAX(id) FROM scans WHERE type = 'hack' GROUP BY target_uuid) AND has_detected = 1"""));
            // Known accounts: the player list's table, instead of de-duplicating joins and scans on every request
            out.put("accounts", count(c, "SELECT COUNT(*) FROM accounts"));
            out.put("fingerprints", count(c, "SELECT COUNT(*) FROM rptrack"));
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM scans WHERE timestamp > ?")) {
                ps.setLong(1, System.currentTimeMillis() - 86_400_000L);
                try (ResultSet rs = ps.executeQuery()) { out.put("scans_today", rs.next() ? rs.getLong(1) : 0L); }
            }
            return out;
        });
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** Mods found by each player's latest mod scan (a later clean scan clears the player): one entry per mod, most players first. */
    public List<Map<String, Object>> getDetectedMods() {
        return db.read("getDetectedMods", new ArrayList<>(), c -> {
            Map<String, Map<String, Object>> byMod = new LinkedHashMap<>();
            // Latest scans first (from idx_scans_type_target); only those that found something (has_detected is set
            // exactly when a stored result is DETECTED) have their results read
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("""
                    WITH latest AS (SELECT MAX(id) AS id FROM scans WHERE type = 'hack' GROUP BY target_uuid)
                    SELECT r.hack_id, r.hack_name, s.target_name, s.target_uuid, s.timestamp
                    FROM latest l
                    JOIN scans s ON s.id = l.id AND s.has_detected = 1
                    JOIN hack_results r ON r.scan_id = s.id
                    WHERE r.result = 'DETECTED'
                    GROUP BY r.hack_id, s.target_uuid
                    ORDER BY s.timestamp DESC""")) {
                while (rs.next()) {
                    Map<String, Object> mod = byMod.computeIfAbsent(rs.getString(1), k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", k);
                        m.put("players", new ArrayList<Map<String, Object>>());
                        return m;
                    });
                    mod.put("name", rs.getString(2));
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> players = (List<Map<String, Object>>) mod.get("players");
                    Map<String, Object> pl = new LinkedHashMap<>();
                    pl.put("name", rs.getString(3));
                    pl.put("uuid", rs.getString(4));
                    pl.put("last_seen", rs.getLong(5));
                    players.add(pl);
                }
            }
            List<Map<String, Object>> out = new ArrayList<>(byMod.values());
            for (Map<String, Object> m : out) m.put("count", ((List<?>) m.get("players")).size());
            out.sort((a, b) -> Integer.compare((Integer) b.get("count"), (Integer) a.get("count")));
            return out;
        });
    }

    /** Mods ever found on one account, latest first: name and when. */
    public List<Map<String, Object>> getDetectedModsOf(String uuid) {
        return db.read("getDetectedModsOf", new ArrayList<>(), c -> {
            List<Map<String, Object>> mods = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT r.hack_name, MAX(s.timestamp) AS ts
                    FROM scans s JOIN hack_results r ON r.scan_id = s.id
                    WHERE s.target_uuid = ? AND r.result = 'DETECTED'
                    GROUP BY r.hack_id ORDER BY ts DESC""")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", rs.getString(1));
                        m.put("last_seen", rs.getLong(2));
                        mods.add(m);
                    }
                }
            }
            return mods;
        });
    }

    /** Accounts whose latest mod scan found something. */
    java.util.Set<String> detectedAccounts(Connection c) throws SQLException {
        java.util.Set<String> detected = new java.util.HashSet<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("""
                SELECT target_uuid FROM scans
                WHERE id IN (SELECT MAX(id) FROM scans WHERE type = 'hack' GROUP BY target_uuid)
                  AND has_detected = 1""")) {
            while (rs.next()) detected.add(rs.getString(1));
        }
        return detected;
    }

    /** Deletes scans (with their results, ON DELETE CASCADE) older than {@code before}. */
    void purgeBefore(Connection c, long before) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM scans WHERE timestamp < ?")) {
            ps.setLong(1, before);
            ps.executeUpdate();
        }
    }
}
