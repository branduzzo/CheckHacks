package com.veneer.core;

import com.veneer.VeneerPlugin;

import java.io.File;
import java.sql.*;
import java.util.*;

public class DatabaseManager {

    private final VeneerPlugin plugin;
    private Connection connection;

    public DatabaseManager(VeneerPlugin plugin) {
        this.plugin = plugin;
        connect();
        createTables();
    }

    private synchronized void connect() {
        try {
            if (connection != null) { try { connection.close(); } catch (SQLException ignored) {} }
            Class.forName("org.sqlite.JDBC");
            File db = new File(plugin.getDataFolder(), "data.db");
            db.getParentFile().mkdirs();
            connection = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
                s.execute("PRAGMA journal_mode = WAL");
                s.execute("PRAGMA synchronous = NORMAL");
                s.execute("PRAGMA cache_size = 1000");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to connect to SQLite: " + e.getMessage());
        }
    }

    /** The connection, reconnecting once when it is missing or was closed; false when it stays unusable. */
    private synchronized boolean ensureConnection() {
        if (connection != null) {
            try { if (!connection.isClosed()) return true; } catch (SQLException ignored) {}
            plugin.getLogger().warning("SQLite connection was lost, reconnecting.");
        }
        connect();
        return connection != null;
    }

    private synchronized void createTables() {
        if (connection == null) return;
        try (Statement s = connection.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS scans (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type TEXT NOT NULL,
                    target_name TEXT NOT NULL,
                    target_uuid TEXT NOT NULL,
                    checker_name TEXT NOT NULL,
                    reason TEXT,
                    timestamp INTEGER NOT NULL,
                    has_detected INTEGER NOT NULL DEFAULT 0
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS hack_results (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scan_id INTEGER NOT NULL,
                    hack_id TEXT NOT NULL,
                    hack_name TEXT NOT NULL,
                    result TEXT NOT NULL,
                    FOREIGN KEY (scan_id) REFERENCES scans(id) ON DELETE CASCADE
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS lang_results (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scan_id INTEGER NOT NULL,
                    language TEXT,
                    response TEXT,
                    FOREIGN KEY (scan_id) REFERENCES scans(id) ON DELETE CASCADE
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS rptrack (
                    uuid TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    mask INTEGER NOT NULL,
                    liquidbounce INTEGER NOT NULL DEFAULT 0,
                    first_seen INTEGER NOT NULL,
                    last_seen INTEGER NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS joins (
                    uuid TEXT NOT NULL,
                    name TEXT NOT NULL,
                    ip TEXT NOT NULL,
                    first_seen INTEGER NOT NULL,
                    last_seen INTEGER NOT NULL,
                    count INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY (uuid, ip)
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS skins (
                    uuid TEXT PRIMARY KEY,
                    png TEXT NOT NULL,
                    updated INTEGER NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS clients (
                    uuid TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    brand TEXT NOT NULL,
                    channels TEXT NOT NULL,
                    updated INTEGER NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS editor_tokens (
                    token TEXT PRIMARY KEY,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL
                )""");
            // Client brands staff were asked about: verdict "allow", "block" or null (not decided yet)
            s.execute("""
                CREATE TABLE IF NOT EXISTS brand_verdicts (
                    id INTEGER PRIMARY KEY,
                    brand TEXT NOT NULL UNIQUE,
                    verdict TEXT,
                    decided_by TEXT,
                    decided_at INTEGER,
                    first_seen INTEGER NOT NULL,
                    last_player TEXT
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS player_language (
                    uuid TEXT PRIMARY KEY,
                    language TEXT NOT NULL
                )""");
            // Browsers bound to an account: sha256 of the cookie secret, and sha256 of nick, address and client
            s.execute("""
                CREATE TABLE IF NOT EXISTS web_devices (
                    secret_hash TEXT PRIMARY KEY,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    perms TEXT NOT NULL,
                    bind_hash TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL
                )""");
            // veneer.web.* rights of the player when the token was issued, comma separated
            try { s.execute("ALTER TABLE editor_tokens ADD COLUMN perms TEXT NOT NULL DEFAULT ''"); }
            catch (SQLException alreadyThere) {}
            // Results are read and cascade-deleted by scan id, history by time and player: without these
            // every panel request scans the whole tables while holding the connection lock
            s.execute("CREATE INDEX IF NOT EXISTS idx_hack_results_scan ON hack_results(scan_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_lang_results_scan ON lang_results(scan_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_scans_time ON scans(timestamp)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_scans_target ON scans(target_uuid)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_joins_ip ON joins(ip)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_joins_last ON joins(last_seen)");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create tables: " + e.getMessage());
        }
    }

    public synchronized long saveScan(String type, String targetName, String targetUUID,
                                      String checkerName, String reason, boolean hasDetected) {
        if (!ensureConnection()) return -1;
        try {
            return insertScanInternal(type, targetName, targetUUID, checkerName, reason, hasDetected);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save scan: " + e.getMessage());
        }
        return -1;
    }

    public synchronized long saveScanWithResults(String type, String targetName, String targetUUID,
                                                 String checkerName, String reason, boolean hasDetected,
                                                 List<Object[]> hackResults) {
        if (!ensureConnection()) return -1;
        boolean prevAuto = true;
        try {
            prevAuto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            long scanId = insertScanInternal(type, targetName, targetUUID, checkerName, reason, hasDetected);
            if (scanId == -1) {
                connection.rollback();
                return -1;
            }
            for (Object[] r : hackResults) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO hack_results (scan_id,hack_id,hack_name,result) VALUES (?,?,?,?)")) {
                    ps.setLong(1, scanId);
                    ps.setString(2, (String) r[0]);
                    ps.setString(3, (String) r[1]);
                    ps.setString(4, (String) r[2]);
                    ps.executeUpdate();
                }
            }
            connection.commit();
            return scanId;
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save scan batch: " + e.getMessage());
            try { connection.rollback(); } catch (SQLException ignored) {}
        } finally {
            try { connection.setAutoCommit(prevAuto); } catch (SQLException ignored) {}
        }
        return -1;
    }

    private long insertScanInternal(String type, String targetName, String targetUUID,
                                    String checkerName, String reason, boolean hasDetected) throws SQLException {
        String sql = "INSERT INTO scans (type,target_name,target_uuid,checker_name,reason,timestamp,has_detected) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, type);
            ps.setString(2, targetName);
            ps.setString(3, targetUUID);
            ps.setString(4, checkerName);
            ps.setString(5, reason);
            ps.setLong(6, System.currentTimeMillis());
            ps.setInt(7, hasDetected ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1;
    }

    public synchronized void saveHackResult(long scanId, String hackId, String hackName, String result) {
        if (!ensureConnection()) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO hack_results (scan_id,hack_id,hack_name,result) VALUES (?,?,?,?)")) {
            ps.setLong(1, scanId);
            ps.setString(2, hackId);
            ps.setString(3, hackName);
            ps.setString(4, result);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save hack result: " + e.getMessage());
        }
    }

    public synchronized void saveLangResult(long scanId, String language, String response) {
        if (!ensureConnection()) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO lang_results (scan_id,language,response) VALUES (?,?,?)")) {
            ps.setLong(1, scanId);
            ps.setString(2, language);
            ps.setString(3, response);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save lang result: " + e.getMessage());
        }
    }

    public synchronized List<Map<String, Object>> getRecentScans(String type, int limit) {
        return getRecentScans(type, limit, 0, null, null);
    }

    /** A page of the scan history, newest first: {@code type} "hack", "lang" or null for both, {@code detected} true keeps scans that found a mod, false the clean ones, null all, {@code player} (uuid or name) keeps one account's scans. */
    public synchronized List<Map<String, Object>> getRecentScans(String type, int limit, int offset, Boolean detected, String player) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (!ensureConnection()) return list;

        List<Long> ids = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        String sql = "SELECT * FROM scans WHERE (? IS NULL OR type=?)" + (detected == null ? "" : detected ? " AND has_detected=1" : " AND has_detected=0")
                + (player == null ? "" : " AND (target_uuid=? OR LOWER(target_name)=LOWER(?))")
                + " ORDER BY timestamp DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setString(2, type);
            int next = 3;
            if (player != null) { ps.setString(next++, player); ps.setString(next++, player); }
            ps.setInt(next++, limit);
            ps.setInt(next, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = rowToMap(rs);
                    rows.add(row);
                    ids.add(((Number) row.get("id")).longValue());
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getRecentScans: " + e.getMessage());
            return list;
        }

        attachResultsBatch(rows, ids);
        return rows;
    }

    public synchronized Map<String, Object> getScan(long id) {
        if (!ensureConnection()) return null;
        Map<String, Object> row = null;
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM scans WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) row = rowToMap(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getScan: " + e.getMessage());
            return null;
        }
        if (row == null) return null;
        String type = (String) row.get("type");
        row.put("results", "hack".equals(type) ? getHackResultsInternal(id) : getLangResultsInternal(id));
        return row;
    }

    private List<Map<String, Object>> getHackResultsInternal(long scanId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT hack_name, result FROM hack_results WHERE scan_id=? ORDER BY id ASC")) {
            ps.setLong(1, scanId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("hack_name", rs.getString("hack_name"));
                    r.put("result",    rs.getString("result"));
                    list.add(r);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getHackResults: " + e.getMessage());
        }
        return list;
    }

    private List<Map<String, Object>> getLangResultsInternal(long scanId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT language, response FROM lang_results WHERE scan_id=? ORDER BY id ASC")) {
            ps.setLong(1, scanId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("language", rs.getString("language"));
                    r.put("response", rs.getString("response"));
                    list.add(r);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getLangResults: " + e.getMessage());
        }
        return list;
    }

    /** Latest 100 scans of an account: by uuid when it is known (names change hands), else by name. */
    public synchronized List<Map<String, Object>> getPlayerScans(String uuid, String playerName) {
        boolean byUuid = uuid != null && !uuid.isEmpty();
        List<Map<String, Object>> list = new ArrayList<>();
        if (!ensureConnection()) return list;

        List<Long> ids = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(byUuid
                ? "SELECT * FROM scans WHERE target_uuid=? ORDER BY timestamp DESC LIMIT 100"
                : "SELECT * FROM scans WHERE LOWER(target_name)=LOWER(?) ORDER BY timestamp DESC LIMIT 100")) {
            ps.setString(1, byUuid ? uuid : playerName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = rowToMap(rs);
                    rows.add(row);
                    ids.add(((Number) row.get("id")).longValue());
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getPlayerScans: " + e.getMessage());
            return list;
        }

        attachResultsBatch(rows, ids);
        return rows;
    }

    private void attachResultsBatch(List<Map<String, Object>> rows, List<Long> ids) {
        if (rows.isEmpty()) return;
        List<Long> hackIds = new ArrayList<>();
        List<Long> langIds = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            long id = ids.get(i);
            if ("hack".equals(row.get("type"))) hackIds.add(id);
            else langIds.add(id);
        }

        Map<Long, List<Map<String, Object>>> hackMap = getBatchHackResults(hackIds);
        Map<Long, List<Map<String, Object>>> langMap = getBatchLangResults(langIds);

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            long id = ids.get(i);
            String type = (String) row.get("type");
            row.put("results", "hack".equals(type)
                    ? hackMap.getOrDefault(id, List.of())
                    : langMap.getOrDefault(id, List.of()));
        }
    }

    private Map<Long, List<Map<String, Object>>> getBatchHackResults(List<Long> scanIds) {
        Map<Long, List<Map<String, Object>>> map = new LinkedHashMap<>();
        if (scanIds.isEmpty()) return map;
        String placeholders = String.join(",", java.util.Collections.nCopies(scanIds.size(), "?"));
        String sql = "SELECT scan_id, hack_name, result FROM hack_results WHERE scan_id IN (" + placeholders + ") ORDER BY id ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < scanIds.size(); i++) {
                ps.setLong(i + 1, scanIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long scanId = rs.getLong("scan_id");
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("hack_name", rs.getString("hack_name"));
                    r.put("result",    rs.getString("result"));
                    map.computeIfAbsent(scanId, k -> new ArrayList<>()).add(r);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getBatchHackResults: " + e.getMessage());
        }
        return map;
    }

    private Map<Long, List<Map<String, Object>>> getBatchLangResults(List<Long> scanIds) {
        Map<Long, List<Map<String, Object>>> map = new LinkedHashMap<>();
        if (scanIds.isEmpty()) return map;
        String placeholders = String.join(",", java.util.Collections.nCopies(scanIds.size(), "?"));
        String sql = "SELECT scan_id, language, response FROM lang_results WHERE scan_id IN (" + placeholders + ") ORDER BY id ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < scanIds.size(); i++) {
                ps.setLong(i + 1, scanIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long scanId = rs.getLong("scan_id");
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("language", rs.getString("language"));
                    r.put("response", rs.getString("response"));
                    map.computeIfAbsent(scanId, k -> new ArrayList<>()).add(r);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getBatchLangResults: " + e.getMessage());
        }
        return map;
    }

    /** Counts for the panel's overview: scans by type, players whose latest mod scan found something, known accounts. */
    public synchronized Map<String, Object> getStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!ensureConnection()) return out;
        try (Statement st = connection.createStatement()) {
            out.put("hack_scans", count(st, "SELECT COUNT(*) FROM scans WHERE type = 'hack'"));
            out.put("lang_scans", count(st, "SELECT COUNT(*) FROM scans WHERE type = 'lang'"));
            out.put("detected_scans", count(st, "SELECT COUNT(*) FROM scans WHERE type = 'hack' AND has_detected = 1"));
            out.put("detected_players", count(st, """
                    SELECT COUNT(*) FROM scans
                    WHERE id IN (SELECT MAX(id) FROM scans WHERE type = 'hack' GROUP BY target_uuid) AND has_detected = 1"""));
            out.put("accounts", count(st, "SELECT COUNT(*) FROM (SELECT uuid FROM joins UNION SELECT target_uuid FROM scans)"));
            out.put("fingerprints", count(st, "SELECT COUNT(*) FROM rptrack"));
            out.put("scans_today", count(st, "SELECT COUNT(*) FROM scans WHERE timestamp > " + (System.currentTimeMillis() - 86_400_000L)));
        } catch (SQLException e) {
            plugin.getLogger().warning("getStats: " + e.getMessage());
        }
        return out;
    }

    private static long count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) { return rs.next() ? rs.getLong(1) : 0; }
    }

    /**
     * Accounts whose name starts with {@code prefix} (any case), from joins, scans and stored clients: name, uuid and
     * when they were last seen, most recent first.
     */
    public synchronized List<Map<String, Object>> searchPlayers(String prefix, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!ensureConnection()) return out;
        String like = prefix.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT name, uuid, MAX(ts) AS last_seen FROM (
                    SELECT name, uuid, last_seen AS ts FROM joins
                    UNION ALL SELECT target_name, target_uuid, timestamp FROM scans
                    UNION ALL SELECT name, uuid, updated FROM clients
                ) WHERE LOWER(name) LIKE ? ESCAPE '\\'
                GROUP BY LOWER(name) ORDER BY last_seen DESC LIMIT ?""")) {
            ps.setString(1, like);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rowToMap(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("searchPlayers: " + e.getMessage());
        }
        return out;
    }

    /**
     * What is stored about the account last seen with this name: {@code uuid}, {@code first_seen} and {@code last_seen}
     * (joins), and {@code client} (brand, channels list, updated) from the last quit. Empty when the name is unknown.
     */
    public synchronized Map<String, Object> getPlayerRecord(String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!ensureConnection()) return out;
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT uuid FROM (
                    SELECT uuid, last_seen AS ts, name FROM joins
                    UNION ALL SELECT target_uuid, timestamp, target_name FROM scans
                    UNION ALL SELECT uuid, updated, name FROM clients
                ) WHERE LOWER(name) = LOWER(?) ORDER BY ts DESC LIMIT 1""")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) out.put("uuid", rs.getString(1));
            }
            String uuid = (String) out.get("uuid");
            if (uuid == null) return out;
            try (PreparedStatement q = connection.prepareStatement(
                    "SELECT MIN(first_seen), MAX(last_seen), SUM(count) FROM joins WHERE uuid = ?")) {
                q.setString(1, uuid);
                try (ResultSet rs = q.executeQuery()) {
                    if (rs.next() && rs.getObject(1) != null) {
                        out.put("first_seen", rs.getLong(1));
                        out.put("last_seen", rs.getLong(2));
                        out.put("joins", rs.getLong(3));
                    }
                }
            }
            try (PreparedStatement q = connection.prepareStatement("SELECT brand, channels, updated FROM clients WHERE uuid = ?")) {
                q.setString(1, uuid);
                try (ResultSet rs = q.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Object> client = new LinkedHashMap<>();
                        client.put("brand", rs.getString(1));
                        String channels = rs.getString(2);
                        client.put("channels", channels.isEmpty() ? List.of() : List.of(channels.split(",")));
                        client.put("updated", rs.getLong(3));
                        out.put("client", client);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getPlayerRecord: " + e.getMessage());
        }
        return out;
    }

    public synchronized boolean deleteScan(long id) {
        if (!ensureConnection()) return false;
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM scans WHERE id=?")) {
            ps.setLong(1, id);
            int affected = ps.executeUpdate();
            return affected > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("deleteScan: " + e.getMessage());
            return false;
        }
    }

    /** Known accounts, most recently active first: uuid, name (the latest one) and last activity. */
    public synchronized List<Map<String, Object>> listPlayers(int limit, int offset) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (!ensureConnection()) return list;
        // SQLite returns the other columns of the row that holds MAX(ts), so name is the latest one
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT uuid, name, MAX(ts) FROM (
                    SELECT uuid, name, last_seen AS ts FROM joins
                    UNION ALL SELECT target_uuid, target_name, timestamp FROM scans
                    UNION ALL SELECT uuid, name, updated FROM clients
                    UNION ALL SELECT uuid, name, last_seen FROM rptrack
                ) WHERE uuid <> '' GROUP BY uuid ORDER BY MAX(ts) DESC LIMIT ? OFFSET ?""")) {
            ps.setInt(1, limit);
            ps.setInt(2, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("uuid", rs.getString(1));
                    row.put("name", rs.getString(2));
                    row.put("last_seen", rs.getLong(3));
                    list.add(row);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("listPlayers: " + e.getMessage());
        }
        return list;
    }

    /** Removes everything stored about the given accounts (addresses, skin, client, fingerprint, scans with their results, language). */
    public synchronized int deletePlayers(Collection<String> uuids) {
        if (!ensureConnection() || uuids.isEmpty()) return 0;
        String marks = String.join(",", Collections.nCopies(uuids.size(), "?"));
        String[] statements = {
                "DELETE FROM hack_results WHERE scan_id IN (SELECT id FROM scans WHERE target_uuid IN (" + marks + "))",
                "DELETE FROM lang_results WHERE scan_id IN (SELECT id FROM scans WHERE target_uuid IN (" + marks + "))",
                "DELETE FROM scans WHERE target_uuid IN (" + marks + ")",
                "DELETE FROM joins WHERE uuid IN (" + marks + ")",
                "DELETE FROM skins WHERE uuid IN (" + marks + ")",
                "DELETE FROM clients WHERE uuid IN (" + marks + ")",
                "DELETE FROM rptrack WHERE uuid IN (" + marks + ")",
                "DELETE FROM player_language WHERE uuid IN (" + marks + ")"};
        int removed = 0;
        try {
            connection.setAutoCommit(false);
            for (String sql : statements) {
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    int i = 1;
                    for (String uuid : uuids) ps.setString(i++, uuid);
                    int n = ps.executeUpdate();
                    if (sql.contains("FROM joins")) removed = n;
                }
            }
            connection.commit();
        } catch (SQLException e) {
            plugin.getLogger().warning("deletePlayers: " + e.getMessage());
            try { connection.rollback(); } catch (SQLException ignored) {}
            removed = 0;
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
        }
        return removed;
    }

    public synchronized String saveToken(String playerUUID, String playerName, int expireMinutes, String perms) {
        if (!ensureConnection()) return null;
        String token = UUID.randomUUID().toString().replace("-", "");
        long now     = System.currentTimeMillis();
        long expires = now + (expireMinutes * 60_000L);
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM editor_tokens WHERE player_uuid=?")) {
                ps.setString(1, playerUUID);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM editor_tokens WHERE expires_at<?")) {
                ps.setLong(1, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO editor_tokens (token,player_uuid,player_name,created_at,expires_at,perms) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, token);
                ps.setString(2, playerUUID);
                ps.setString(3, playerName);
                ps.setLong(4, now);
                ps.setLong(5, expires);
                ps.setString(6, perms);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("saveToken: " + e.getMessage());
        }
        return token;
    }

    /** sha256 of what ties a browser to an account: the nick, the address (when {@code ip} is given) and the client's User-Agent. */
    private static String bindHash(String name, String ip, String userAgent) {
        return sha256(name + "\n" + (ip == null ? "" : ip) + "\n" + (userAgent == null ? "" : userAgent));
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Registers a browser for the account that owns a link token. The link is spent; the browser gets a random secret
     * (returned, to be set as a cookie) and the database keeps only its sha256 together with the sha256 of nick,
     * address and client, so the account is bound to this browser and its previous one stops working. Null when the
     * link is unknown or expired, else the secret and its expiry.
     */
    public synchronized Map<String, String> registerDevice(String linkToken, String ip, String userAgent, boolean bindIp, int days) {
        Map<String, String> info = validateToken(linkToken);
        if (info == null) return null;
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        String secret = java.util.HexFormat.of().formatHex(raw);
        long now = System.currentTimeMillis();
        long expires = now + days * 86_400_000L;
        // The link spend, the old devices and the new row go in one transaction: a failure between the
        // DELETEs and the INSERT would otherwise leave the account with no binding and no token
        boolean prevAuto = true;
        try {
            prevAuto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement link = connection.prepareStatement("DELETE FROM editor_tokens WHERE token=?");
                 PreparedStatement old = connection.prepareStatement("DELETE FROM web_devices WHERE player_uuid=? OR expires_at<?");
                 PreparedStatement ins = connection.prepareStatement(
                         "INSERT INTO web_devices (secret_hash,player_uuid,player_name,perms,bind_hash,created_at,expires_at) VALUES (?,?,?,?,?,?,?)")) {
                link.setString(1, linkToken);
                link.executeUpdate();
                old.setString(1, info.get("player_uuid"));
                old.setLong(2, now);
                old.executeUpdate();
                ins.setString(1, sha256(secret));
                ins.setString(2, info.get("player_uuid"));
                ins.setString(3, info.get("player_name"));
                ins.setString(4, info.get("perms"));
                ins.setString(5, bindHash(info.get("player_name"), bindIp ? ip : null, userAgent));
                ins.setLong(6, now);
                ins.setLong(7, expires);
                ins.executeUpdate();
                connection.commit();
                return Map.of("secret", secret, "expires_at", String.valueOf(expires));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("registerDevice: " + e.getMessage());
            try { connection.rollback(); } catch (SQLException ignored) {}
        } finally {
            try { connection.setAutoCommit(prevAuto); } catch (SQLException ignored) {}
        }
        return null;
    }

    /** The account a browser secret belongs to, if the secret is known, unexpired and comes from the client it was bound to. */
    public synchronized Map<String, String> validateDevice(String secret, String ip, String userAgent, boolean bindIp) {
        if (!ensureConnection() || secret == null) return null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT player_uuid, player_name, perms, bind_hash, expires_at FROM web_devices WHERE secret_hash=? AND expires_at>?")) {
            ps.setString(1, sha256(secret));
            ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String expected = bindHash(rs.getString("player_name"), bindIp ? ip : null, userAgent);
                if (!java.security.MessageDigest.isEqual(expected.getBytes(), rs.getString("bind_hash").getBytes())) return null;
                Map<String, String> info = new LinkedHashMap<>();
                info.put("player_uuid", rs.getString("player_uuid"));
                info.put("player_name", rs.getString("player_name"));
                info.put("perms", rs.getString("perms"));
                info.put("expires_at", String.valueOf(rs.getLong("expires_at")));
                return info;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("validateDevice: " + e.getMessage());
            return null;
        }
    }

    /** The stored 64x64 skin of an account as a base64 PNG, or null. */
    public synchronized String getSkin(String uuid) {
        return getSkins(List.of(uuid)).get(uuid);
    }

    public synchronized Map<String, String> validateToken(String token) {
        if (!ensureConnection() || token == null) return null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT player_uuid, player_name, perms, expires_at FROM editor_tokens WHERE token=? AND expires_at>?")) {
            ps.setString(1, token);
            ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, String> info = new LinkedHashMap<>();
                    info.put("player_uuid", rs.getString("player_uuid"));
                    info.put("player_name", rs.getString("player_name"));
                    info.put("perms", rs.getString("perms"));
                    info.put("expires_at", String.valueOf(rs.getLong("expires_at")));
                    return info;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("validateToken: " + e.getMessage());
        }
        return null;
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++)
            row.put(meta.getColumnName(i), rs.getObject(i));
        return row;
    }

    /**
     * Inserts the cache fingerprint of an account. An existing one gets the packs added ({@code replace} false) or
     * is replaced by {@code mask} ({@code replace} true).
     */
    public synchronized void saveRPTrack(String uuid, String name, long mask, boolean liquidBounce, boolean replace) {
        if (!ensureConnection()) return;
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO rptrack (uuid, name, mask, liquidbounce, first_seen, last_seen)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, mask = CASE WHEN ? THEN excluded.mask ELSE excluded.mask | rptrack.mask END,
                    liquidbounce = excluded.liquidbounce OR rptrack.liquidbounce,
                    last_seen = excluded.last_seen""")) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setLong(3, mask);
            ps.setInt(4, liquidBounce ? 1 : 0);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.setBoolean(7, replace);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("saveRPTrack: " + e.getMessage());
        }
    }

    /** All stored rptrack as rows with uuid, name, mask, liquidbounce, last_seen. */
    public synchronized List<Map<String, Object>> getRPTracks() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!ensureConnection()) return out;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, name, mask, liquidbounce, last_seen FROM rptrack")) {
            while (rs.next()) out.add(rowToMap(rs));
        } catch (SQLException e) {
            plugin.getLogger().warning("getRPTracks: " + e.getMessage());
        }
        return out;
    }

    /** Notes that this account joined from this address (one row per account and address). */
    public synchronized void recordJoin(String uuid, String name, String ip) {
        if (!ensureConnection()) return;
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO joins (uuid, name, ip, first_seen, last_seen, count) VALUES (?,?,?,?,?,1)
                ON CONFLICT(uuid, ip) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen,
                    count = joins.count + 1""")) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setString(3, ip);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("recordJoin: " + e.getMessage());
        }
    }

    /** All rows of {@code brand_verdicts}, oldest first. */
    public synchronized List<Map<String, Object>> getBrandVerdicts() {
        List<Map<String, Object>> list = new ArrayList<>();
        if (!ensureConnection()) return list;
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM brand_verdicts ORDER BY id")) {
            while (rs.next()) list.add(rowToMap(rs));
        } catch (SQLException e) {
            plugin.getLogger().warning("getBrandVerdicts: " + e.getMessage());
        }
        return list;
    }

    public synchronized void saveBrandVerdict(int id, String brand, String verdict, String decidedBy, long decidedAt,
                                              long firstSeen, String lastPlayer) {
        if (!ensureConnection()) return;
        try (PreparedStatement ps = connection.prepareStatement("""
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
        } catch (SQLException e) {
            plugin.getLogger().warning("saveBrandVerdict: " + e.getMessage());
        }
    }

    /** {@code /veneer lang} choices: uuid to language code or {@code auto}. */
    public synchronized Map<String, String> getLanguageChoices() {
        Map<String, String> map = new HashMap<>();
        if (!ensureConnection()) return map;
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT uuid, language FROM player_language")) {
            while (rs.next()) map.put(rs.getString(1), rs.getString(2));
        } catch (SQLException e) {
            plugin.getLogger().warning("getLanguageChoices: " + e.getMessage());
        }
        return map;
    }

    /** Stores a player's language choice; null deletes it. */
    public synchronized void saveLanguageChoice(String uuid, String language) {
        if (!ensureConnection()) return;
        try (PreparedStatement ps = connection.prepareStatement(language == null
                ? "DELETE FROM player_language WHERE uuid = ?"
                : "INSERT INTO player_language (uuid, language) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET language = excluded.language")) {
            ps.setString(1, uuid);
            if (language != null) ps.setString(2, language);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("saveLanguageChoice: " + e.getMessage());
        }
    }

    public synchronized boolean hasFreshSkin(String uuid, long maxAgeMs) {
        if (!ensureConnection()) return true;
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM skins WHERE uuid = ? AND updated > ?")) {
            ps.setString(1, uuid);
            ps.setLong(2, System.currentTimeMillis() - maxAgeMs);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            plugin.getLogger().warning("hasFreshSkin: " + e.getMessage());
            return true;
        }
    }

    public synchronized void saveSkin(String uuid, String pngBase64) {
        if (!ensureConnection()) return;
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO skins (uuid, png, updated) VALUES (?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET png = excluded.png, updated = excluded.updated""")) {
            ps.setString(1, uuid);
            ps.setString(2, pngBase64);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("saveSkin: " + e.getMessage());
        }
    }

    /** Distinct channel groups seen across all clients, ordered by player count. */
    public synchronized List<Map<String, Object>> getChannelGroups(int limit, int offset) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (!ensureConnection()) return list;
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT channels, COUNT(DISTINCT uuid) AS player_count, MAX(updated) AS last_seen
                FROM clients WHERE channels != ''
                GROUP BY channels ORDER BY player_count DESC, last_seen DESC
                LIMIT ? OFFSET ?""")) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("channels", rs.getString(1));
                    row.put("player_count", rs.getInt(2));
                    row.put("last_seen", rs.getLong(3));
                    list.add(row);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getChannelGroups: " + e.getMessage());
        }
        return list;
    }

    /** Stores the client brand and plugin channels an account last played with (for client links). */
    public synchronized void recordClient(String uuid, String name, String brand, Collection<String> channels) {
        if (!ensureConnection()) return;
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO clients (uuid, name, brand, channels, updated) VALUES (?,?,?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, brand = excluded.brand,
                    channels = excluded.channels, updated = excluded.updated""")) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setString(3, brand);
            ps.setString(4, String.join(",", new TreeSet<>(channels)));
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("recordClient: " + e.getMessage());
        }
    }

    /**
     * Deletes what is older than the given number of days (0 or less keeps it): addresses and skins of accounts
     * not seen for {@code linkDays} (a skin is refreshed on join, so an old one belongs to an absent player), and
     * scans with their results after {@code scanDays}.
     */
    public synchronized void purge(int linkDays, int scanDays) {
        if (!ensureConnection()) return;
        long now = System.currentTimeMillis();
        try {
            if (linkDays > 0) {
                purgeOlder("DELETE FROM joins WHERE last_seen < ?", now - linkDays * 86_400_000L);
                purgeOlder("DELETE FROM skins WHERE updated < ?", now - linkDays * 86_400_000L);
                purgeOlder("DELETE FROM clients WHERE updated < ?", now - linkDays * 86_400_000L);
            }
            // results go with their scan (ON DELETE CASCADE)
            if (scanDays > 0) purgeOlder("DELETE FROM scans WHERE timestamp < ?", now - scanDays * 86_400_000L);
        } catch (SQLException e) {
            plugin.getLogger().warning("purge: " + e.getMessage());
        }
    }

    private void purgeOlder(String sql, long before) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, before);
            ps.executeUpdate();
        }
    }

    /**
     * Graph of linked accounts: {@code nodes} (id, name, detected, links, skin) and {@code edges} (source, target,
     * type "ip", "rptrack" or "client", label, p = how likely the two accounts are one person, 0 to 1). An address
     * shared by more than {@code maxPerIp} accounts is skipped (shared networks); fingerprints link when they overlap
     * by at least {@code minMatch} packs. A client link is the same brand with the same set of at least
     * {@code minChannels} plugin channels, shared by no more than {@code maxPerClient} accounts: a common client
     * (plain vanilla, a popular modpack) says nothing. Nodes also carry {@code last_seen} and their {@code ips}.
     * Accounts without any link are left out.
     * <p>
     * Not synchronized as a whole: the rows are copied under the connection lock, and the pairwise comparison runs
     * outside it, so a large graph does not hold up scan saves on the main thread.
     */
    public Map<String, Object> getLinkGraph(int maxPerIp, int minMatch, int maxPerClient, int minChannels) {
        List<String[]> joinRows = new ArrayList<>(), clientRows = new ArrayList<>();
        Set<String> detected = new HashSet<>();
        loadLinkRows(joinRows, clientRows, detected);
        List<Map<String, Object>> tracks = getRPTracks();

        List<Map<String, Object>> edges = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, List<String>> byIp = new LinkedHashMap<>(), ipsOf = new HashMap<>();
        Map<String, Long> lastSeen = new HashMap<>();
        for (String[] r : joinRows) {
            names.putIfAbsent(r[0], r[1]);
            byIp.computeIfAbsent(r[2], k -> new ArrayList<>()).add(r[0]);
            ipsOf.computeIfAbsent(r[0], k -> new ArrayList<>()).add(r[2]);
            lastSeen.merge(r[0], Long.parseLong(r[3]), Math::max);
        }
        for (Map.Entry<String, List<String>> e : byIp.entrySet()) {
            List<String> ids = e.getValue();
            if (ids.size() < 2 || ids.size() > maxPerIp) continue;
            for (int i = 0; i < ids.size(); i++)
                for (int j = i + 1; j < ids.size(); j++)
                    edges.add(edge(ids.get(i), ids.get(j), "ip", e.getKey(), 0.55 / Math.max(1.0, (ids.size() - 1) / 2.0)));
        }
        for (int i = 0; i < tracks.size(); i++) {
            long a = ((Number) tracks.get(i).get("mask")).longValue();
            for (int j = i + 1; j < tracks.size(); j++) {
                long b = ((Number) tracks.get(j).get("mask")).longValue();
                int common = Long.bitCount(a & b);
                int needed = Math.max(1, minMatch);
                if (common < needed) continue;
                String ua = (String) tracks.get(i).get("uuid"), ub = (String) tracks.get(j).get("uuid");
                names.putIfAbsent(ua, (String) tracks.get(i).get("name"));
                names.putIfAbsent(ub, (String) tracks.get(j).get("name"));
                edges.add(edge(ua, ub, "rptrack", common + " packs", Math.min(0.97, 0.8 + 0.03 * (common - needed))));
            }
        }

        Map<String, List<String>> byClient = new LinkedHashMap<>();
        for (String[] r : clientRows) {
            if (r[3].isEmpty() || r[3].split(",").length < minChannels) continue;
            names.putIfAbsent(r[0], r[1]);
            byClient.computeIfAbsent(r[2] + "\n" + r[3], k -> new ArrayList<>()).add(r[0]);
        }
        for (Map.Entry<String, List<String>> e : byClient.entrySet()) {
            List<String> ids = e.getValue();
            if (ids.size() < 2 || ids.size() > maxPerClient) continue;
            String[] key = e.getKey().split("\n", 2);
            String label = key[0] + ", " + key[1].split(",").length + " channels";
            for (int i = 0; i < ids.size(); i++)
                for (int j = i + 1; j < ids.size(); j++)
                    edges.add(edge(ids.get(i), ids.get(j), "client", label, 0.3 / Math.max(1.0, (ids.size() - 1) / 2.0)));
        }

        Map<String, Integer> degree = new LinkedHashMap<>();
        for (Map<String, Object> e : edges) {
            degree.merge((String) e.get("source"), 1, Integer::sum);
            degree.merge((String) e.get("target"), 1, Integer::sum);
        }
        Map<String, String> skins = getSkins(degree.keySet());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map.Entry<String, Integer> d : degree.entrySet()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", d.getKey());
            n.put("name", names.getOrDefault(d.getKey(), d.getKey()));
            n.put("detected", detected.contains(d.getKey()));
            n.put("links", d.getValue());
            n.put("skin", skins.get(d.getKey()));
            n.put("last_seen", lastSeen.get(d.getKey()));
            n.put("ips", ipsOf.getOrDefault(d.getKey(), List.of()));
            nodes.add(n);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("edges", edges);
        return out;
    }

    /** Join rows (uuid, name, ip) and the players whose latest mod scan found something. */
    private synchronized void loadLinkRows(List<String[]> joinRows, List<String[]> clientRows, Set<String> detected) {
        if (!ensureConnection()) return;
        try (Statement st = connection.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT uuid, name, ip, last_seen FROM joins ORDER BY last_seen DESC")) {
                while (rs.next()) joinRows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
            }
            try (ResultSet rs = st.executeQuery("SELECT uuid, name, brand, channels FROM clients")) {
                while (rs.next()) clientRows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
            }
            try (ResultSet rs = st.executeQuery("""
                    SELECT target_uuid FROM scans
                    WHERE id IN (SELECT MAX(id) FROM scans WHERE type = 'hack' GROUP BY target_uuid)
                      AND has_detected = 1""")) {
                while (rs.next()) detected.add(rs.getString(1));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getLinkGraph: " + e.getMessage());
        }
    }

    /** Stored skins of the given accounts only; loading every skin ever saved would grow with the whole player base. */
    private synchronized Map<String, String> getSkins(Collection<String> uuids) {
        Map<String, String> out = new HashMap<>();
        if (!ensureConnection() || uuids.isEmpty()) return out;
        List<String> ids = new ArrayList<>(uuids);
        for (int from = 0; from < ids.size(); from += 500) {
            List<String> chunk = ids.subList(from, Math.min(from + 500, ids.size()));
            String marks = String.join(",", Collections.nCopies(chunk.size(), "?"));
            try (PreparedStatement ps = connection.prepareStatement("SELECT uuid, png FROM skins WHERE uuid IN (" + marks + ")")) {
                for (int i = 0; i < chunk.size(); i++) ps.setString(i + 1, chunk.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.put(rs.getString(1), rs.getString(2));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("getSkins: " + e.getMessage());
            }
        }
        return out;
    }

    /** Mods found by each player's latest mod scan (a later clean scan clears the player): one entry per mod, most players first. */
    public synchronized List<Map<String, Object>> getDetectedMods() {
        Map<String, Map<String, Object>> byMod = new LinkedHashMap<>();
        if (!ensureConnection()) return new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("""
                SELECT r.hack_id, r.hack_name, s.target_name, s.target_uuid, MAX(s.timestamp) AS ts
                FROM hack_results r JOIN scans s ON s.id = r.scan_id
                WHERE r.result = 'DETECTED'
                  AND s.id IN (SELECT MAX(id) FROM scans WHERE type = 'hack' GROUP BY target_uuid)
                GROUP BY r.hack_id, s.target_uuid
                ORDER BY ts DESC""")) {
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
        } catch (SQLException e) {
            plugin.getLogger().warning("getDetectedMods: " + e.getMessage());
        }
        List<Map<String, Object>> out = new ArrayList<>(byMod.values());
        for (Map<String, Object> m : out) m.put("count", ((List<?>) m.get("players")).size());
        out.sort((a, b) -> Integer.compare((Integer) b.get("count"), (Integer) a.get("count")));
        return out;
    }

    /** One account for the graph's detail card: addresses it joined from and its RPTrack fingerprint, if any. */
    public synchronized Map<String, Object> getLinkDetails(String uuid) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> ips = new ArrayList<>();
        out.put("ips", ips);
        out.put("fingerprint", null);
        List<Map<String, Object>> mods = new ArrayList<>();
        out.put("mods", mods);
        if (!ensureConnection()) return out;
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT r.hack_name, MAX(s.timestamp) AS ts
                FROM hack_results r JOIN scans s ON s.id = r.scan_id
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
        } catch (SQLException e) {
            plugin.getLogger().warning("getLinkDetails mods: " + e.getMessage());
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ip, first_seen, last_seen, count FROM joins WHERE uuid = ? ORDER BY last_seen DESC")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ips.add(rowToMap(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getLinkDetails: " + e.getMessage());
        }
        for (Map<String, Object> row : getRPTracks()) {
            if (!uuid.equals(row.get("uuid"))) continue;
            long mask = ((Number) row.get("mask")).longValue();
            Map<String, Object> fp = new LinkedHashMap<>();
            fp.put("bits", Long.toBinaryString(mask));
            fp.put("packs", Long.bitCount(mask));
            fp.put("liquidbounce", ((Number) row.get("liquidbounce")).intValue() != 0);
            fp.put("last_seen", row.get("last_seen"));
            out.put("fingerprint", fp);
        }
        return out;
    }

    private static Map<String, Object> edge(String a, String b, String type, String label, double p) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("source", a);
        e.put("target", b);
        e.put("type", type);
        e.put("label", label);
        e.put("p", p);
        return e;
    }

    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("close: " + e.getMessage());
        }
    }
}