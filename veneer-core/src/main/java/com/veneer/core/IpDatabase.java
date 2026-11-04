package com.veneer.core;

import com.google.common.net.InetAddresses;
import com.veneer.VeneerPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;

/**
 * Provider (ASN) and country of an IP address, from the ip-location-db CSV files: IPtoASN (PDDL) for the provider,
 * geo-whois-asn-country (CC BY 4.0, NRO) for the country. The files are downloaded to {@code plugins/Veneer/geo/}
 * through jsDelivr and refreshed when older than {@code refreshDays}; until they are loaded, and when a download
 * fails, {@link #lookup} returns null and everything else works without them.
 * <p>
 * Addresses of VPNs, Cloudflare WARP and hosting are shared by strangers, so {@link Info#weak} marks them: by ASN
 * from the config list, or by a keyword in the provider name.
 */
public final class IpDatabase {

    private static final String CDN = "https://cdn.jsdelivr.net/npm/@ip-location-db/";
    private static final String[][] FILES = {
            {"iptoasn-asn/iptoasn-asn-ipv4.csv", "asn-ipv4.csv"},
            {"iptoasn-asn/iptoasn-asn-ipv6.csv", "asn-ipv6.csv"},
            {"geo-whois-asn-country/geo-whois-asn-country-ipv4.csv", "country-ipv4.csv"},
            {"geo-whois-asn-country/geo-whois-asn-country-ipv6.csv", "country-ipv6.csv"},
    };

    public record Info(int asn, String org, String country, boolean weak) {}

    /** Sorted address ranges of one family as unsigned 128-bit numbers (IPv4 in the low 32 bits), a value each. */
    private static final class Ranges {
        long[] startHi, startLo, endHi, endLo;
        Object[] value;
        int size;

        Ranges() { grow(1024); }

        private void grow(int n) {
            startHi = Arrays.copyOf(startHi == null ? new long[0] : startHi, n);
            startLo = Arrays.copyOf(startLo == null ? new long[0] : startLo, n);
            endHi = Arrays.copyOf(endHi == null ? new long[0] : endHi, n);
            endLo = Arrays.copyOf(endLo == null ? new long[0] : endLo, n);
            value = Arrays.copyOf(value == null ? new Object[0] : value, n);
        }

        void add(long[] start, long[] end, Object v) {
            if (size == value.length) grow(size * 2);
            startHi[size] = start[0]; startLo[size] = start[1];
            endHi[size] = end[0]; endLo[size] = end[1];
            value[size++] = v;
        }

        Object find(long[] ip) {
            int lo = 0, hi = size - 1, at = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (compare(startHi[mid], startLo[mid], ip[0], ip[1]) <= 0) { at = mid; lo = mid + 1; }
                else hi = mid - 1;
            }
            if (at < 0 || compare(ip[0], ip[1], endHi[at], endLo[at]) > 0) return null;
            return value[at];
        }
    }

    /** Index 0 is IPv4, 1 is IPv6: the families are kept apart, IPv6 ranges near :: would overlap IPv4 numbers. */
    private record Tables(Ranges[] asn, Ranges[] country, Map<Integer, String> orgs) {}

    private final VeneerPlugin plugin;
    private final File dir;
    private final int refreshDays;
    private final Set<Integer> weakAsns;
    private final List<String> weakKeywords;
    private volatile Tables tables;

    public IpDatabase(VeneerPlugin plugin, int refreshDays, Collection<Integer> weakAsns, Collection<String> weakKeywords) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "geo");
        this.refreshDays = Math.max(1, refreshDays);
        this.weakAsns = new HashSet<>(weakAsns);
        this.weakKeywords = weakKeywords.stream().map(k -> k.toLowerCase(Locale.ROOT)).filter(k -> !k.isBlank()).toList();
    }

    /** Ticks between checks for stale files; a server that runs for weeks still gets the weekly refresh. */
    private static final long RECHECK_TICKS = 6 * 3600 * 20L;

    /** Downloads what is missing or stale and loads the tables, off the main thread, then keeps them fresh. */
    public void start() {
        FoliaScheduler.runAsync(plugin, this::refresh);
    }

    private void refresh() {
        try {
            if (download() || tables == null) load();
        } catch (Exception e) {
            plugin.getLogger().warning("[Hacks] IP database not available: " + e);
        }
        if (plugin.isEnabled()) FoliaScheduler.runAsyncLater(plugin, this::refresh, RECHECK_TICKS);
    }

    public boolean loaded() { return tables != null; }

    /** Provider and country of {@code ip}, or null when the database is not loaded or the address is unknown. */
    public Info lookup(String ip) {
        Tables t = tables;
        if (t == null || ip == null) return null;
        long[] n = parse(ip);
        if (n == null) return null;
        int family = (int) n[2];
        Integer asn = (Integer) t.asn[family].find(n);
        String country = (String) t.country[family].find(n);
        if (asn == null && country == null) return null;
        String org = asn != null ? t.orgs.getOrDefault(asn, "") : "";
        return new Info(asn != null ? asn : 0, org, country, asn != null && isWeak(asn, org));
    }

    private boolean isWeak(int asn, String org) {
        if (weakAsns.contains(asn)) return true;
        String lower = org.toLowerCase(Locale.ROOT);
        for (String k : weakKeywords) if (lower.contains(k)) return true;
        return false;
    }

    /** True when at least one file was replaced. */
    private boolean download() throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        long maxAge = refreshDays * 86_400_000L;
        boolean changed = false;
        for (String[] f : FILES) {
            File out = new File(dir, f[1]);
            if (out.isFile() && System.currentTimeMillis() - out.lastModified() < maxAge) continue;
            File part = new File(dir, f[1] + ".part");
            HttpResponse<java.nio.file.Path> res = http.send(
                    HttpRequest.newBuilder(URI.create(CDN + f[0])).timeout(Duration.ofMinutes(5)).build(),
                    HttpResponse.BodyHandlers.ofFile(part.toPath()));
            if (res.statusCode() != 200) {
                Files.deleteIfExists(part.toPath());
                // A stale copy is better than none
                if (out.isFile()) continue;
                throw new IllegalStateException(f[0] + ": HTTP " + res.statusCode());
            }
            Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            changed = true;
        }
        return changed;
    }

    private void load() throws Exception {
        Ranges[] asn = {new Ranges(), new Ranges()}, country = {new Ranges(), new Ranges()};
        Map<Integer, String> orgs = new HashMap<>();
        Map<String, String> codes = new HashMap<>();
        for (int f = 0; f < 2; f++) {
            Ranges a = asn[f], c = country[f];
            read(f == 0 ? "asn-ipv4.csv" : "asn-ipv6.csv", (s, e, rest) -> {
                int comma = rest.indexOf(',');
                int number = Integer.parseInt(comma < 0 ? rest : rest.substring(0, comma));
                if (comma >= 0) orgs.putIfAbsent(number, unquote(rest.substring(comma + 1)));
                a.add(s, e, number);
            });
            read(f == 0 ? "country-ipv4.csv" : "country-ipv6.csv", (s, e, rest) -> c.add(s, e, codes.computeIfAbsent(rest, k -> k)));
        }
        tables = new Tables(asn, country, orgs);
        plugin.getLogger().info("[Hacks] IP database loaded: " + (asn[0].size + asn[1].size) + " provider ranges, "
                + (country[0].size + country[1].size) + " country ranges.");
    }

    private interface Row { void accept(long[] start, long[] end, String rest); }

    /** Rows are {@code start,end,rest}; the files list ranges in ascending order. */
    private void read(String name, Row row) throws Exception {
        try (BufferedReader in = Files.newBufferedReader(new File(dir, name).toPath())) {
            String line;
            while ((line = in.readLine()) != null) {
                int a = line.indexOf(','), b = a < 0 ? -1 : line.indexOf(',', a + 1);
                if (b < 0) continue;
                long[] s = parse(line.substring(0, a)), e = parse(line.substring(a + 1, b));
                if (s == null || e == null) continue;
                try { row.accept(s, e, line.substring(b + 1)); } catch (NumberFormatException ignored) {}
            }
        }
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1).replace("\"\"", "\"") : s;
    }

    /** An IP literal as {hi, lo, family} (family 0 = IPv4, 1 = IPv6); null for anything else (never resolves names). */
    static long[] parse(String ip) {
        if (ip.indexOf(':') < 0) {
            String[] p = ip.split("\\.", -1);
            if (p.length != 4) return null;
            long v = 0;
            for (String part : p) {
                if (part.isEmpty() || part.length() > 3) return null;
                int x;
                try { x = Integer.parseInt(part); } catch (NumberFormatException e) { return null; }
                if (x > 255) return null;
                v = v << 8 | x;
            }
            return new long[]{0, v, 0};
        }
        try {
            // literals only: InetAddresses.forString never touches DNS (unlike InetAddress.getByName)
            byte[] b = InetAddresses.forString(ip).getAddress();
            if (b.length == 4) return parse(InetAddress.getByAddress(b).getHostAddress());
            long hi = 0, lo = 0;
            for (int i = 0; i < 8; i++) hi = hi << 8 | (b[i] & 0xff);
            for (int i = 8; i < 16; i++) lo = lo << 8 | (b[i] & 0xff);
            return new long[]{hi, lo, 1};
        } catch (Exception e) {
            return null;
        }
    }

    private static int compare(long aHi, long aLo, long bHi, long bLo) {
        int c = Long.compareUnsigned(aHi, bHi);
        return c != 0 ? c : Long.compareUnsigned(aLo, bLo);
    }
}
