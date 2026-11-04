package com.veneer.core.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** RPTrack: the resource pack cache fingerprint of each account, a bit mask of cached packs. */
public final class FingerprintStore {

    /** One stored fingerprint. */
    public record Fingerprint(String uuid, String name, long mask) {}

    private final Database db;

    FingerprintStore(Database db) {
        this.db = db;
    }

    /**
     * Inserts the cache fingerprint of an account. An existing one gets the packs added ({@code replace} false) or
     * is replaced by {@code mask} ({@code replace} true).
     */
    public void save(String uuid, String name, long mask, boolean liquidBounce, boolean replace) {
        long now = System.currentTimeMillis();
        db.transaction("saveRPTrack", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("""
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
            }
            Rows.touchAccount(c, uuid, name, now);
            return null;
        });
    }

    /**
     * Every stored fingerprint, only what comparing needs (uuid, name, mask). RPTrack compares a client against
     * all of them; reading three columns into records instead of whole rows into maps keeps that cheap.
     */
    public List<Fingerprint> masks() {
        return db.read("rptrack masks", new ArrayList<>(), c -> {
            List<Fingerprint> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, mask FROM rptrack");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Fingerprint(rs.getString(1), rs.getString(2), rs.getLong(3)));
            }
            return out;
        });
    }

    /** The stored mask of one account, 0 when it has none. */
    public long maskOf(String uuid) {
        return db.read("rptrack mask", 0L, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT mask FROM rptrack WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
            }
        });
    }

    /** One account's fingerprint for display (bits, packs, liquidbounce, last_seen), or null. */
    public Map<String, Object> details(String uuid) {
        return db.read("rptrack details", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT mask, liquidbounce, last_seen FROM rptrack WHERE uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    long mask = rs.getLong(1);
                    Map<String, Object> fp = new LinkedHashMap<>();
                    fp.put("bits", Long.toBinaryString(mask));
                    fp.put("packs", Long.bitCount(mask));
                    fp.put("liquidbounce", rs.getInt(2) != 0);
                    fp.put("last_seen", rs.getObject(3));
                    return fp;
                }
            }
        });
    }
}
