package com.veneer.core.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web panel access. {@code /hackeditor new} makes a one-time link; opening it swaps the link for a session bound to
 * that browser's cookie. Only SHA-256 hashes are stored, so reading the database gives no access to the panel.
 */
public final class TokenStore {

    private static final String LINK = "link", SESSION = "session";
    private static final long TOUCH_EVERY_MS = 60_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A session made from a link: the secret for the cookie and whose it is. */
    public record Redeemed(String session, String id, String playerUuid, String playerName) {}

    private final Database db;

    TokenStore(Database db) {
        this.db = db;
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void insert(Connection c, String hash, String kind, String uuid, String name, String perms,
                               long now, long expires, String ip) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO web_sessions (hash,kind,player_uuid,player_name,perms,created_at,expires_at,last_used,last_ip) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, hash);
            ps.setString(2, kind);
            ps.setString(3, uuid);
            ps.setString(4, name);
            ps.setString(5, perms);
            ps.setLong(6, now);
            ps.setLong(7, expires);
            ps.setLong(8, now);
            ps.setString(9, ip);
            ps.executeUpdate();
        }
    }

    /** A new one-time link secret, replacing the player's unused one; null on failure. */
    public String createLink(String playerUuid, String playerName, int expireMinutes, String perms) {
        String secret = newSecret();
        long now = System.currentTimeMillis();
        return db.transaction("createLink", null, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM web_sessions WHERE expires_at<? OR (kind='link' AND player_uuid=?)")) {
                ps.setLong(1, now);
                ps.setString(2, playerUuid);
                ps.executeUpdate();
            }
            insert(c, hash(secret), LINK, playerUuid, playerName, perms, now, now + expireMinutes * 60_000L, null);
            return secret;
        });
    }

    /** Uses up a link and starts a session for this browser; null when the link is unknown, used or expired. */
    public Redeemed redeemLink(String secret, String ip, int sessionDays) {
        if (secret == null) return null;
        long now = System.currentTimeMillis();
        return db.transaction("redeemLink", null, c -> {
            String uuid, name, perms;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT player_uuid, player_name, perms FROM web_sessions WHERE hash=? AND kind='link' AND expires_at>?")) {
                ps.setString(1, hash(secret));
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    uuid = rs.getString(1);
                    name = rs.getString(2);
                    perms = rs.getString(3);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM web_sessions WHERE hash=?")) {
                ps.setString(1, hash(secret));
                ps.executeUpdate();
            }
            String session = newSecret();
            insert(c, hash(session), SESSION, uuid, name, perms, now, now + sessionDays * 86_400_000L, ip);
            return new Redeemed(session, hash(session).substring(0, 8), uuid, name);
        });
    }

    /**
     * The session's owner (player_uuid, player_name, perms), or null when it is unknown or expired. Use extends the
     * session; that is written at most once a minute.
     */
    public Map<String, String> validateSession(String secret, String ip, int sessionDays) {
        if (secret == null) return null;
        String hash = hash(secret);
        long now = System.currentTimeMillis();
        long[] lastUsed = {0};
        Map<String, String> info = db.read("validateSession", null, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT player_uuid, player_name, perms, last_used FROM web_sessions WHERE hash=? AND kind='session' AND expires_at>?")) {
                ps.setString(1, hash);
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("player_uuid", rs.getString(1));
                    row.put("player_name", rs.getString(2));
                    row.put("perms", rs.getString(3));
                    lastUsed[0] = rs.getLong(4);
                    return row;
                }
            }
        });
        if (info != null && now - lastUsed[0] > TOUCH_EVERY_MS) {
            db.write("touchSession", null, c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE web_sessions SET last_used=?, expires_at=?, last_ip=? WHERE hash=?")) {
                    ps.setLong(1, now);
                    ps.setLong(2, now + sessionDays * 86_400_000L);
                    ps.setString(3, ip);
                    ps.setString(4, hash);
                    ps.executeUpdate();
                }
                return null;
            });
        }
        return info;
    }

    /** How many live sessions the player has. */
    public int countSessions(String playerUuid) {
        return listSessions(playerUuid, null).size();
    }

    /** Live sessions of an account given by uuid or, when that is null, by name: id, name, uuid, created, used, ip. */
    public List<Map<String, Object>> listSessions(String playerUuid, String playerName) {
        return db.read("listSessions", new ArrayList<>(), c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT hash, player_name, player_uuid, created_at, last_used, last_ip FROM web_sessions WHERE kind='session' AND expires_at>? AND "
                            + (playerUuid != null ? "player_uuid=?" : "LOWER(player_name)=?") + " ORDER BY last_used DESC")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, playerUuid != null ? playerUuid : Rows.lower(playerName));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getString(1).substring(0, 8));
                        row.put("name", rs.getString(2));
                        row.put("uuid", rs.getString(3));
                        row.put("created", rs.getLong(4));
                        row.put("used", rs.getLong(5));
                        row.put("ip", rs.getString(6));
                        out.add(row);
                    }
                }
            }
            return out;
        });
    }

    /** Disconnects the named player's browsers, or just one when {@code id} is given; how many went. */
    public int revokeByName(String playerName, String id) {
        return db.write("revokeSessions", 0, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM web_sessions WHERE LOWER(player_name)=?" + (id != null ? " AND substr(hash,1,8)=?" : ""))) {
                ps.setString(1, Rows.lower(playerName));
                if (id != null) ps.setString(2, id);
                return ps.executeUpdate();
            }
        });
    }

    /** The last address of one browser of the named player, or null when there is no such browser. */
    public String sessionIp(String playerName, String id) {
        return listSessions(null, playerName).stream().filter(r -> id.equals(r.get("id")))
                .map(r -> (String) r.get("ip")).findFirst().orElse(null);
    }

    /** Keeps this address out of the panel. */
    public void blockIp(String ip) {
        db.write("blockIp", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO web_blocked_ips (ip, created_at) VALUES (?,?)")) {
                ps.setString(1, ip);
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** False when the address was not blocked. */
    public boolean unblockIp(String ip) {
        return db.write("unblockIp", false, c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM web_blocked_ips WHERE ip=?")) {
                ps.setString(1, ip);
                return ps.executeUpdate() > 0;
            }
        });
    }

    public boolean isBlocked(String ip) {
        return db.read("isBlocked", false, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM web_blocked_ips WHERE ip=?")) {
                ps.setString(1, ip);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /** Removes all access of an account, unused links too. */
    public int revokeByUuid(String playerUuid) {
        return db.write("revokeSessions", 0, c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM web_sessions WHERE player_uuid=?")) {
                ps.setString(1, playerUuid);
                return ps.executeUpdate();
            }
        });
    }

    /** Rights of every browser of the player, after they changed. */
    public void updatePerms(String playerUuid, String perms) {
        db.write("updateWebPerms", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE web_sessions SET perms=? WHERE player_uuid=? AND perms<>?")) {
                ps.setString(1, perms);
                ps.setString(2, playerUuid);
                ps.setString(3, perms);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Accounts with panel access, for the periodic check of their rights. */
    public List<String> owners() {
        return db.read("webOwners", new ArrayList<>(), c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT player_uuid FROM web_sessions");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
            return out;
        });
    }
}
