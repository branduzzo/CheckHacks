package com.veneer.core.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class StorageTest {

    private static final Logger LOG = Logger.getLogger("test");

    @TempDir
    Path dir;
    private Storage storage;

    @BeforeEach
    void open() {
        storage = new Storage(dir.resolve("data.db").toFile(), LOG);
        assertTrue(storage.available());
    }

    @AfterEach
    void close() {
        storage.close();
    }

    private static List<Object[]> results(String... idResult) {
        return java.util.Arrays.stream(idResult).map(s -> {
            String[] p = s.split("=");
            return new Object[]{p[0], p[0].toUpperCase(), p[1]};
        }).toList();
    }

    @Test
    void scansCarryTheirResultsInOrder() {
        long a = storage.scans().saveScanWithResults("hack", "Alice", "u-a", "staff", "manual", true,
                results("meteor=DETECTED", "wurst=NOT_DETECTED"));
        long b = storage.scans().saveLangScan("Bob", "u-b", "staff", "Lang check", "Russian", "ru_ru");
        assertTrue(a > 0 && b > a);

        List<Map<String, Object>> page = storage.scans().getRecentScans(null, 10, 0, null, null);
        assertEquals(2, page.size());
        Map<String, Object> lang = page.get(0).get("type").equals("lang") ? page.get(0) : page.get(1);
        Map<String, Object> hack = lang == page.get(0) ? page.get(1) : page.get(0);
        assertEquals(List.of(Map.of("hack_name", "METEOR", "result", "DETECTED"),
                Map.of("hack_name", "WURST", "result", "NOT_DETECTED")), hack.get("results"));
        assertEquals(List.of(Map.of("language", "Russian", "response", "ru_ru")), lang.get("results"));

        assertEquals(hack.get("results"), storage.scans().getScan(a).get("results"));
        assertNull(storage.scans().getScan(9999));
    }

    @Test
    void scanFiltersByPlayerInAnyCaseAndByDetection() {
        storage.scans().saveScanWithResults("hack", "Alice", "u-a", "staff", null, true, results("meteor=DETECTED"));
        storage.scans().saveScanWithResults("hack", "Alice", "u-a", "staff", null, false, results("meteor=NOT_DETECTED"));
        storage.scans().saveScanWithResults("hack", "Bob", "u-b", "staff", null, true, results("wurst=DETECTED"));

        assertEquals(2, storage.scans().getRecentScans(null, 10, 0, null, "aLiCe").size());
        assertEquals(2, storage.scans().getRecentScans(null, 10, 0, null, "u-a").size());
        assertEquals(1, storage.scans().getRecentScans(null, 10, 0, true, "ALICE").size());
        assertEquals(2, storage.scans().getRecentScans("hack", 10, 0, true, null).size());
        assertEquals(1, storage.scans().getRecentScans("hack", 1, 1, true, null).size());
        assertEquals(2, storage.scans().getPlayerScans(null, "ALICE").size());
        assertEquals(1, storage.scans().getPlayerScans("u-b", "whatever").size());
    }

    @Test
    void statsAndDetectedModsFollowTheLatestScan() {
        storage.scans().saveScanWithResults("hack", "Alice", "u-a", "s", null, true, results("meteor=DETECTED"));
        storage.scans().saveScanWithResults("hack", "Alice", "u-a", "s", null, false, results("meteor=NOT_DETECTED"));
        storage.scans().saveScanWithResults("hack", "Bob", "u-b", "s", null, true, results("meteor=DETECTED", "wurst=DETECTED"));
        storage.scans().saveLangScan("Carl", "u-c", "s", null, "en", "x");

        Map<String, Object> stats = storage.scans().getStats();
        assertEquals(3L, stats.get("hack_scans"));
        assertEquals(1L, stats.get("lang_scans"));
        assertEquals(2L, stats.get("detected_scans"));
        assertEquals(1L, stats.get("detected_players"));
        assertEquals(3L, stats.get("accounts"));
        assertEquals(4L, stats.get("scans_today"));

        List<Map<String, Object>> mods = storage.scans().getDetectedMods();
        assertEquals(2, mods.size());
        for (Map<String, Object> m : mods) assertEquals(1, m.get("count"), "only Bob's latest scan counts");

        assertEquals(1, storage.scans().getDetectedModsOf("u-a").size(), "history of one account keeps old finds");
    }

    @Test
    void playerSearchRecordAndList() throws Exception {
        storage.players().recordJoin("u-a", "Alice_1", "1.1.1.1");
        Thread.sleep(2);
        storage.players().recordJoin("u-b", "Alfred", "2.2.2.2");
        storage.players().recordJoin("u-b", "Alfred", "2.2.2.2");
        storage.players().recordClient("u-c", "alpha", "fabric", List.of("b:c", "a:b"));
        storage.players().recordJoin("u-d", "Zed", "3.3.3.3");

        List<Map<String, Object>> found = storage.players().searchPlayers("AL", 10);
        assertEquals(Set.of("Alice_1", "Alfred", "alpha"), Set.copyOf(found.stream().map(r -> r.get("name")).toList()));
        // "_" is a literal, not a LIKE wildcard
        assertEquals(List.of("Alice_1"), storage.players().searchPlayers("alice_", 10).stream().map(r -> r.get("name")).toList());
        assertEquals(List.of(), storage.players().searchPlayers("alicex", 10));
        assertEquals(1, storage.players().searchPlayers("al", 1).size());

        Map<String, Object> rec = storage.players().getPlayerRecord("ALFRED");
        assertEquals("u-b", rec.get("uuid"));
        assertEquals(2L, rec.get("joins"));
        assertEquals(Map.of("brand", "fabric", "channels", List.of("a:b", "b:c")),
                Map.of("brand", ((Map<?, ?>) storage.players().getPlayerRecord("Alpha").get("client")).get("brand"),
                        "channels", ((Map<?, ?>) storage.players().getPlayerRecord("Alpha").get("client")).get("channels")));
        assertTrue(storage.players().getPlayerRecord("nobody").isEmpty());

        List<Map<String, Object>> list = storage.players().listPlayers(10, 0);
        assertEquals(4, list.size());
        assertEquals("u-d", list.get(0).get("uuid"), "most recent first");

        // A rename shows the new name
        storage.players().recordJoin("u-a", "Alice_2", "1.1.1.1");
        assertEquals("Alice_2", storage.players().listPlayers(1, 0).get(0).get("name"));

        assertEquals(1, storage.players().deletePlayers(List.of("u-a")));
        assertEquals(3, storage.players().listPlayers(10, 0).size());
        assertTrue(storage.players().getPlayerRecord("Alice_2").isEmpty());
    }

    @Test
    void purgeDropsOldRowsAndTheirAccounts() throws Exception {
        storage.players().recordJoin("u-a", "Alice", "1.1.1.1");
        storage.scans().saveScan("hack", "Bob", "u-b", "s", null, false);
        // Age Alice's join by hand
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("data.db"));
             Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout = 5000");
            s.executeUpdate("UPDATE joins SET last_seen = 0");
        }
        storage.purge(30, 0);
        List<Map<String, Object>> list = storage.players().listPlayers(10, 0);
        assertEquals(List.of("u-b"), list.stream().map(r -> r.get("uuid")).toList());
    }

    @Test
    void fingerprintsMergeOrReplace() {
        storage.fingerprints().save("u-a", "Alice", 0b0011, false, false);
        storage.fingerprints().save("u-a", "Alice", 0b0100, true, false);
        assertEquals(0b0111, storage.fingerprints().maskOf("u-a"));
        storage.fingerprints().save("u-a", "Alice", 0b1000, false, true);
        assertEquals(0b1000, storage.fingerprints().maskOf("u-a"));
        assertEquals(0, storage.fingerprints().maskOf("nobody"));
        Map<String, Object> d = storage.fingerprints().details("u-a");
        assertEquals(1, d.get("packs"));
        assertEquals(true, d.get("liquidbounce"));
        assertNull(storage.fingerprints().details("nobody"));
        assertEquals(1, storage.fingerprints().masks().size());
        assertEquals("u-a", storage.players().listPlayers(5, 0).get(0).get("uuid"));
    }

    @Test
    void tokensAndSettings() {
        var tokens = storage.tokens();
        String first = tokens.createLink("u-a", "Alice", 5, "ips");
        String link = tokens.createLink("u-a", "Alice", 5, "ips");
        assertNull(tokens.redeemLink(first, "1.1.1.1", 30), "a new link replaces the unused one");
        assertNull(tokens.validateSession(link, "1.1.1.1", 30), "a link is not a session");
        var redeemed = tokens.redeemLink(link, "1.1.1.1", 30);
        assertEquals("Alice", redeemed.playerName());
        assertNull(tokens.redeemLink(link, "2.2.2.2", 30), "a link works once");
        assertEquals("ips", tokens.validateSession(redeemed.session(), "1.1.1.1", 30).get("perms"));
        assertNull(tokens.validateSession(null, "1.1.1.1", 30));
        assertNull(tokens.validateSession("wrong", "1.1.1.1", 30));

        var second = tokens.redeemLink(tokens.createLink("u-a", "Alice", 5, "ips"), "2.2.2.2", 30);
        assertEquals(2, tokens.countSessions("u-a"), "one session per browser");
        tokens.updatePerms("u-a", "scan");
        assertEquals("scan", tokens.validateSession(second.session(), "2.2.2.2", 30).get("perms"));
        assertEquals("2.2.2.2", tokens.sessionIp("Alice", second.id()));
        tokens.blockIp("2.2.2.2");
        assertTrue(tokens.isBlocked("2.2.2.2"));
        assertTrue(tokens.unblockIp("2.2.2.2"));
        assertFalse(tokens.isBlocked("2.2.2.2"));
        String one = (String) tokens.listSessions(null, "alice").get(0).get("id");
        assertEquals(1, tokens.revokeByName("Alice", one));
        assertEquals(1, tokens.revokeByName("alice", null));
        assertNull(tokens.validateSession(redeemed.session(), "1.1.1.1", 30));
        assertNull(tokens.validateSession(second.session(), "2.2.2.2", 30));

        storage.settings().saveLanguageChoice("u-a", "ru");
        assertEquals(Map.of("u-a", "ru"), storage.settings().getLanguageChoices());
        storage.settings().saveLanguageChoice("u-a", null);
        assertTrue(storage.settings().getLanguageChoices().isEmpty());

        storage.settings().saveBrandVerdict(1, "lunar", "block", "staff", 5, 1, "Alice");
        assertEquals("block", storage.settings().getBrandVerdicts().get(0).get("verdict"));
    }

    @Test
    void linkGraphAndDetails() {
        storage.players().recordJoin("u-a", "Alice", "9.9.9.9");
        storage.players().recordJoin("u-b", "Bob", "9.9.9.9");
        storage.fingerprints().save("u-a", "Alice", 0b1111, false, false);
        storage.fingerprints().save("u-c", "Carl", 0b0111, false, false);
        storage.scans().saveScanWithResults("hack", "Carl", "u-c", "s", null, true, results("meteor=DETECTED"));

        Map<String, Object> g = storage.links().getLinkGraph(8, 3, 5, 3);
        List<?> edges = (List<?>) g.get("edges");
        assertEquals(2, edges.size());
        List<?> nodes = (List<?>) g.get("nodes");
        assertEquals(3, nodes.size());
        assertTrue(nodes.stream().anyMatch(n -> ((Map<?, ?>) n).get("id").equals("u-c") && (Boolean) ((Map<?, ?>) n).get("detected")));

        Map<String, Object> details = storage.links().getLinkDetails("u-a");
        assertEquals(1, ((List<?>) details.get("ips")).size());
        assertEquals(4, ((Map<?, ?>) details.get("fingerprint")).get("packs"));
        assertEquals(1, ((List<?>) storage.links().getLinkDetails("u-c").get("mods")).size());
    }

    @Test
    void readsDoNotWaitForAWrite() throws Exception {
        storage.players().recordJoin("u-a", "Alice", "1.1.1.1");
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread writer = new Thread(() -> storage.players().deletePlayers(List.of("nobody")));
        // Hold the write lock with a slow transaction
        Database db = field(storage);
        Thread slow = new Thread(() -> db.transaction("slow", null, c -> {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO player_language VALUES ('x', 'en')");
            }
            writing.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            return null;
        }));
        slow.start();
        assertTrue(writing.await(5, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        assertEquals(1, storage.players().listPlayers(10, 0).size());
        assertFalse(storage.players().getPlayerRecord("Alice").isEmpty());
        long readMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(readMs < 1_000, "reads waited " + readMs + " ms for the writer");
        // The uncommitted row is not visible to readers
        assertTrue(storage.settings().getLanguageChoices().isEmpty());
        writer.start();
        release.countDown();
        slow.join();
        writer.join();
        assertEquals(Map.of("x", "en"), storage.settings().getLanguageChoices());
    }

    @Test
    void upgradesAFileOfThePreviousVersion() throws Exception {
        storage.close();
        File old = dir.resolve("old.db").toFile();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + old); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE joins (uuid TEXT NOT NULL, name TEXT NOT NULL, ip TEXT NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, count INTEGER NOT NULL DEFAULT 1, PRIMARY KEY (uuid, ip))");
            s.execute("CREATE TABLE scans (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, target_name TEXT NOT NULL, target_uuid TEXT NOT NULL, checker_name TEXT NOT NULL, reason TEXT, timestamp INTEGER NOT NULL, has_detected INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE editor_tokens (token TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)");
            s.execute("INSERT INTO joins VALUES ('u-a', 'OldName', '1.1.1.1', 1, 10, 1)");
            s.execute("INSERT INTO scans (type, target_name, target_uuid, checker_name, timestamp) VALUES ('hack', 'NewName', 'u-a', 's', 20)");
            s.execute("INSERT INTO scans (type, target_name, target_uuid, checker_name, timestamp) VALUES ('hack', 'Bob', 'u-b', 's', 5)");
        }
        storage = new Storage(old, LOG);
        List<Map<String, Object>> list = storage.players().listPlayers(10, 0);
        assertEquals(List.of("u-a", "u-b"), list.stream().map(r -> r.get("uuid")).toList());
        assertEquals("NewName", list.get(0).get("name"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + old); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA user_version")) {
            assertEquals(Schema.VERSION, rs.getInt(1));
        }
        // Opening again does not fill twice
        storage.close();
        storage = new Storage(old, LOG);
        assertEquals(2, storage.players().listPlayers(10, 0).size());
    }

    private static Database field(Storage s) throws Exception {
        var f = Storage.class.getDeclaredField("db");
        f.setAccessible(true);
        return (Database) f.get(s);
    }

    @Test
    void anOldJoinsTableGetsItsCountColumn(@org.junit.jupiter.api.io.TempDir java.io.File dir) throws Exception {
        java.io.File file = new java.io.File(dir, "old.db");
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file); var st = c.createStatement()) {
            st.execute("CREATE TABLE joins (uuid TEXT NOT NULL, name TEXT NOT NULL, ip TEXT NOT NULL, "
                    + "first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, PRIMARY KEY (uuid, ip))");
            st.execute("PRAGMA user_version = 2");
            st.execute("CREATE TABLE hack_results (id INTEGER PRIMARY KEY AUTOINCREMENT, scan_id INTEGER NOT NULL, hack_id TEXT NOT NULL, "
                    + "hack_name TEXT NOT NULL, result TEXT NOT NULL, probe_key TEXT, probe_answer TEXT, probe_channels TEXT)");
        }
        try (Storage old = new Storage(file, null)) {
            old.players().recordJoin("u-1", "Alice", "1.1.1.1");
            old.players().recordJoin("u-1", "Alice", "1.1.1.1");
            assertEquals(2L, old.players().getPlayerRecord("Alice").get("joins"));
        }
    }
}
