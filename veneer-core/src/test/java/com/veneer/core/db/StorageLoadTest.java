package com.veneer.core.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saves must keep up while the web panel reads hard. With the old single connection behind {@code synchronized},
 * four busy panel threads starved the writer: one save waited over a minute. Bounds here are loose (a slow CI
 * machine passes); a regression to lock starvation does not.
 */
class StorageLoadTest {

    private static final String[] MODS = {"meteor", "wurst", "aristois", "liquidbounce", "impact", "future"};

    @TempDir
    Path dir;

    @Test
    void savesAreNotStarvedByPanelReads() throws Exception {
        int accounts = Integer.getInteger("veneer.load.accounts", 3_000);
        try (Storage storage = new Storage(dir.resolve("data.db").toFile(), Logger.getLogger("load"))) {
            for (int i = 0; i < accounts; i++) {
                storage.players().recordJoin(uuid(i), "Player" + i, "10.0." + (i % 250) + "." + (i % 7));
                storage.scans().saveScanWithResults("hack", "Player" + i, uuid(i), "staff", null, i % 9 == 0, results(i % 9 == 0));
            }

            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong reads = new AtomicLong();
            ExecutorService panel = Executors.newFixedThreadPool(4);
            for (int t = 0; t < 4; t++) {
                int k = t;
                panel.submit(() -> {
                    int n = k;
                    while (!stop.get()) {
                        switch (n++ % 5) {
                            case 0 -> storage.players().listPlayers(46, (n % 50) * 46);
                            case 1 -> storage.scans().getStats();
                            case 2 -> storage.players().searchPlayers("play", 12);
                            case 3 -> storage.scans().getRecentScans(null, 100, 0, null, null);
                            default -> storage.scans().getDetectedMods();
                        }
                        reads.incrementAndGet();
                    }
                });
            }

            List<Long> waits = new ArrayList<>();
            int saves = 200;
            long begin = System.nanoTime();
            for (int i = 0; i < saves; i++) {
                long t = System.nanoTime();
                storage.players().recordJoin(uuid(i), "Player" + i, "10.9.9." + (i % 200));
                storage.scans().saveScanWithResults("hack", "Player" + i, uuid(i), "staff", null, false, results(false));
                waits.add((System.nanoTime() - t) / 1_000_000);
            }
            long totalMs = (System.nanoTime() - begin) / 1_000_000;
            stop.set(true);
            panel.shutdown();
            assertTrue(panel.awaitTermination(1, TimeUnit.MINUTES));

            Collections.sort(waits);
            long p50 = waits.get(saves / 2), p99 = waits.get(saves * 99 / 100), max = waits.get(saves - 1);
            System.out.printf("[load] %d accounts: %d saves in %d ms (p50 %d ms, p99 %d ms, max %d ms) next to %d panel reads%n",
                    accounts, saves, totalMs, p50, p99, max, reads.get());
            assertTrue(max < 5_000, "a save waited " + max + " ms behind panel reads");
            assertTrue(totalMs < 30_000, "saves took " + totalMs + " ms");
            assertTrue(reads.get() > 0, "the panel threads did not run");
            assertEquals(accounts, storage.players().listPlayers(accounts + 10, 0).size());
        }
    }

    private static String uuid(int i) {
        return String.format("00000000-0000-0000-0000-%012d", i);
    }

    private static List<Object[]> results(boolean detected) {
        List<Object[]> out = new ArrayList<>();
        for (int m = 0; m < MODS.length; m++)
            out.add(new Object[]{MODS[m], MODS[m].toUpperCase(), detected && m == 0 ? "DETECTED" : "NOT_DETECTED"});
        return out;
    }
}
