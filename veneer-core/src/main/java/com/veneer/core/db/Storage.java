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
    private final LinkGraph links;

    public Storage(File file, Logger log) {
        db = new Database(file, log);
        scans = new ScanStore(db);
        players = new PlayerStore(db);
        fingerprints = new FingerprintStore(db);
        tokens = new TokenStore(db);
        settings = new SettingsStore(db);
        links = new LinkGraph(db, scans, players, fingerprints);
    }

    public ScanStore scans() { return scans; }
    public PlayerStore players() { return players; }
    public FingerprintStore fingerprints() { return fingerprints; }
    public TokenStore tokens() { return tokens; }
    public SettingsStore settings() { return settings; }
    public LinkGraph links() { return links; }

    public boolean available() { return db.available(); }

    /**
     * Deletes what is older than the given number of days (0 or less keeps it): addresses and skins of accounts
     * not seen for {@code linkDays} (a skin is refreshed on join, so an old one belongs to an absent player), and
     * scans with their results after {@code scanDays}.
     */
    public void purge(int linkDays, int scanDays) {
        if (linkDays <= 0 && scanDays <= 0) return;
        long now = System.currentTimeMillis();
        db.transaction("purge", null, c -> {
            if (linkDays > 0) players.purgeBefore(c, now - linkDays * 86_400_000L);
            // results go with their scan (ON DELETE CASCADE)
            if (scanDays > 0) scans.purgeBefore(c, now - scanDays * 86_400_000L);
            players.pruneAccounts(c);
            return null;
        });
    }

    @Override
    public void close() {
        db.close();
    }
}
