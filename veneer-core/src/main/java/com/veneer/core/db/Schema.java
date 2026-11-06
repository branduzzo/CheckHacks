package com.veneer.core.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/** Tables, indexes and upgrades of an existing file ({@code PRAGMA user_version}). */
final class Schema {

    /** Bump with every step added to {@link #upgrade}. */
    static final int VERSION = 4;

    private Schema() {}

    static void create(Connection c, Logger log) throws SQLException {
        try (Statement s = c.createStatement()) {
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
            // Web panel access: one-time links and browser sessions, by the SHA-256 of their secret
            s.execute("""
                CREATE TABLE IF NOT EXISTS web_sessions (
                    hash TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    perms TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    last_used INTEGER NOT NULL,
                    last_ip TEXT
                )""");
            s.execute("CREATE TABLE IF NOT EXISTS web_blocked_ips (ip TEXT PRIMARY KEY, created_at INTEGER NOT NULL)");
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
            // Every known account with its latest name and activity. The player list used to be worked out from
            // joins, scans, clients and rptrack together on each page (four full scans, grouped and sorted); this
            // table is kept up to date by the writes to those tables instead.
            s.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    uuid TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    last_seen INTEGER NOT NULL
                )""");
            // veneer.web.* rights of the player when the token was issued, comma separated
            try { s.execute("ALTER TABLE editor_tokens ADD COLUMN perms TEXT NOT NULL DEFAULT ''"); }
            catch (SQLException alreadyThere) {}

            // Results are read and cascade-deleted by scan id, history by time and player
            s.execute("CREATE INDEX IF NOT EXISTS idx_hack_results_scan ON hack_results(scan_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_lang_results_scan ON lang_results(scan_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_scans_time ON scans(timestamp)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_scans_target ON scans(target_uuid)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_joins_ip ON joins(ip)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_joins_last ON joins(last_seen)");
            // "Latest mod scan of each player" (stats, detected mods, link graph): answered from this index alone
            s.execute("CREATE INDEX IF NOT EXISTS idx_scans_type_target ON scans(type, target_uuid, id)");
            // Detections page: newest scans that found something, without walking the clean ones
            s.execute("CREATE INDEX IF NOT EXISTS idx_scans_detected_time ON scans(timestamp) WHERE has_detected = 1");
            // Look-ups by name in any case (player search, player record, scans of a name)
            s.execute("CREATE INDEX IF NOT EXISTS idx_scans_lname ON scans(LOWER(target_name))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_joins_lname ON joins(LOWER(name))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_clients_lname ON clients(LOWER(name))");
            // Retention purge
            s.execute("CREATE INDEX IF NOT EXISTS idx_skins_updated ON skins(updated)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_clients_updated ON clients(updated)");
            // Channel groups page: groups in index order instead of sorting the whole table
            s.execute("CREATE INDEX IF NOT EXISTS idx_clients_channels ON clients(channels, uuid, updated)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tokens_player ON editor_tokens(player_uuid)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_accounts_last ON accounts(last_seen)");
        }
        upgrade(c, log);
    }

    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) if (column.equals(rs.getString("name"))) return true;
        }
        return false;
    }

    private static void upgrade(Connection c, Logger log) throws SQLException {
        int version;
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA user_version")) {
            version = rs.next() ? rs.getInt(1) : 0;
        }
        if (version >= VERSION) return;
        int filled = 0;
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            if (version < 1) {
                // Fill accounts from what an older version stored (the latest name of each account wins)
                filled = s.executeUpdate("""
                    INSERT INTO accounts (uuid, name, last_seen)
                    SELECT uuid, name, MAX(ts) FROM (
                        SELECT uuid, name, last_seen AS ts FROM joins
                        UNION ALL SELECT target_uuid, target_name, timestamp FROM scans
                        UNION ALL SELECT uuid, name, updated FROM clients
                        UNION ALL SELECT uuid, name, last_seen FROM rptrack
                    ) WHERE uuid <> '' AND uuid NOT IN (SELECT uuid FROM accounts) GROUP BY uuid""");
            }
            if (version < 2) {
                // What each mod's probe asked and how the client answered, for the panel
                s.execute("ALTER TABLE hack_results ADD COLUMN probe_key TEXT");
                s.execute("ALTER TABLE hack_results ADD COLUMN probe_answer TEXT");
                s.execute("ALTER TABLE hack_results ADD COLUMN probe_channels TEXT");
            }
            if (version < 3 && !hasColumn(c, "joins", "count")) {
                // Databases from before join counting: without it no join could be recorded or read
                s.execute("ALTER TABLE joins ADD COLUMN count INTEGER NOT NULL DEFAULT 1");
            }
            if (version < 4) {
                // Tokens of the old URL log-in were stored in plain text and no longer open anything
                s.execute("DELETE FROM editor_tokens");
            }
            s.execute("PRAGMA user_version = " + VERSION);
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
        // Planner statistics for the new indexes
        try (Statement s = c.createStatement()) {
            s.execute("ANALYZE");
        }
        if (log != null && filled > 0) log.info("Database upgraded to version " + VERSION + " (" + filled + " accounts indexed).");
    }
}
