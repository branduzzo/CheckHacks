package com.veneer.core.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JDBC helpers shared by the stores. */
final class Rows {

    /** Bound parameters per statement for {@code IN (...)} lists; SQLite's limit is far higher, this keeps statements small. */
    static final int CHUNK = 500;

    private Rows() {}

    static Map<String, Object> toMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++)
            row.put(meta.getColumnLabel(i), rs.getObject(i));
        return row;
    }

    static List<Map<String, Object>> all(PreparedStatement ps) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(toMap(rs));
        }
        return out;
    }

    /**
     * Lower case the way SQLite's {@code LOWER()} does it (ASCII letters only), so a parameter compared with
     * {@code LOWER(column)} matches the same rows as {@code LOWER(?)} would, and the expression index is used.
     */
    static String lower(String s) {
        if (s == null) return null;
        char[] out = s.toCharArray();
        for (int i = 0; i < out.length; i++) if (out[i] >= 'A' && out[i] <= 'Z') out[i] += 32;
        return new String(out);
    }

    static String marks(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    /** Runs {@code sqlWithIn} (one {@code %s} for the marks) for each chunk of {@code keys}, handing each row to {@code row}. */
    static void eachIn(Connection c, String sqlWithIn, List<?> keys, SqlConsumer<ResultSet> row) throws SQLException {
        for (int from = 0; from < keys.size(); from += CHUNK) {
            List<?> chunk = keys.subList(from, Math.min(from + CHUNK, keys.size()));
            try (PreparedStatement ps = c.prepareStatement(sqlWithIn.formatted(marks(chunk.size())))) {
                for (int i = 0; i < chunk.size(); i++) ps.setObject(i + 1, chunk.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) row.accept(rs);
                }
            }
        }
    }

    /** Runs {@code sqlWithIn} as an update for each chunk of {@code keys}; the number of rows changed. */
    static int updateIn(Connection c, String sqlWithIn, Collection<?> keys) throws SQLException {
        List<?> list = new ArrayList<>(keys);
        int n = 0;
        for (int from = 0; from < list.size(); from += CHUNK) {
            List<?> chunk = list.subList(from, Math.min(from + CHUNK, list.size()));
            try (PreparedStatement ps = c.prepareStatement(sqlWithIn.formatted(marks(chunk.size())))) {
                for (int i = 0; i < chunk.size(); i++) ps.setObject(i + 1, chunk.get(i));
                n += ps.executeUpdate();
            }
        }
        return n;
    }

    /**
     * Notes activity of an account for the player list: the name is replaced only by a newer one. Called in the
     * same write as the row that shows the activity.
     */
    static void touchAccount(Connection c, String uuid, String name, long ts) throws SQLException {
        if (uuid == null || uuid.isEmpty() || name == null) return;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO accounts (uuid, name, last_seen) VALUES (?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen
                WHERE excluded.last_seen >= accounts.last_seen""")) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setLong(3, ts);
            ps.executeUpdate();
        }
    }

    @FunctionalInterface
    interface SqlConsumer<T> {
        void accept(T t) throws SQLException;
    }
}
