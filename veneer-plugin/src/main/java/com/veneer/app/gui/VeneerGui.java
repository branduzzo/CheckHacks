package com.veneer.app.gui;

import com.veneer.app.Veneer;
import com.veneer.core.Alerts;
import com.veneer.core.FoliaScheduler;
import com.veneer.core.Messages;
import com.veneer.core.PlayerProfile;
import com.veneer.spoof.managers.BrandVerdicts;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class VeneerGui implements Listener {

    private static final boolean GRIM_API;

    static {
        boolean g = false;
        try { Class.forName("ac.grim.grimac.api.GrimAPIProvider"); g = true; } catch (ClassNotFoundException ignored) {}
        GRIM_API = g;
    }

    private static final int PAGE_SIZE = 45;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    // Main chest menu slot layout (4 rows = 36 slots)
    private static final int SLOT_SPOOF = 1, SLOT_HACKS = 3, SLOT_RPTRACK = 5;
    private static final int SLOT_PLAYERS = 10, SLOT_DETECTIONS = 11, SLOT_BRANDS = 12,
            SLOT_GRIM = 13, SLOT_ALERTS = 14, SLOT_EDITOR = 15, SLOT_CHANNELS = 16;
    private static final int SLOT_RELOAD = 19, SLOT_VERSION = 21;
    private static final int SLOT_CLOSE = 35;

    // Sub-page nav row (row 6, slots 45–53)
    private static final int NAV_PREV = 45, NAV_SEARCH = 46, NAV_BACK = 49, NAV_NEXT = 52, NAV_CLOSE2 = 53;

    // Player info page slots (4 rows = 36 slots)
    private static final int PI_HEAD = 4;
    private static final int PI_BRAND = 10, PI_STATUS = 11, PI_CHANNELS = 12, PI_SCANS = 13,
            PI_GRIM = 14, PI_SENSITIVITY = 15;
    private static final int PI_CHECK = 19, PI_DETECTIONS = 22, PI_ALERTS = 25;
    private static final int PI_BACK = 27, PI_CLOSE = 35;

    public enum Page { MAIN, PLAYERS, DETECTIONS, BRANDS, GRIM, PLAYER_INFO, CHANNELS, ALERTS }

    record VeneerMenuHolder(Page page, int pageNum, String query) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private final Veneer plugin;
    /** The channel group an item of the channels page stands for, so a click can find its players. */
    private final NamespacedKey channelsKey;
    private final GrimViolationTracker grimTracker;
    private final Map<UUID, Page> awaitingSearch = new ConcurrentHashMap<>();

    public VeneerGui(Veneer plugin) {
        this.plugin = plugin;
        GrimViolationTracker tracker = null;
        if (GRIM_API) {
            try {
                tracker = new GrimViolationTracker(plugin);
            } catch (LinkageError e) {
                plugin.getLogger().warning(GrimViolationTracker.UNSUPPORTED_MESSAGE);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not hook GrimAC: " + e);
            }
        }
        grimTracker = tracker;
        channelsKey = new NamespacedKey(plugin, "channels");
    }

    // ── Public entry points ──────────────────────────────────────────────────

    public boolean open(Player player) {
        if (!player.hasPermission("veneer.admin")) return false;
        openMainChest(player);
        return true;
    }

    public boolean openPage(Player player, String[] args) {
        if (!player.hasPermission("veneer.admin")) return false;
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        int pageNum = args.length > 2 ? parseInt(args[2]) : 0;
        String query = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : null;
        switch (sub) {
            case "players"    -> openPlayers(player, pageNum, query);
            case "detections" -> openDetections(player, pageNum, null);
            case "brands"     -> openBrands(player, pageNum);
            case "grim"       -> { if (grimTracker != null) openGrim(player, pageNum); else open(player); }
            case "channels"   -> openChannels(player, pageNum);
            case "alerts"     -> openAlerts(player, pageNum, query);
            default           -> open(player);
        }
        return true;
    }

    /** Opens the player info page for a specific target. */
    public void openPlayerInfo(Player admin, String targetName) {
        if (!admin.hasPermission("veneer.admin")) return;
        load(admin, () -> {
            Map<String, Object> record = plugin.database().players().getPlayerRecord(targetName);
            String uuid = (String) record.get("uuid");
            List<Map<String, Object>> scans = uuid == null ? List.of()
                    : plugin.database().scans().getRecentScans(null, 3, 0, null, uuid);
            return new PlayerInfo(record, scans);
        }, info -> showPlayerInfo(admin, targetName, info.record(), info.scans()));
    }

    private record PlayerInfo(Map<String, Object> record, List<Map<String, Object>> scans) {}

    private void showPlayerInfo(Player admin, String targetName, Map<String, Object> record, List<Map<String, Object>> scans) {
        Messages m = plugin.messages().in(admin);

        if (record.isEmpty()) {
            m.send(admin, "player-not-found", "player", targetName);
            return;
        }

        String uuid = (String) record.get("uuid");
        Player target = plugin.getServer().getPlayerExact(targetName);
        PlayerProfile profile = target != null ? plugin.profileOf(target.getUniqueId()) : null;
        boolean isOnline = target != null;

        @SuppressWarnings("unchecked")
        Map<String, Object> client = (Map<String, Object>) record.get("client");
        String brand = profile != null && plugin.brandOf(target) != null ? plugin.brandOf(target)
                : client != null ? (String) client.get("brand") : null;
        @SuppressWarnings("unchecked")
        List<String> channels = profile != null ? new ArrayList<>(profile.getChannels())
                : client != null ? (List<String>) client.get("channels") : List.of();
        long firstSeen = record.get("first_seen") instanceof Number n ? n.longValue() : 0;
        long lastSeen  = record.get("last_seen")  instanceof Number n ? n.longValue() : 0;
        long joins     = record.get("joins")       instanceof Number n ? n.longValue() : 0;

        Inventory inv = plugin.getServer().createInventory(
                new VeneerMenuHolder(Page.PLAYER_INFO, 0, targetName), 36,
                Component.text(targetName).color(isOnline ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                        .decoration(TextDecoration.ITALIC, false));

        ItemStack glass = noName(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 36; i++) inv.setItem(i, glass);

        // Head
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) skull.getItemMeta();
        try { sm.setOwningPlayer(plugin.getServer().getOfflinePlayer(UUID.fromString(uuid))); } catch (Exception ignored) {}
        sm.displayName(Component.text(targetName).color(isOnline ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> headLore = new ArrayList<>();
        headLore.add((isOnline ? m.get("gui-player-online") : m.get("gui-player-offline"))
                .decoration(TextDecoration.ITALIC, false));
        if (firstSeen > 0) headLore.add(m.get("gui-player-first-seen", Map.of("date", DATE_FMT.format(Instant.ofEpochMilli(firstSeen))))
                .decoration(TextDecoration.ITALIC, false));
        if (joins > 0) headLore.add(m.get("gui-player-joins", Map.of("count", String.valueOf(joins)))
                .decoration(TextDecoration.ITALIC, false));
        sm.lore(headLore);
        skull.setItemMeta(sm);
        inv.setItem(PI_HEAD, skull);

        // Brand
        if (brand != null) {
            inv.setItem(PI_BRAND, infoItem(Material.NAME_TAG,
                    m.get("gui-player-brand", Map.of("brand", brand)), null));
        }

        // Online / last seen status
        inv.setItem(PI_STATUS, infoItem(
                isOnline ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE,
                isOnline ? m.get("gui-player-online") : m.get("gui-player-offline"),
                lastSeen > 0 ? m.get("gui-player-last-seen", Map.of("date", DATE_FMT.format(Instant.ofEpochMilli(lastSeen)))) : null));

        // Channels
        if (!channels.isEmpty()) {
            String chanStr = channels.size() > 5
                    ? String.join(", ", channels.subList(0, 5)) + "  +" + (channels.size() - 5)
                    : String.join(", ", channels);
            inv.setItem(PI_CHANNELS, infoItem(Material.COMPARATOR,
                    m.get("gui-player-channels", Map.of("count", String.valueOf(channels.size()))),
                    Component.text(chanStr).color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        }

        // Recent scan summary (loaded with the record, off the main thread)
        if (!scans.isEmpty()) {
            inv.setItem(PI_SCANS, infoItem(Material.PAPER,
                    m.get("gui-player-scans", Map.of("count", String.valueOf(scans.size()))),
                    Component.text(DATE_FMT.format(Instant.ofEpochMilli(
                            ((Number) scans.get(0).getOrDefault("timestamp", 0L)).longValue())))
                            .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        }

        // GrimAC flags + mouse sensitivity
        if (GRIM_API && grimTracker != null && uuid != null) {
            UUID uid = safeUuid(uuid);
            List<GrimViolationTracker.Violation> grimFlags = grimTracker.getByPlayer(uid, 99);
            if (!grimFlags.isEmpty()) {
                inv.setItem(PI_GRIM, infoItem(Material.SHIELD,
                        m.get("gui-grim-flags-count", Map.of("count", String.valueOf(grimFlags.size()))), null));
            }
            GrimViolationTracker.SensitivitySnapshot sens = grimTracker.getSensitivity(uid);
            if (sens != null) {
                boolean nonDefault = !sens.isDefault();
                boolean nonVanilla = !sens.vanillaMath();
                Component sensTitle = m.get("gui-player-sensitivity",
                        Map.of("h", String.valueOf(sens.hPct()), "v", String.valueOf(sens.vPct())));
                Component sensLore = nonVanilla
                        ? m.get("gui-player-sensitivity-nonvanilla").decoration(TextDecoration.ITALIC, false)
                        : nonDefault
                        ? m.get("gui-player-sensitivity-nondefault").decoration(TextDecoration.ITALIC, false)
                        : m.get("gui-player-sensitivity-default").decoration(TextDecoration.ITALIC, false);
                Material sensMat = nonVanilla ? Material.MAGENTA_STAINED_GLASS_PANE
                        : nonDefault ? Material.YELLOW_STAINED_GLASS_PANE
                        : Material.GREEN_STAINED_GLASS_PANE;
                inv.setItem(PI_SENSITIVITY, infoItem(sensMat, sensTitle, sensLore));
            }
        }

        // Action buttons
        inv.setItem(PI_CHECK, navItem(Material.BLAZE_POWDER,
                m.get("gui-player-btn-check"), m.get("gui-player-btn-check-lore")));
        inv.setItem(PI_DETECTIONS, navItem(Material.WRITTEN_BOOK,
                m.get("gui-player-btn-scans"), m.get("gui-player-btn-scans-lore")));
        inv.setItem(PI_ALERTS, navItem(Material.BELL,
                m.get("gui-player-btn-alerts"), m.get("gui-player-btn-alerts-lore")));

        // Nav
        inv.setItem(PI_BACK,  barItem(Material.RED_STAINED_GLASS_PANE, m.get("gui-nav-back")));
        inv.setItem(PI_CLOSE, barItem(Material.BARRIER, m.get("gui-nav-close")));

        admin.openInventory(inv);
    }

    /** Runs all enabled checks for a player immediately (no dialog). */
    public void openCheckDialog(Player admin, String targetName) {
        if (!admin.hasPermission("veneer.admin")) return;
        Player target = plugin.getServer().getPlayerExact(targetName);
        Messages m = plugin.messages().in(admin);
        if (target == null) { m.send(admin, "player-not-found", "player", targetName); return; }
        boolean mods = admin.hasPermission("veneer.hacks.check"), lang = admin.hasPermission("veneer.hacks.lang");
        if (plugin.hacks().running() && (mods || lang))
            admin.performCommand("hackcheck " + targetName + (mods && lang ? " all" : mods ? " hack" : " lang"));
        if (plugin.rptrack().running() && admin.hasPermission("veneer.rptrack"))      admin.performCommand("rptrack " + targetName);
    }

    /**
     * Runs {@code query} off the main thread and then {@code show} on the player's thread. The pages used to query
     * SQLite while building the menu, on the main thread: a slow query (or one waiting for the database) froze
     * the server for that long.
     */
    private <T> void load(Player player, java.util.function.Supplier<T> query, java.util.function.Consumer<T> show) {
        FoliaScheduler.runAsync(plugin, () -> {
            T data = query.get();
            FoliaScheduler.runAtEntity(plugin, player, () -> {
                if (player.isOnline()) show.accept(data);
            });
        });
    }

    // ── Chest main menu ───────────────────────────────────────────────────────

    private void openMainChest(Player player) {
        Messages m = plugin.messages().in(player);
        Inventory inv = plugin.getServer().createInventory(
                new VeneerMenuHolder(Page.MAIN, 0, null), 36, m.get("gui-chest-title"));

        ItemStack glass = noName(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 36; i++) inv.setItem(i, glass);

        inv.setItem(SLOT_SPOOF,   moduleItem(plugin.spoof().running(),   m.get("gui-module-spoof"),   m));
        inv.setItem(SLOT_HACKS,   moduleItem(plugin.hacks().running(),   m.get("gui-module-hacks"),   m));
        inv.setItem(SLOT_RPTRACK, moduleItem(plugin.rptrack().running(), m.get("gui-module-rptrack"), m));

        inv.setItem(SLOT_PLAYERS,    navItem(Material.PLAYER_HEAD,  m.get("gui-btn-players"),    m.get("gui-btn-players-lore")));
        inv.setItem(SLOT_DETECTIONS, navItem(Material.WRITTEN_BOOK, m.get("gui-btn-detections"), m.get("gui-btn-detections-lore")));
        if (plugin.spoof().running() && player.hasPermission("veneer.spoof.use"))
            inv.setItem(SLOT_BRANDS, navItem(Material.NAME_TAG,     m.get("gui-btn-brands"),     m.get("gui-btn-brands-lore")));
        if (GRIM_API && grimTracker != null)
            inv.setItem(SLOT_GRIM,   navItem(Material.SHIELD,       m.get("gui-btn-grim"),       m.get("gui-btn-grim-lore")));
        if (player.hasPermission(Alerts.PERMISSION))
            inv.setItem(SLOT_ALERTS, navItem(Material.BELL,         m.get("gui-btn-alerts-log"), m.get("gui-btn-alerts-log-lore")));
        if (plugin.hacks().running() && player.hasPermission("veneer.hacks.editor"))
            inv.setItem(SLOT_EDITOR, navItem(Material.WRITABLE_BOOK, m.get("gui-btn-editor"),    m.get("gui-btn-editor-lore")));
        inv.setItem(SLOT_CHANNELS, navItem(Material.COMPARATOR, m.get("gui-btn-channels"), m.get("gui-btn-channels-lore")));

        inv.setItem(SLOT_RELOAD,  navItem(Material.COMPARATOR,    m.get("gui-btn-reload"),   m.get("gui-btn-reload-lore")));
        inv.setItem(SLOT_VERSION, navItem(Material.KNOWLEDGE_BOOK, m.get("gui-btn-version"), m.get("gui-btn-version-lore")));
        inv.setItem(SLOT_CLOSE,   barItem(Material.BARRIER, m.get("gui-nav-close")));

        player.openInventory(inv);
    }

    // ── Players page ──────────────────────────────────────────────────────────

    private void openPlayers(Player player, int pageNum, String query) {
        boolean search = query != null && !query.isBlank();
        load(player, () -> search
                        ? plugin.database().players().searchPlayers(query, PAGE_SIZE + 1)
                        : plugin.database().players().listPlayers(PAGE_SIZE + 1, pageNum * PAGE_SIZE),
                rows -> showPlayers(player, pageNum, query, rows));
    }

    private void showPlayers(Player player, int pageNum, String query, List<Map<String, Object>> rows) {
        Messages m = plugin.messages().in(player);
        boolean mayHaveMore = rows.size() > PAGE_SIZE;
        if (mayHaveMore) rows = rows.subList(0, PAGE_SIZE);

        Component title = query != null && !query.isBlank()
                ? m.get("gui-page-players").append(Component.text(" (" + query + ")").color(NamedTextColor.GRAY))
                : m.get("gui-page-players");

        Inventory inv = plugin.getServer().createInventory(
                new VeneerMenuHolder(Page.PLAYERS, pageNum, query), 54, title);

        Set<String> online = new HashSet<>();
        for (Player p : plugin.getServer().getOnlinePlayers())
            online.add(p.getName().toLowerCase(Locale.ROOT));

        int slot = 0;
        for (Map<String, Object> row : rows) {
            if (slot >= PAGE_SIZE) break;
            String name = (String) row.get("name");
            String uuid = (String) row.get("uuid");
            long lastSeen = row.get("last_seen") instanceof Number n ? n.longValue() : 0;
            boolean isOnline = online.contains(name.toLowerCase(Locale.ROOT));

            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
            try { skullMeta.setOwningPlayer(plugin.getServer().getOfflinePlayer(UUID.fromString(uuid))); } catch (Exception ignored) {}
            skullMeta.displayName(Component.text(name).color(isOnline ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add((isOnline ? m.get("gui-player-online") : m.get("gui-player-offline"))
                    .decoration(TextDecoration.ITALIC, false));
            if (lastSeen > 0)
                lore.add(m.get("gui-player-last-seen", Map.of("date", DATE_FMT.format(Instant.ofEpochMilli(lastSeen))))
                        .decoration(TextDecoration.ITALIC, false));
            if (GRIM_API && grimTracker != null && grimTracker.hasViolations(safeUuid(uuid)))
                lore.add(m.get("gui-player-grim-flagged").decoration(TextDecoration.ITALIC, false));
            skullMeta.lore(lore);
            skull.setItemMeta(skullMeta);
            inv.setItem(slot++, skull);
        }

        fillGlass(inv, slot, PAGE_SIZE);
        buildNavRow(inv, pageNum, mayHaveMore, true, m);
        player.openInventory(inv);
    }

    // ── Detections page ───────────────────────────────────────────────────────

    private void openDetections(Player player, int pageNum, String playerFilter) {
        load(player, () -> plugin.database().scans().getRecentScans(null, PAGE_SIZE + 1, pageNum * PAGE_SIZE, true, playerFilter),
                rows -> showDetections(player, pageNum, playerFilter, rows));
    }

    private void showDetections(Player player, int pageNum, String playerFilter, List<Map<String, Object>> rows) {
        Messages m = plugin.messages().in(player);
        boolean mayHaveMore = rows.size() > PAGE_SIZE;
        if (mayHaveMore) rows = rows.subList(0, PAGE_SIZE);

        Component title = playerFilter != null
                ? m.get("gui-page-detections").append(Component.text(" (" + playerFilter + ")").color(NamedTextColor.GRAY))
                : m.get("gui-page-detections");

        Inventory inv = plugin.getServer().createInventory(
                new VeneerMenuHolder(Page.DETECTIONS, pageNum, playerFilter), 54, title);

        int slot = 0;
        for (Map<String, Object> row : rows) {
            if (slot >= PAGE_SIZE) break;
            String pName   = (String) row.get("target_name");
            String type    = (String) row.get("type");
            long   ts      = row.get("timestamp") instanceof Number n ? n.longValue() : 0;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) row.get("results");
            List<String> detected = new ArrayList<>();
            if (results != null)
                for (Map<String, Object> r : results)
                    if ("DETECTED".equals(r.get("result")))
                        detected.add((String) r.getOrDefault("hack_name", r.getOrDefault("language", "?")));

            ItemStack item = new ItemStack(detected.isEmpty() ? Material.PAPER : Material.WRITTEN_BOOK);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text(pName != null ? pName : "?").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(m.get("gui-detection-type", Map.of("type", type != null ? type.toUpperCase(Locale.ROOT) : "?"))
                    .decoration(TextDecoration.ITALIC, false));
            if (!detected.isEmpty())
                lore.add(m.get("gui-detection-mods", Map.of("mods", String.join(", ", detected)))
                        .decoration(TextDecoration.ITALIC, false));
            if (ts > 0)
                lore.add(m.get("gui-detection-date", Map.of("date", DATE_FMT.format(Instant.ofEpochMilli(ts))))
                        .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }

        fillGlass(inv, slot, PAGE_SIZE);
        buildNavRow(inv, pageNum, mayHaveMore, false, m);
        player.openInventory(inv);
    }

    // ── Alerts page ───────────────────────────────────────────────────────────

    private void openAlerts(Player player, int pageNum, String targetPlayer) {
        load(player, () -> plugin.database().scans().getRecentScans(null, PAGE_SIZE + 1, pageNum * PAGE_SIZE, true, targetPlayer),
                rows -> showAlerts(player, pageNum, targetPlayer, rows));
    }

    private void showAlerts(Player player, int pageNum, String targetPlayer, List<Map<String, Object>> rows) {
        Messages m = plugin.messages().in(player);
        boolean mayHaveMore = rows.size() > PAGE_SIZE;
        if (mayHaveMore) rows = rows.subList(0, PAGE_SIZE);

        Component title = targetPlayer != null
                ? m.get("gui-page-alerts").append(Component.text(" (" + targetPlayer + ")").color(NamedTextColor.GRAY))
                : m.get("gui-page-alerts");

        Inventory inv = plugin.getServer().createInventory(
                new VeneerMenuHolder(Page.ALERTS, pageNum, targetPlayer), 54, title);

        int slot = 0;
        for (Map<String, Object> row : rows) {
            if (slot >= PAGE_SIZE) break;
            String pName   = (String) row.get("target_name");
            String type    = (String) row.get("type");
            long   ts      = row.get("timestamp") instanceof Number n ? n.longValue() : 0;
            String checker = (String) row.getOrDefault("checker_name", "?");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) row.get("results");
            List<String> detected = new ArrayList<>();
            if (results != null)
                for (Map<String, Object> r : results)
                    if ("DETECTED".equals(r.get("result")))
                        detected.add((String) r.getOrDefault("hack_name", r.getOrDefault("language", "?")));

            ItemStack item = new ItemStack(Material.BELL);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text(pName != null ? pName : "?").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(m.get("gui-alert-type", Map.of("type", type != null ? type.toUpperCase(Locale.ROOT) : "?",
                    "checker", checker != null ? checker : "?")).decoration(TextDecoration.ITALIC, false));
            if (!detected.isEmpty())
                lore.add(m.get("gui-detection-mods", Map.of("mods", String.join(", ", detected)))
                        .decoration(TextDecoration.ITALIC, false));
            if (ts > 0)
                lore.add(m.get("gui-detection-date", Map.of("date", DATE_FMT.format(Instant.ofEpochMilli(ts))))
                        .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }

        if (slot == 0) {
            ItemStack none = noName(Material.STRUCTURE_VOID);
            ItemMeta meta = none.getItemMeta();
            meta.displayName(m.get("gui-alerts-empty").decoration(TextDecoration.ITALIC, false));
            none.setItemMeta(meta);
            inv.setItem(22, none);
        }

        fillGlass(inv, slot, PAGE_SIZE);
        buildNavRow(inv, pageNum, mayHaveMore, false, m);
        player.openInventory(inv);
    }

    // ── Brands page ───────────────────────────────────────────────────────────

    private void openBrands(Player player, int pageNum) {
        if (!plugin.spoof().running()) { open(player); return; }
        Messages m = plugin.messages().in(player);
        List<BrandVerdicts.Entry> all = plugin.spoof().verdicts().all();
        int from = pageNum * PAGE_SIZE, to = Math.min(from + PAGE_SIZE, all.size());
        List<BrandVerdicts.Entry> entries = from < all.size() ? all.subList(from, to) : List.of();

        Inventory inv = plugin.getServer().createInventory(
                new VeneerMenuHolder(Page.BRANDS, pageNum, null), 54, m.get("gui-page-brands"));

        int slot = 0;
        for (BrandVerdicts.Entry e : entries)
            inv.setItem(slot++, buildBrandItem(e, m));

        fillGlass(inv, slot, PAGE_SIZE);
        buildNavRow(inv, pageNum, all.size() > (long)(pageNum + 1) * PAGE_SIZE, false, m);
        player.openInventory(inv);
    }

    private ItemStack buildBrandItem(BrandVerdicts.Entry e, Messages m) {
        BrandVerdicts.Verdict v = e.verdict();
        Material mat = v == null ? Material.ORANGE_STAINED_GLASS
                : v == BrandVerdicts.Verdict.ALLOW ? Material.LIME_STAINED_GLASS : Material.RED_STAINED_GLASS;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(e.brand()).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add((v == null ? m.get("gui-brand-undecided")
                : v == BrandVerdicts.Verdict.ALLOW ? m.get("gui-brand-allowed") : m.get("gui-brand-blocked"))
                .decoration(TextDecoration.ITALIC, false));
        if (e.lastPlayer() != null)
            lore.add(m.get("gui-brand-last", Map.of("player", e.lastPlayer())).decoration(TextDecoration.ITALIC, false));
        lore.add(m.get("gui-brand-hint").decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ── GrimAC page ───────────────────────────────────────────────────────────

    private void openGrim(Player player, int pageNum) {
        Messages m = plugin.messages().in(player);
        List<GrimViolationTracker.Violation> all = grimTracker.getRecent(PAGE_SIZE * 10);
        int from = pageNum * PAGE_SIZE, to = Math.min(from + PAGE_SIZE, all.size());
        List<GrimViolationTracker.Violation> violations = from < all.size() ? all.subList(from, to) : List.of();

        Inventory inv = plugin.getServer().createInventory(
                new VeneerMenuHolder(Page.GRIM, pageNum, null), 54, m.get("gui-page-grim"));

        int slot = 0;
        for (GrimViolationTracker.Violation v : violations) {
            ItemStack item = new ItemStack(Material.SHIELD);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text(v.playerName()).color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(m.get("gui-grim-check", Map.of("check", v.check())).decoration(TextDecoration.ITALIC, false));
            if (!v.verbose().isEmpty())
                lore.add(m.get("gui-grim-verbose", Map.of("verbose", v.verbose())).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text(DATE_FMT.format(Instant.ofEpochMilli(v.time()))).color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }

        if (slot == 0) {
            ItemStack none = noName(Material.STRUCTURE_VOID);
            ItemMeta meta = none.getItemMeta();
            meta.displayName(m.get("gui-grim-empty").decoration(TextDecoration.ITALIC, false));
            none.setItemMeta(meta);
            inv.setItem(22, none);
        }

        fillGlass(inv, slot, PAGE_SIZE);
        buildNavRow(inv, pageNum, all.size() > (long)(pageNum + 1) * PAGE_SIZE, false, m);
        player.openInventory(inv);
    }

    // ── Channels page ─────────────────────────────────────────────────────────

    private void openChannels(Player player, int pageNum) {
        load(player, () -> plugin.database().players().getChannelGroups(PAGE_SIZE + 1, pageNum * PAGE_SIZE),
                rows -> showChannels(player, pageNum, rows));
    }

    private void showChannels(Player player, int pageNum, List<Map<String, Object>> rows) {
        Messages m = plugin.messages().in(player);
        boolean mayHaveMore = rows.size() > PAGE_SIZE;
        if (mayHaveMore) rows = rows.subList(0, PAGE_SIZE);

        Inventory inv = plugin.getServer().createInventory(
                new VeneerMenuHolder(Page.CHANNELS, pageNum, null), 54, m.get("gui-page-channels"));

        // Index online players by their channel set for kick support
        Map<String, List<Player>> onlineByChannels = new HashMap<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            PlayerProfile profile = plugin.profileOf(p.getUniqueId());
            if (profile == null) continue;
            String key = String.join(",", new java.util.TreeSet<>(profile.getChannels()));
            onlineByChannels.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        int slot = 0;
        for (Map<String, Object> row : rows) {
            if (slot >= PAGE_SIZE) break;
            String channels    = (String) row.get("channels");
            int    playerCount = ((Number) row.get("player_count")).intValue();
            long   lastSeen    = row.get("last_seen") instanceof Number n ? n.longValue() : 0;

            List<Player> online = onlineByChannels.getOrDefault(channels, List.of());

            String[] channelList = channels.split(",");
            String display = channelList.length > 3
                    ? channelList[0] + ", " + channelList[1] + ", +" + (channelList.length - 2)
                    : channels;

            Material mat = !online.isEmpty() ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text(display).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(m.get("gui-channel-players", Map.of("count", String.valueOf(playerCount))).decoration(TextDecoration.ITALIC, false));
            if (!online.isEmpty()) {
                String onlineNames = online.stream().map(Player::getName)
                        .limit(5).reduce((a, b) -> a + ", " + b).orElse("");
                if (online.size() > 5) onlineNames += " +" + (online.size() - 5);
                lore.add(m.get("gui-channel-online", Map.of("players", onlineNames)).decoration(TextDecoration.ITALIC, false));
                lore.add(m.get("gui-channel-kick-hint").decoration(TextDecoration.ITALIC, false));
            }
            if (lastSeen > 0)
                lore.add(m.get("gui-player-last-seen", Map.of("date", DATE_FMT.format(Instant.ofEpochMilli(lastSeen))))
                        .decoration(TextDecoration.ITALIC, false));
            meta.getPersistentDataContainer().set(channelsKey, PersistentDataType.STRING,
                    String.join(",", new TreeSet<>(Arrays.asList(channelList))));
            meta.lore(lore);
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }

        if (slot == 0) {
            ItemStack none = noName(Material.STRUCTURE_VOID);
            ItemMeta meta = none.getItemMeta();
            meta.displayName(m.get("gui-channels-empty").decoration(TextDecoration.ITALIC, false));
            none.setItemMeta(meta);
            inv.setItem(22, none);
        }

        fillGlass(inv, slot, PAGE_SIZE);
        buildNavRow(inv, pageNum, mayHaveMore, false, m);
        player.openInventory(inv);
    }

    // ── Navigation row ────────────────────────────────────────────────────────

    private void buildNavRow(Inventory inv, int pageNum, boolean hasNext, boolean hasSearch, Messages m) {
        ItemStack glass = noName(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 45; i < 54; i++) inv.setItem(i, glass);

        inv.setItem(NAV_PREV, pageNum > 0
                ? barItem(Material.ARROW, m.get("gui-nav-prev"))
                : noName(Material.GRAY_STAINED_GLASS_PANE));
        if (hasSearch)
            inv.setItem(NAV_SEARCH, barItem(Material.NAME_TAG, m.get("gui-nav-search")));
        inv.setItem(NAV_BACK, barItem(Material.RED_STAINED_GLASS_PANE, m.get("gui-nav-back")));
        inv.setItem(NAV_NEXT, hasNext
                ? barItem(Material.ARROW, m.get("gui-nav-next"))
                : noName(Material.GRAY_STAINED_GLASS_PANE));
        inv.setItem(NAV_CLOSE2, barItem(Material.BARRIER, m.get("gui-nav-close")));
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof VeneerMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();

        switch (holder.page()) {
            case MAIN        -> handleMain(player, slot);
            case PLAYERS     -> handlePlayers(player, holder, slot, event.getCurrentItem());
            case DETECTIONS  -> handleDetections(player, holder, slot, event.getCurrentItem());
            case BRANDS      -> handleBrands(player, holder, slot, event);
            case GRIM        -> handleGrim(player, holder, slot, event.getCurrentItem());
            case PLAYER_INFO -> handlePlayerInfo(player, holder, slot);
            case CHANNELS    -> handleChannels(player, holder, slot, event.getCurrentItem(), event.getClick());
            case ALERTS      -> handleAlerts(player, holder, slot, event.getCurrentItem());
        }
    }

    private void handleMain(Player player, int slot) {
        switch (slot) {
            case SLOT_PLAYERS    -> { player.closeInventory(); openPlayers(player, 0, null); }
            case SLOT_DETECTIONS -> { player.closeInventory(); openDetections(player, 0, null); }
            case SLOT_BRANDS     -> { player.closeInventory(); openBrands(player, 0); }
            case SLOT_GRIM       -> { if (grimTracker != null) { player.closeInventory(); openGrim(player, 0); } }
            case SLOT_ALERTS     -> { player.closeInventory(); openAlerts(player, 0, null); }
            case SLOT_CHANNELS   -> { player.closeInventory(); openChannels(player, 0); }
            case SLOT_EDITOR     -> { player.closeInventory(); player.performCommand("hackeditor"); }
            case SLOT_RELOAD     -> { player.closeInventory(); player.performCommand("veneer reload"); }
            case SLOT_VERSION    -> { player.closeInventory(); player.performCommand("veneer version"); }
            case SLOT_CLOSE      -> player.closeInventory();
        }
    }

    private void handlePlayers(Player player, VeneerMenuHolder holder, int slot, ItemStack item) {
        if (slot < PAGE_SIZE) {
            if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                String name = plain(item.getItemMeta().displayName());
                player.closeInventory();
                openPlayerInfo(player, name);
            }
            return;
        }
        switch (slot) {
            case NAV_PREV   -> { if (holder.pageNum() > 0) { player.closeInventory(); openPlayers(player, holder.pageNum() - 1, holder.query()); } }
            case NAV_SEARCH -> startSearch(player, Page.PLAYERS);
            case NAV_BACK   -> { player.closeInventory(); open(player); }
            case NAV_NEXT   -> { player.closeInventory(); openPlayers(player, holder.pageNum() + 1, holder.query()); }
            case NAV_CLOSE2 -> player.closeInventory();
        }
    }

    private void handleDetections(Player player, VeneerMenuHolder holder, int slot, ItemStack item) {
        if (slot < PAGE_SIZE) {
            if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                String name = plain(item.getItemMeta().displayName());
                player.closeInventory();
                openPlayerInfo(player, name);
            }
            return;
        }
        switch (slot) {
            case NAV_PREV   -> { if (holder.pageNum() > 0) { player.closeInventory(); openDetections(player, holder.pageNum() - 1, holder.query()); } }
            case NAV_BACK   -> { player.closeInventory(); open(player); }
            case NAV_NEXT   -> { player.closeInventory(); openDetections(player, holder.pageNum() + 1, holder.query()); }
            case NAV_CLOSE2 -> player.closeInventory();
        }
    }

    private void handleBrands(Player player, VeneerMenuHolder holder, int slot, InventoryClickEvent event) {
        if (slot < PAGE_SIZE && plugin.spoof().running()) {
            List<BrandVerdicts.Entry> all = plugin.spoof().verdicts().all();
            int idx = holder.pageNum() * PAGE_SIZE + slot;
            if (idx >= all.size()) return;
            BrandVerdicts.Entry e = all.get(idx);
            // Same right as /spoof brands allow|block|forget
            if (!player.hasPermission("veneer.spoof.admin")) {
                player.sendMessage(plugin.messages().in(player).get("no-permission"));
                return;
            }
            ClickType ct = event.getClick();
            BrandVerdicts.Verdict next;
            if (ct.isLeftClick()) next = BrandVerdicts.Verdict.ALLOW;
            else if (ct.isRightClick()) next = BrandVerdicts.Verdict.BLOCK;
            else if (ct == ClickType.MIDDLE) next = null;
            else return; // drop, number keys and the like must not reset a verdict
            plugin.spoof().verdicts().decide(e.id(), next, player.getName());
            Messages m = plugin.messages().in(player);
            event.getInventory().setItem(slot, buildBrandItem(plugin.spoof().verdicts().get(e.id()), m));
            return;
        }
        switch (slot) {
            case NAV_PREV   -> { if (holder.pageNum() > 0) { player.closeInventory(); openBrands(player, holder.pageNum() - 1); } }
            case NAV_BACK   -> { player.closeInventory(); open(player); }
            case NAV_NEXT   -> { player.closeInventory(); openBrands(player, holder.pageNum() + 1); }
            case NAV_CLOSE2 -> player.closeInventory();
        }
    }

    private void handleGrim(Player player, VeneerMenuHolder holder, int slot, ItemStack item) {
        if (slot < PAGE_SIZE) {
            if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                String name = plain(item.getItemMeta().displayName());
                player.closeInventory();
                openPlayerInfo(player, name);
            }
            return;
        }
        switch (slot) {
            case NAV_PREV   -> { if (holder.pageNum() > 0) { player.closeInventory(); openGrim(player, holder.pageNum() - 1); } }
            case NAV_BACK   -> { player.closeInventory(); open(player); }
            case NAV_NEXT   -> { player.closeInventory(); openGrim(player, holder.pageNum() + 1); }
            case NAV_CLOSE2 -> player.closeInventory();
        }
    }

    private void handlePlayerInfo(Player player, VeneerMenuHolder holder, int slot) {
        String targetName = holder.query();
        switch (slot) {
            case PI_CHECK      -> { player.closeInventory(); if (targetName != null) openCheckDialog(player, targetName); }
            case PI_DETECTIONS -> { player.closeInventory(); if (targetName != null) openDetections(player, 0, targetName); }
            case PI_ALERTS     -> { player.closeInventory(); if (targetName != null) openAlerts(player, 0, targetName); }
            case PI_BACK       -> { player.closeInventory(); open(player); }
            case PI_CLOSE      -> player.closeInventory();
        }
    }

    private void handleAlerts(Player player, VeneerMenuHolder holder, int slot, ItemStack item) {
        if (slot < PAGE_SIZE) {
            if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                String name = plain(item.getItemMeta().displayName());
                player.closeInventory();
                openPlayerInfo(player, name);
            }
            return;
        }
        switch (slot) {
            case NAV_PREV   -> { if (holder.pageNum() > 0) { player.closeInventory(); openAlerts(player, holder.pageNum() - 1, holder.query()); } }
            case NAV_BACK   -> { player.closeInventory(); open(player); }
            case NAV_NEXT   -> { player.closeInventory(); openAlerts(player, holder.pageNum() + 1, holder.query()); }
            case NAV_CLOSE2 -> player.closeInventory();
        }
    }

    private void handleChannels(Player player, VeneerMenuHolder holder, int slot, ItemStack item, ClickType clickType) {
        if (slot < PAGE_SIZE) {
            // Shift is the confirmation: a plain right-click must not kick a whole group by accident
            if (clickType != ClickType.SHIFT_RIGHT || item == null || !item.hasItemMeta()) return;
            String channelKey = item.getItemMeta().getPersistentDataContainer().get(channelsKey, PersistentDataType.STRING);
            if (channelKey == null || channelKey.isEmpty()) return;
            // Kick online players with this channel group
            int kicked = 0;
            for (Player p : List.copyOf(plugin.getServer().getOnlinePlayers())) {
                PlayerProfile profile = plugin.profileOf(p.getUniqueId());
                if (profile == null) continue;
                String key = String.join(",", new java.util.TreeSet<>(profile.getChannels()));
                if (channelKey.equals(key)) {
                    // Folia: each player is kicked on their own region thread
                    Component reason = plugin.messages().in(p).get("gui-channel-kick-reason");
                    FoliaScheduler.runAtEntity(plugin, p, () -> p.kick(reason));
                    kicked++;
                }
            }
            Messages m = plugin.messages().in(player);
            m.send(player, kicked > 0 ? "gui-channel-kicked" : "gui-channel-none-online",
                    "count", String.valueOf(kicked), "channels", channelKey);
            player.closeInventory();
            return;
        }
        switch (slot) {
            case NAV_PREV   -> { if (holder.pageNum() > 0) { player.closeInventory(); openChannels(player, holder.pageNum() - 1); } }
            case NAV_BACK   -> { player.closeInventory(); open(player); }
            case NAV_NEXT   -> { player.closeInventory(); openChannels(player, holder.pageNum() + 1); }
            case NAV_CLOSE2 -> player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Page page = awaitingSearch.remove(player.getUniqueId());
        if (page == null) return;
        event.setCancelled(true);
        String query = plain(event.message());
        if (".".equals(query)) return;
        FoliaScheduler.runAtEntity(plugin, player, () -> {
            if (page == Page.PLAYERS) openPlayers(player, 0, query);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void startSearch(Player player, Page page) {
        player.closeInventory();
        awaitingSearch.put(player.getUniqueId(), page);
        plugin.messages().in(player).send(player, "gui-search-prompt");
    }

    private ItemStack moduleItem(boolean running, Component name, Messages m) {
        ItemStack stack = new ItemStack(running ? Material.LIME_CONCRETE : Material.RED_CONCRETE);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name.color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of((running ? m.get("gui-module-on") : m.get("gui-module-off")).decoration(TextDecoration.ITALIC, false)));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack navItem(Material mat, Component name, Component lore) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name.color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        if (lore != null) meta.lore(List.of(lore.decoration(TextDecoration.ITALIC, false)));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack barItem(Material mat, Component name) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack infoItem(Material mat, Component name, Component lore) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (lore != null) meta.lore(List.of(lore.decoration(TextDecoration.ITALIC, false)));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack noName(Material mat) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.empty());
        stack.setItemMeta(meta);
        return stack;
    }

    private static void fillGlass(Inventory inv, int from, int to) {
        ItemStack glass = noName(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = from; i < to; i++) inv.setItem(i, glass);
    }

    private static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static UUID safeUuid(String s) {
        try { return UUID.fromString(s); } catch (Exception e) { return new UUID(0, 0); }
    }

    private static int parseInt(String s) {
        try { return Math.max(0, Integer.parseInt(s)); } catch (NumberFormatException e) { return 0; }
    }
}
