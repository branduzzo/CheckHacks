package com.veneer.spoof.managers;

import com.veneer.VeneerPlugin;
import com.veneer.core.FoliaScheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Staff decisions on client brands no {@code client-brands.brands} pattern matches. An unknown brand gets a
 * short id when it is first alerted; the alert's buttons (or {@code /spoof brands allow|block <id>}) record a
 * verdict for that exact brand string, kept in {@code data.db}. {@link ConfigManager#getMatchingClientBrand}
 * then treats it as the built-in brand {@link ConfigManager#STAFF_ALLOWED} or {@link ConfigManager#STAFF_BLOCKED}.
 */
public class BrandVerdicts {

    public enum Verdict { ALLOW, BLOCK }

    /** Undecided brands kept at most: a client can send any brand, so a flood of random ones must not grow the table. */
    private static final int MAX_UNDECIDED = 500;

    public static final class Entry {
        private final int id;
        private final String brand;
        private final long firstSeen;
        private volatile Verdict verdict;
        private volatile String decidedBy;
        private volatile long decidedAt;
        private volatile String lastPlayer;

        Entry(int id, String brand, long firstSeen) {
            this.id = id;
            this.brand = brand;
            this.firstSeen = firstSeen;
        }

        public int id() { return id; }
        public String brand() { return brand; }
        public Verdict verdict() { return verdict; }
        public String decidedBy() { return decidedBy; }
        public long decidedAt() { return decidedAt; }
        public String lastPlayer() { return lastPlayer; }
    }

    private final VeneerPlugin plugin;
    private final Map<String, Entry> byBrand = new ConcurrentHashMap<>();
    private final Map<Integer, Entry> byId = new ConcurrentHashMap<>();
    private final AtomicInteger lastId = new AtomicInteger();

    public BrandVerdicts(VeneerPlugin plugin) {
        this.plugin = plugin;
        for (Map<String, Object> row : plugin.database().settings().getBrandVerdicts()) {
            Entry e = new Entry(((Number) row.get("id")).intValue(), (String) row.get("brand"),
                    ((Number) row.get("first_seen")).longValue());
            String v = (String) row.get("verdict");
            try {
                e.verdict = v == null ? null : Verdict.valueOf(v.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                // an edited or foreign value: treat the brand as undecided instead of failing the module
                plugin.getLogger().warning("Unknown brand verdict '" + v + "' for " + e.brand + ", treated as undecided");
            }
            e.decidedBy = (String) row.get("decided_by");
            e.decidedAt = row.get("decided_at") instanceof Number n ? n.longValue() : 0;
            e.lastPlayer = (String) row.get("last_player");
            byBrand.put(e.brand, e);
            byId.put(e.id, e);
            lastId.accumulateAndGet(e.id, Math::max);
        }
    }

    /** The verdict for this exact brand, or null when staff have not decided. */
    public Verdict verdict(String brand) {
        Entry e = brand == null ? null : byBrand.get(brand);
        return e == null ? null : e.verdict;
    }

    /** The entry for an unknown brand a player was seen with, created on first sight; null past the undecided cap. */
    public Entry seen(String brand, String player) {
        Entry e = byBrand.get(brand);
        if (e == null) {
            if (byBrand.values().stream().filter(x -> x.verdict == null).count() >= MAX_UNDECIDED) return null;
            e = byBrand.computeIfAbsent(brand, b -> {
                Entry created = new Entry(lastId.incrementAndGet(), b, System.currentTimeMillis());
                byId.put(created.id, created);
                return created;
            });
        }
        e.lastPlayer = player;
        save(e);
        return e;
    }

    public Entry get(int id) {
        return byId.get(id);
    }

    /** Records (or with null clears) the verdict; false when there is no such id. */
    public boolean decide(int id, Verdict verdict, String by) {
        Entry e = byId.get(id);
        if (e == null) return false;
        e.verdict = verdict;
        e.decidedBy = verdict == null ? null : by;
        e.decidedAt = verdict == null ? 0 : System.currentTimeMillis();
        save(e);
        return true;
    }

    /** All entries, undecided first, then newest first. */
    public List<Entry> all() {
        List<Entry> list = new ArrayList<>(byId.values());
        list.sort(Comparator.<Entry, Boolean>comparing(e -> e.verdict != null).thenComparing(e -> -e.id));
        return list;
    }

    /** Writes the entry as it is when the task runs, so saves finishing out of order still leave the latest state. */
    private void save(Entry e) {
        FoliaScheduler.runAsync(plugin, () -> {
            Verdict v = e.verdict;
            plugin.database().settings().saveBrandVerdict(e.id, e.brand, v == null ? null : v.name().toLowerCase(Locale.ROOT),
                    e.decidedBy, e.decidedAt, e.firstSeen, e.lastPlayer);
        });
    }
}
