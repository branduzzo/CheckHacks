package com.veneer.core.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Accounts: the addresses they joined from, their last client, skin, and look-ups by name. */
public final class PlayerStore {

    private final Database db;

    PlayerStore(Database db) {
        this.db = db;
    }

    // ------------------------------------------------------------------ writes

    /** A registered account as AuthMe knows it; the addresses and the e-mail hash may be null. */
    public record AuthMeAccount(String uuid, String name, String regIp, String lastIp, long lastLogin, String emailHash) {}

    /** The current copy of AuthMe's accounts, replacing the previous one. */
    public void replaceAuthMeAccounts(Collection<AuthMeAccount> accounts) {
        db.transaction("replaceAuthMeAccounts", null, c -> {
            try (java.sql.Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM authme_accounts");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO authme_accounts (uuid, name, reg_ip, last_ip, last_login, email_hash) VALUES (?,?,?,?,?,?)")) {
                for (AuthMeAccount a : accounts) {
                    ps.setString(1, a.uuid());
                    ps.setString(2, a.name());
                    ps.setString(3, a.regIp());
                    ps.setString(4, a.lastIp());
                    ps.setLong(5, a.lastLogin());
                    ps.setString(6, a.emailHash());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /** Lowercase name to uuid of every known account. */
    public Map<String, String> accountUuids() {
        return db.read("accountUuids", new java.util.HashMap<>(), c -> {
            Map<String, String> out = new java.util.HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name FROM accounts"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(Rows.lower(rs.getString(2)), rs.getString(1));
            }
            return out;
        });
    }

    /** Notes that this account joined from this address (one row per account and address). */
    public void recordJoin(String uuid, String name, String ip) {
        long now = System.currentTimeMillis();
        db.transaction("recordJoin", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO joins (uuid, name, ip, first_seen, last_seen, count) VALUES (?,?,?,?,?,1)
                    ON CONFLICT(uuid, ip) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen,
                        count = joins.count + 1""")) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setString(3, ip);
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            Rows.touchAccount(c, uuid, name, now);
            return null;
        });
    }

    /**
     * Characters of a stored channel list. A large modpack registers a few hundred channels of some 30 characters;
     * a client can register a thousand of 256, and the list is kept per account and once more in an index.
     */
    static final int MAX_STORED_CHANNELS = 16_384;

    /** The channels sorted and comma separated, as many as fit into {@link #MAX_STORED_CHANNELS}. */
    static String channelList(Collection<String> channels) {
        StringBuilder out = new StringBuilder();
        for (String channel : new TreeSet<>(channels)) {
            if (channel.isEmpty() || channel.indexOf(',') >= 0) continue;
            if (out.length() + channel.length() + 1 > MAX_STORED_CHANNELS) break;
            if (out.length() > 0) out.append(',');
            out.append(channel);
        }
        return out.toString();
    }

    /** Stores the client brand and plugin channels an account last played with (for client links). */
    public void recordClient(String uuid, String name, String brand, Collection<String> channels) {
        long now = System.currentTimeMillis();
        db.transaction("recordClient", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO clients (uuid, name, brand, channels, updated) VALUES (?,?,?,?,?)
                    ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, brand = excluded.brand,
                        channels = excluded.channels, updated = excluded.updated""")) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setString(3, brand);
                ps.setString(4, channelList(channels));
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            Rows.touchAccount(c, uuid, name, now);
            return null;
        });
    }

    public boolean hasFreshSkin(String uuid, long maxAgeMs) {
        return db.read("hasFreshSkin", true, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM skins WHERE uuid = ? AND updated > ?")) {
                ps.setString(1, uuid);
                ps.setLong(2, System.currentTimeMillis() - maxAgeMs);
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        });
    }

    public void saveSkin(String uuid, String pngBase64) {
        db.write("saveSkin", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO skins (uuid, png, updated) VALUES (?,?,?)
                    ON CONFLICT(uuid) DO UPDATE SET png = excluded.png, updated = excluded.updated""")) {
                ps.setString(1, uuid);
                ps.setString(2, pngBase64);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Removes everything stored about the given accounts (addresses, skin, client, fingerprint, scans with their results, language). */
    public int deletePlayers(Collection<String> uuids) {
        if (uuids.isEmpty()) return 0;
        return db.transaction("deletePlayers", 0, c -> {
            Rows.updateIn(c, "DELETE FROM hack_results WHERE scan_id IN (SELECT id FROM scans WHERE target_uuid IN (%s))", uuids);
            Rows.updateIn(c, "DELETE FROM lang_results WHERE scan_id IN (SELECT id FROM scans WHERE target_uuid IN (%s))", uuids);
            Rows.updateIn(c, "DELETE FROM scans WHERE target_uuid IN (%s)", uuids);
            int removed = Rows.updateIn(c, "DELETE FROM joins WHERE uuid IN (%s)", uuids);
            Rows.updateIn(c, "DELETE FROM manual_links WHERE uuid_a IN (%s)", uuids);
            Rows.updateIn(c, "DELETE FROM manual_links WHERE uuid_b IN (%s)", uuids);
            for (String table : List.of("skins", "clients", "rptrack", "player_language", "authme_accounts", "accounts"))
                Rows.updateIn(c, "DELETE FROM " + table + " WHERE uuid IN (%s)", uuids);
            return removed;
        });
    }

    // ------------------------------------------------------------------- reads

    /**
     * Accounts whose name starts with {@code prefix} (any case), from joins, scans and stored clients: name, uuid and
     * when they were last seen, most recent first.
     */
    public List<Map<String, Object>> searchPlayers(String prefix, int limit) {
        return searchPlayers(prefix, limit, 0);
    }

    /** As above, skipping the first {@code offset} accounts: the next pages of a search. */
    public List<Map<String, Object>> searchPlayers(String prefix, int limit, int offset) {
        String lo = Rows.lower(prefix);
        char last = lo.isEmpty() ? 0 : lo.charAt(lo.length() - 1);
        // A prefix is a range of the LOWER(name) indexes: [prefix, prefix with its last letter + 1). LIKE on an
        // expression cannot use an index, so it is the fallback for the odd prefix the range cannot express.
        boolean range = !lo.isEmpty() && !Character.isSurrogate(last) && last != Character.MAX_VALUE;
        String cond = range ? " >= ? AND LOWER(%s) < ?" : " LIKE ? ESCAPE '\\'";
        String sql = """
                SELECT name, uuid, MAX(ts) AS last_seen FROM (
                    SELECT name, uuid, last_seen AS ts FROM joins WHERE LOWER(name)%1$s
                    UNION ALL SELECT target_name, target_uuid, timestamp FROM scans WHERE LOWER(target_name)%2$s
                    UNION ALL SELECT name, uuid, updated FROM clients WHERE LOWER(name)%1$s
                ) GROUP BY LOWER(name) ORDER BY last_seen DESC, LOWER(name) LIMIT ? OFFSET ?""".formatted(
                cond.formatted("name"), cond.formatted("target_name"));
        String like = lo.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String hi = range ? lo.substring(0, lo.length() - 1) + (char) (last + 1) : null;
        return db.read("searchPlayers", new ArrayList<>(), c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                for (int branch = 0; branch < 3; branch++) {
                    if (range) { ps.setString(i++, lo); ps.setString(i++, hi); }
                    else ps.setString(i++, like);
                }
                ps.setInt(i++, limit);
                ps.setInt(i, Math.max(0, offset));
                return Rows.all(ps);
            }
        });
    }

    /**
     * What is stored about the account last seen with this name: {@code uuid}, {@code first_seen} and {@code last_seen}
     * (joins), and {@code client} (brand, channels list, updated) from the last quit. Empty when the name is unknown.
     */
    public Map<String, Object> getPlayerRecord(String name) {
        String lo = Rows.lower(name);
        return db.read("getPlayerRecord", new LinkedHashMap<>(), c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            // Each branch is an index look-up on LOWER(name); before, the three tables were read whole
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT uuid FROM (
                        SELECT uuid, last_seen AS ts FROM joins WHERE LOWER(name) = ?
                        UNION ALL SELECT target_uuid, timestamp FROM scans WHERE LOWER(target_name) = ?
                        UNION ALL SELECT uuid, updated FROM clients WHERE LOWER(name) = ?
                    ) ORDER BY ts DESC LIMIT 1""")) {
                ps.setString(1, lo);
                ps.setString(2, lo);
                ps.setString(3, lo);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) out.put("uuid", rs.getString(1));
                }
            }
            String uuid = (String) out.get("uuid");
            if (uuid == null) return out;
            try (PreparedStatement q = c.prepareStatement(
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
            try (PreparedStatement q = c.prepareStatement("SELECT brand, channels, updated FROM clients WHERE uuid = ?")) {
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
            return out;
        });
    }

    /** Known accounts, most recently active first: uuid, name (the latest one) and last activity. */
    public List<Map<String, Object>> listPlayers(int limit, int offset) {
        return db.read("listPlayers", new ArrayList<>(), c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid, name, last_seen FROM accounts ORDER BY last_seen DESC LIMIT ? OFFSET ?")) {
                ps.setInt(1, limit);
                ps.setInt(2, Math.max(0, offset));
                return Rows.all(ps);
            }
        });
    }

    /** Distinct channel groups seen across all clients, ordered by player count. */
    public List<Map<String, Object>> getChannelGroups(int limit, int offset) {
        return db.read("getChannelGroups", new ArrayList<>(), c -> {
            List<Map<String, Object>> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT channels, COUNT(DISTINCT uuid) AS player_count, MAX(updated) AS last_seen
                    FROM clients WHERE channels != ''
                    GROUP BY channels ORDER BY player_count DESC, last_seen DESC
                    LIMIT ? OFFSET ?""")) {
                ps.setInt(1, limit);
                ps.setInt(2, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("channels", rs.getString(1));
                        row.put("player_count", rs.getInt(2));
                        row.put("last_seen", rs.getLong(3));
                        list.add(row);
                    }
                }
            }
            return list;
        });
    }

    /** Stored skins of the given accounts only; loading every skin ever saved would grow with the whole player base. */
    public Map<String, String> getSkins(Collection<String> uuids) {
        if (uuids.isEmpty()) return new HashMap<>();
        List<String> ids = new ArrayList<>(uuids);
        return db.read("getSkins", new HashMap<>(), c -> {
            Map<String, String> out = new HashMap<>();
            Rows.eachIn(c, "SELECT uuid, png FROM skins WHERE uuid IN (%s)", ids, rs -> out.put(rs.getString(1), rs.getString(2)));
            return out;
        });
    }

    /** Addresses of one account, latest first: ip, first_seen, last_seen, count. */
    public List<Map<String, Object>> getAddresses(String uuid) {
        return db.read("getAddresses", new ArrayList<>(), c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ip, first_seen, last_seen, count FROM joins WHERE uuid = ? ORDER BY last_seen DESC")) {
                ps.setString(1, uuid);
                return Rows.all(ps);
            }
        });
    }

    // ------------------------------------------------------------------- purge

    /** Addresses, skins, clients and fingerprints not updated since {@code before}; how many rows went. */
    int purgeBefore(Connection c, long before) throws SQLException {
        int removed = 0;
        for (String sql : List.of("DELETE FROM joins WHERE last_seen < ?", "DELETE FROM skins WHERE updated < ?",
                "DELETE FROM clients WHERE updated < ?", "DELETE FROM authme_accounts WHERE last_login < ?",
                "DELETE FROM rptrack WHERE last_seen < ?")) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, before);
                removed += ps.executeUpdate();
            }
        }
        return removed;
    }

    /** Drops accounts of which nothing is left after a purge, as the player list showed before it had its own table. */
    int pruneAccounts(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                DELETE FROM accounts WHERE NOT EXISTS (SELECT 1 FROM joins j WHERE j.uuid = accounts.uuid)
                  AND NOT EXISTS (SELECT 1 FROM scans s WHERE s.target_uuid = accounts.uuid)
                  AND NOT EXISTS (SELECT 1 FROM clients k WHERE k.uuid = accounts.uuid)
                  AND NOT EXISTS (SELECT 1 FROM rptrack r WHERE r.uuid = accounts.uuid)""")) {
            return ps.executeUpdate();
        }
    }
}
