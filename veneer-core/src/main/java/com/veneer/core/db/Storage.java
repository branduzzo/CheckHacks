package com.veneer.core.db;

import java.io.File;
import java.util.logging.Logger;

/**
 * Everything Veneer stores, split by subject. One SQLite file ({@link Database}) behind it; each store owns its
 * tables and queries:
 * <ul>
 *   <li>{@link #scans()} mod scans and language checks with their results</li>
 *   <li>{@link #players()} accounts: addresses, clients, skins, name search, the player list</li>
 *   <li>{@link #fingerprints()} RPTrack cache fingerprints</li>
 *   <li>{@link #tokens()} web panel log-in tokens</li>
 *   <li>{@link #settings()} brand verdicts and message language choices</li>
 *   <li>{@link #manualLinks()} account links marked by hand</li>
 *   <li>{@link #links()} the account link graph built from the above</li>
 * </ul>
 * Every method may be called from any thread, but they do I/O: call them off the main thread.
 */
public final class Storage implements AutoCloseable {

    private final Database db;
    private final ScanStore scans;
    private final PlayerStore players;
    private final FingerprintStore fingerprints;
    private final TokenStore tokens;
    private final SettingsStore settings;
    private final ManualLinkStore manualLinks;
    private final LinkGraph links;

    public Storage(File file, Logger log) {
        db = new Database(file, log);
        scans = new ScanStore(db);
        players = new PlayerStore(db);
        fingerprints = new FingerprintStore(db);
        tokens = new TokenStore(db);
        settings = new SettingsStore(db);
        manualLinks = new ManualLinkStore(db);
        links = new LinkGraph(db, scans, players, fingerprints, manualLinks);
    }

    public ScanStore scans() { return scans; }
    public PlayerStore players() { return players; }
    public FingerprintStore fingerprints() { return fingerprints; }
    public TokenStore tokens() { return tokens; }
    public SettingsStore settings() { return settings; }
    public ManualLinkStore manualLinks() { return manualLinks; }
    public LinkGraph links() { return links; }

    public boolean available() { return db.available(); }

    /** Scans deleted per transaction: their results go with them, and other writes get their turn in between. */
    private static final int PURGE_BATCH = 2_000;

    /**
     * Deletes what is older than the given number of days (0 or less keeps it): addresses, skins, clients and
     * RPTrack fingerprints of accounts not seen for {@code linkDays} (they are refreshed on every join, so an old one
     * belongs to an absent player), and scans with their results after {@code scanDays}. Expired web panel links
     * and sessions go whatever the settings are. Call it off the main thread.
     * <p>
     * In small transactions: one big delete (a year of scans with their results) held the only write connection
     * for seconds, and every join and scan waited behind it.
     *
     * @return the rows deleted
     */
    public int purge(int linkDays, int scanDays) {
        long now = System.currentTimeMillis();
        int removed = tokens.purgeExpired(now);
        if (scanDays > 0) {
            long before = now - scanDays * 86_400_000L;
            int batch;
            do {
                // results go with their scan (ON DELETE CASCADE)
                batch = db.transaction("purge scans", 0, c -> scans.purgeBefore(c, before, PURGE_BATCH));
                removed += batch;
            } while (batch == PURGE_BATCH && db.available());
        }
        if (linkDays > 0)
            removed += db.transaction("purge accounts", 0, c -> players.purgeBefore(c, now - linkDays * 86_400_000L));
        if (linkDays > 0 || scanDays > 0)
            removed += db.transaction("prune accounts", 0, players::pruneAccounts);
        if (removed > 0) db.reclaim();
        return removed;
    }

    @Override
    public void close() {
        db.close();
    }
}
