package com.veneer.core.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Web panel log-in tokens. */
public final class TokenStore {

    private final Database db;

    TokenStore(Database db) {
        this.db = db;
    }

    /** A new token for the player (replacing their previous one), valid for {@code expireMinutes}; null on failure. */
    public String saveToken(String playerUUID, String playerName, int expireMinutes, String perms) {
        String token = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        long expires = now + (expireMinutes * 60_000L);
        return db.transaction("saveToken", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM editor_tokens WHERE player_uuid=? OR expires_at<?")) {
                ps.setString(1, playerUUID);
                ps.setLong(2, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO editor_tokens (token,player_uuid,player_name,created_at,expires_at,perms) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, token);
                ps.setString(2, playerUUID);
                ps.setString(3, playerName);
                ps.setLong(4, now);
                ps.setLong(5, expires);
                ps.setString(6, perms);
                ps.executeUpdate();
            }
            return token;
        });
    }

    /** The token's owner (player_uuid, player_name, perms, expires_at), or null when it is unknown or expired. */
    public Map<String, String> validateToken(String token) {
        if (token == null) return null;
        return db.read("validateToken", null, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT player_uuid, player_name, perms, expires_at FROM editor_tokens WHERE token=? AND expires_at>?")) {
                ps.setString(1, token);
                ps.setLong(2, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    Map<String, String> info = new LinkedHashMap<>();
                    info.put("player_uuid", rs.getString("player_uuid"));
                    info.put("player_name", rs.getString("player_name"));
                    info.put("perms", rs.getString("perms"));
                    info.put("expires_at", String.valueOf(rs.getLong("expires_at")));
                    return info;
                }
            }
        });
    }
}
