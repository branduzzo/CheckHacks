package com.veneer.core.db;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * The SQLite file and its connections. SQLite allows one writer at a time but, in WAL mode, any number of readers
 * next to it, so there is one write connection (behind a lock) and a small pool of read-only connections.
 * <p>
 * Before this, every query went through one connection under one lock: a slow panel request (the link graph, the
 * player list) held up the scan, join and fingerprint saves queued behind it, and the GUI waited on the main
 * thread. Now reads never wait for writes and writes only wait for other writes.
 */
public final class Database implements AutoCloseable {

    /** What runs on a connection. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private static final int READERS = 4;
    /** How long a statement waits for a lock held by another connection before failing with SQLITE_BUSY. */
    private static final int BUSY_TIMEOUT_MS = 5_000;

    private final Logger log;
    private final String url;
    private final ReentrantLock writeLock = new ReentrantLock(true);
    private final BlockingQueue<Connection> readers = new ArrayBlockingQueue<>(READERS);
    private final List<Connection> all = new ArrayList<>();
    private Connection writer;
    private volatile boolean closed;

    public Database(File file, Logger log) {
        this.log = log;
        file.getAbsoluteFile().getParentFile().mkdirs();
        this.url = "jdbc:sqlite:" + file.getAbsolutePath();
        try {
            Class.forName("org.sqlite.JDBC");
            writer = open(false);
            Schema.create(writer, log);
            for (int i = 0; i < READERS; i++) readers.add(open(true));
        } catch (Exception e) {
            log.severe("Failed to open SQLite database " + file + ": " + e.getMessage());
            closeAll();
            writer = null;
            readers.clear();
        }
    }

    private Connection open(boolean readOnly) throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("PRAGMA synchronous = NORMAL");
            // 16 MB page cache per connection (negative = KiB), temp b-trees for GROUP BY / ORDER BY in memory
            s.execute("PRAGMA cache_size = -16000");
            s.execute("PRAGMA temp_store = MEMORY");
            if (readOnly) s.execute("PRAGMA query_only = ON");
        }
        all.add(c);
        return c;
    }

    public boolean available() {
        return writer != null && !closed;
    }

    /** Runs a query on a pooled read connection; {@code fallback} when the database is unavailable or the query fails. */
    public <T> T read(String what, T fallback, Work<T> work) {
        if (!available()) return fallback;
        Connection c;
        try {
            c = readers.poll(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        }
        if (c == null) {
            log.warning(what + ": no free database connection");
            return fallback;
        }
        try {
            return work.run(c);
        } catch (SQLException e) {
            log.warning(what + ": " + e.getMessage());
            return fallback;
        } finally {
            readers.offer(c);
        }
    }

    /** Runs statements on the write connection, one writer at a time. */
    public <T> T write(String what, T fallback, Work<T> work) {
        if (!available()) return fallback;
        writeLock.lock();
        try {
            return work.run(writer);
        } catch (SQLException e) {
            log.warning(what + ": " + e.getMessage());
            return fallback;
        } finally {
            writeLock.unlock();
        }
    }

    /** {@link #write} in one transaction: everything or nothing is stored. */
    public <T> T transaction(String what, T fallback, Work<T> work) {
        return write(what, fallback, c -> {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try { c.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        });
    }

    @Override
    public void close() {
        if (closed) return;
        // Wait for a running write so it is not cut off halfway
        writeLock.lock();
        try {
            closed = true;
            if (writer != null) {
                try (Statement s = writer.createStatement()) {
                    // Refreshes the planner statistics the indexes depend on; cheap when nothing changed
                    s.execute("PRAGMA optimize");
                } catch (SQLException ignored) {}
            }
            closeAll();
        } finally {
            writeLock.unlock();
        }
    }

    private void closeAll() {
        for (Connection c : all) {
            try {
                if (!c.isClosed()) c.close();
            } catch (SQLException e) {
                log.warning("close: " + e.getMessage());
            }
        }
        all.clear();
    }
}
