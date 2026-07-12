package me.branduzzo.checkHacks.managers;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.model.EditorTokenInfo;
import me.branduzzo.checkHacks.model.HackResultRow;
import me.branduzzo.checkHacks.model.LangResultRow;
import me.branduzzo.checkHacks.model.ScanRecord;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import me.branduzzo.checkHacks.utils.FoliaScheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class DatabaseManager {

    private final CheckHacksPlugin plugin;
    private Connection connection;
    private final AtomicInteger inflight = new AtomicInteger();

    public DatabaseManager(CheckHacksPlugin plugin) {
        this.plugin = plugin;
        connect();
        createTables();
    }

    public void runAsync(Runnable task) {
        inflight.incrementAndGet();
        FoliaScheduler.runAsync(plugin, () -> {
            try {
                task.run();
            } finally {
                inflight.decrementAndGet();
            }
        });
    }

    public void awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (inflight.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private synchronized void connect() {
        try {
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
                CREATE TABLE IF NOT EXISTS editor_tokens (
                    token TEXT PRIMARY KEY,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL
                )""");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create tables: " + e.getMessage());
        }
    }

    public synchronized long saveHackScan(String targetName, String targetUUID,
                                          String checkerName, String reason,
                                          boolean hasDetected, List<HackResultRow> results) {
        if (connection == null) return -1;
        try {
            connection.setAutoCommit(false);
            long scanId = insertScan("hack", targetName, targetUUID, checkerName, reason, hasDetected);
            if (scanId < 0) {
                connection.rollback();
                return -1;
            }
            if (results != null) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO hack_results (scan_id,hack_id,hack_name,result) VALUES (?,?,?,?)")) {
                    for (HackResultRow row : results) {
                        ps.setLong(1, scanId);
                        ps.setString(2, row.hackId());
                        ps.setString(3, row.hackName());
                        ps.setString(4, row.result());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            connection.commit();
            return scanId;
        } catch (SQLException e) {
            rollbackQuietly();
            plugin.getLogger().warning("Failed to save hack scan: " + e.getMessage());
            return -1;
        } finally {
            restoreAutoCommit();
        }
    }

    public synchronized long saveLangScan(String targetName, String targetUUID,
                                          String checkerName, String reason,
                                          boolean hasDetected, LangResultRow result) {
        if (connection == null) return -1;
        try {
            connection.setAutoCommit(false);
            long scanId = insertScan("lang", targetName, targetUUID, checkerName, reason, hasDetected);
            if (scanId < 0) {
                connection.rollback();
                return -1;
            }
            if (result != null) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO lang_results (scan_id,language,response) VALUES (?,?,?)")) {
                    ps.setLong(1, scanId);
                    ps.setString(2, result.language());
                    ps.setString(3, result.response());
                    ps.executeUpdate();
                }
            }
            connection.commit();
            return scanId;
        } catch (SQLException e) {
            rollbackQuietly();
            plugin.getLogger().warning("Failed to save lang scan: " + e.getMessage());
            return -1;
        } finally {
            restoreAutoCommit();
        }
    }

    private long insertScan(String type, String targetName, String targetUUID,
                            String checkerName, String reason, boolean hasDetected)
            throws SQLException {
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

    public synchronized List<ScanRecord> getRecentScans(String type, int limit) {
        List<ScanRecord> list = new ArrayList<>();
        if (connection == null) return list;

        String sql = type != null
                ? "SELECT * FROM scans WHERE type=? ORDER BY timestamp DESC LIMIT ?"
                : "SELECT * FROM scans ORDER BY timestamp DESC LIMIT ?";
        List<ScanRecord> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (type != null) {
                ps.setString(1, type);
                ps.setInt(2, limit);
            } else {
                ps.setInt(1, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(readScan(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getRecentScans: " + e.getMessage());
            return list;
        }
        return attachResults(rows);
    }

    public synchronized ScanRecord getScan(long id) {
        if (connection == null) return null;
        ScanRecord row = null;
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM scans WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) row = readScan(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getScan: " + e.getMessage());
            return null;
        }
        if (row == null) return null;
        List<ScanRecord> attached = attachResults(List.of(row));
        return attached.isEmpty() ? row : attached.getFirst();
    }

    public synchronized List<ScanRecord> getPlayerScans(String playerName) {
        List<ScanRecord> list = new ArrayList<>();
        if (connection == null) return list;

        List<ScanRecord> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM scans WHERE LOWER(target_name)=LOWER(?) ORDER BY timestamp DESC LIMIT 100")) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(readScan(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("getPlayerScans: " + e.getMessage());
            return list;
        }
        return attachResults(rows);
    }

    private List<ScanRecord> attachResults(List<ScanRecord> rows) {
        if (rows.isEmpty()) return rows;
        List<Long> hackIds = new ArrayList<>();
        List<Long> langIds = new ArrayList<>();
        for (ScanRecord row : rows) {
            if ("hack".equals(row.type())) hackIds.add(row.id());
            else langIds.add(row.id());
        }
        Map<Long, List<HackResultRow>> hackByScan = loadHackResults(hackIds);
        Map<Long, List<LangResultRow>> langByScan = loadLangResults(langIds);

        List<ScanRecord> out = new ArrayList<>(rows.size());
        for (ScanRecord row : rows) {
            if ("hack".equals(row.type())) {
                out.add(row.withResults(hackByScan.getOrDefault(row.id(), List.of())));
            } else {
                out.add(row.withResults(langByScan.getOrDefault(row.id(), List.of())));
            }
        }
        return out;
    }

    private Map<Long, List<HackResultRow>> loadHackResults(List<Long> scanIds) {
        Map<Long, List<HackResultRow>> map = new HashMap<>();
        if (scanIds.isEmpty()) return map;
        String placeholders = String.join(",", scanIds.stream().map(id -> "?").toList());
        String sql = "SELECT scan_id, hack_id, hack_name, result FROM hack_results WHERE scan_id IN ("
                + placeholders + ") ORDER BY id ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < scanIds.size(); i++) ps.setLong(i + 1, scanIds.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long scanId = rs.getLong("scan_id");
                    map.computeIfAbsent(scanId, k -> new ArrayList<>()).add(
                            new HackResultRow(
                                    rs.getString("hack_id"),
                                    rs.getString("hack_name"),
                                    rs.getString("result")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("loadHackResults: " + e.getMessage());
        }
        return map;
    }

    private Map<Long, List<LangResultRow>> loadLangResults(List<Long> scanIds) {
        Map<Long, List<LangResultRow>> map = new HashMap<>();
        if (scanIds.isEmpty()) return map;
        String placeholders = String.join(",", scanIds.stream().map(id -> "?").toList());
        String sql = "SELECT scan_id, language, response FROM lang_results WHERE scan_id IN ("
                + placeholders + ") ORDER BY id ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < scanIds.size(); i++) ps.setLong(i + 1, scanIds.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long scanId = rs.getLong("scan_id");
                    map.computeIfAbsent(scanId, k -> new ArrayList<>()).add(
                            new LangResultRow(rs.getString("language"), rs.getString("response")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("loadLangResults: " + e.getMessage());
        }
        return map;
    }

    private ScanRecord readScan(ResultSet rs) throws SQLException {
        return new ScanRecord(
                rs.getLong("id"),
                rs.getString("type"),
                rs.getString("target_name"),
                rs.getString("target_uuid"),
                rs.getString("checker_name"),
                rs.getString("reason"),
                rs.getLong("timestamp"),
                rs.getInt("has_detected") != 0,
                null);
    }

    public synchronized boolean deleteScan(long id) {
        if (connection == null) return false;
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM scans WHERE id=?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("deleteScan: " + e.getMessage());
            return false;
        }
    }

    public synchronized String saveToken(String playerUUID, String playerName, int expireMinutes) {
        if (connection == null) return null;
        String token = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
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
                    "INSERT INTO editor_tokens (token,player_uuid,player_name,created_at,expires_at) VALUES (?,?,?,?,?)")) {
                ps.setString(1, token);
                ps.setString(2, playerUUID);
                ps.setString(3, playerName);
                ps.setLong(4, now);
                ps.setLong(5, expires);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("saveToken: " + e.getMessage());
            return null;
        }
        return token;
    }

    public synchronized EditorTokenInfo validateToken(String token) {
        if (connection == null || token == null) return null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT player_uuid, player_name FROM editor_tokens WHERE token=? AND expires_at>?")) {
            ps.setString(1, token);
            ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new EditorTokenInfo(rs.getString("player_uuid"), rs.getString("player_name"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("validateToken: " + e.getMessage());
        }
        return null;
    }

    private void rollbackQuietly() {
        try {
            if (connection != null) connection.rollback();
        } catch (SQLException ignored) {}
    }

    private void restoreAutoCommit() {
        try {
            if (connection != null) connection.setAutoCommit(true);
        } catch (SQLException ignored) {}
    }

    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("close: " + e.getMessage());
        }
    }
}
