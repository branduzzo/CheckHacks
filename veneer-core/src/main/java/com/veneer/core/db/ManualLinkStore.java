package com.veneer.core.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** Manual account links: two accounts staff marked as one person ({@code /accountlink}, the web panel). */
public final class ManualLinkStore {

    private final Database db;

    ManualLinkStore(Database db) {
        this.db = db;
    }

    /** Marks two accounts as one person. The pair is stored in uuid order, so the same link twice changes nothing. */
    public void link(String a, String b) {
        String first = a.compareTo(b) < 0 ? a : b, second = a.compareTo(b) < 0 ? b : a;
        db.write("link accounts", null, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO manual_links (uuid_a, uuid_b, created_at) VALUES (?, ?, ?)")) {
                ps.setString(1, first);
                ps.setString(2, second);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Removes a manual link between two accounts. */
    public void unlink(String a, String b) {
        String first = a.compareTo(b) < 0 ? a : b, second = a.compareTo(b) < 0 ? b : a;
        db.write("unlink accounts", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM manual_links WHERE uuid_a = ? AND uuid_b = ?")) {
                ps.setString(1, first);
                ps.setString(2, second);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Every manual link with both accounts' current names (null when the account left the database). */
    public List<String[]> links() {
        return db.read("manual links", new ArrayList<>(), c -> {
            List<String[]> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT m.uuid_a, m.uuid_b, a.name, b.name FROM manual_links m
                    LEFT JOIN accounts a ON a.uuid = m.uuid_a
                    LEFT JOIN accounts b ON b.uuid = m.uuid_b""");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
            }
            return out;
        });
    }
}
