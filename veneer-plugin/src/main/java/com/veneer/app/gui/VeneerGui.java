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
import org.bukkit.event.inventory.InventoryDragEvent;
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
    /** Covers every slot without a button, so no menu has a hole. */
    private static final Material FILLER = Material.LIGHT_GRAY_STAINED_GLASS_PANE;
    /** Where a list page says that it is empty. */
    private static final int EMPTY_SLOT = 22;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    // Main chest menu slot layout (4 rows = 36 slots)
    private static final int SLOT_SPOOF = 1, SLOT_HACKS = 3, SLOT_RPTRACK = 5;
    private static final int SLOT_PLAYERS = 10, SLOT_DETECTIONS = 11, SLOT_BRANDS = 12,
            SLOT_GRIM = 13, SLOT_ALERTS = 14, SLOT_EDITOR = 15, SLOT_CHANNELS = 16;
    private static final int SLOT_RELOAD = 19, SLOT_VERSION = 21, SLOT_ALERTS_TOGGLE = 23, SLOT_LANG = 25, SLOT_BROWSERS = 31;
    private static final int SLOT_CLOSE = 35;

    // Sub-page nav row (row 6, slots 45–53)
    private static final int NAV_PREV = 45, NAV_SEARCH = 46, NAV_BACK = 49, NAV_NEXT = 52, NAV_CLOSE2 = 53;

    // Player info page slots (4 rows = 36 slots)
    private static final int PI_HEAD = 4;
    private static final int PI_BRAND = 10, PI_STATUS = 11, PI_CHANNELS = 12, PI_SCANS = 13,
            PI_GRIM = 14, PI_SENSITIVITY = 15;
    private static final int PI_CHECK = 19, PI_DETECTIONS = 22, PI_ALERTS = 25;
    private static final int PI_BROWSERS = 30, PI_DELETE = 32;
    private static final int PI_BACK = 27, PI_CLOSE = 35;

    // Delete confirmation (3 rows)
    private static final int CONFIRM_YES = 11, CONFIRM_NO = 15;

    public enum Page { MAIN, PLAYERS, DETECTIONS, BRANDS, GRIM, PLAYER_INFO, CHANNELS, ALERTS, BROWSERS, CONFIRM_DELETE }

    /** What an open menu is: its page, the page number, the player or search it is about, and whether a next page exists. */
    static final class VeneerMenuHolder implements InventoryHolder {
        private final Page page;
        private final int pageNum;
        private final String query;
        private boolean hasNext;
        private Inventory inventory;

        VeneerMenuHolder(Page page, int pageNum, String query) {
            this.page = page;
            this.pageNum = pageNum;
            this.query = query;
        }

        Page page() { return page; }
        int pageNum() { return pageNum; }
        String query() { return query; }
        boolean hasNext() { return hasNext; }

        @Override public Inventory getInventory() { return inventory; }
    }

    /** A new menu, every slot covered by the filler; the pages put their items over it. */
    private Inventory menu(Page page, int pageNum, String query, int size, Component title) {
        VeneerMenuHolder holder = new VeneerMenuHolder(page, pageNum, query);
        Inventory inv = plugin.getServer().createInventory(holder, size, title);
        holder.inventory = inv;
        fillGlass(inv, 0, size);
        return inv;
    }

    /** A clicked menu item as the handlers get it, one tick after the click. */
    private record Click(Inventory inventory, ItemStack item, ClickType type) {}

    private final Veneer plugin;
    /** The channel group an item of the channels page stands for, so a click can find its players. */
    private final NamespacedKey channelsKey;
    /** The browser an item of the browsers page stands for. */
    private final NamespacedKey browserKey;
    /** The brand verdict an item of the brands page stands for: the list is re-sorted whenever a verdict changes. */
    private final NamespacedKey brandKey;
    /** The player an item of a list page stands for. The display name is not asked: fillers and markers have one too. */
    private final NamespacedKey targetKey;
    private final GrimViolationTracker grimTracker;

    /** How long the chat line after "search" is taken as the query. */
    private static final long SEARCH_WAIT_MS = 60_000;
    private record Search(Page page, long until) {}
    private final Map<UUID, Search> awaitingSearch = new ConcurrentHashMap<>();

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
        browserKey = new NamespacedKey(plugin, "browser");
        brandKey = new NamespacedKey(plugin, "brand");
        targetKey = new NamespacedKey(plugin, "target");
    }

    /** The brands page lists and changes verdicts, as {@code /spoof brands} does: the same right. */
    private boolean mayBrands(Player player) {
        return plugin.spoof().running() && player.hasPermission("veneer.spoof.admin");
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

        Inventory inv = menu(Page.PLAYER_INFO, 0, targetName, 36, Component.text(targetName).color(isOnline ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                        .decoration(TextDecoration.ITALIC, false));

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

        if (plugin.hacks().running() && admin.hasPermission("veneer.hacks.editor.admin"))
            inv.setItem(PI_BROWSERS, navItem(Material.SPYGLASS, m.get("gui-btn-browsers"), m.get("gui-btn-browsers-player-lore")));
        inv.setItem(PI_DELETE, navItem(Material.LAVA_BUCKET, m.get("gui-btn-delete-player"), m.get("gui-btn-delete-player-lore")));

        // Nav
        inv.setItem(PI_BACK,  barItem(Material.RED_STAINED_GLASS_PANE, m.get("gui-nav-back")));
        inv.setItem(PI_CLOSE, barItem(Material.BARRIER, m.get("gui-nav-close")));

        admin.openInventory(inv);
    }

    /**
     * Runs every check the sender may run on a player, immediately (no dialog). It is a shortcut for
     * {@code /hackcheck} and {@code /rptrack}, so it asks for their rights and nothing else.
     */
    public void openCheckDialog(Player admin, String targetName) {
        Messages m = plugin.messages().in(admin);
        boolean mods = plugin.hacks().running() && admin.hasPermission("veneer.hacks.check");
        boolean lang = plugin.hacks().running() && admin.hasPermission("veneer.hacks.lang");
        boolean rptrack = plugin.rptrack().running() && admin.hasPermission("veneer.rptrack");
        if (!mods && !lang && !rptrack) { admin.sendMessage(m.get("no-permission")); return; }
        Player target = plugin.getServer().getPlayerExact(targetName);
        if (target == null) { m.send(admin, "player-not-found", "player", targetName); return; }
        if (mods || lang)
            admin.performCommand("hackcheck " + target.getName() + (mods && lang ? " all" : mods ? " hack" : " lang"));
        if (rptrack) admin.performCommand("rptrack " + target.getName());
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
        Inventory inv = menu(Page.MAIN, 0, null, 36, m.get("gui-chest-title"));

        inv.setItem(SLOT_SPOOF,   moduleItem(plugin.spoof().running(),   m.get("gui-module-spoof"),   m));
        inv.setItem(SLOT_HACKS,   moduleItem(plugin.hacks().running(),   m.get("gui-module-hacks"),   m));
        inv.setItem(SLOT_RPTRACK, moduleItem(plugin.rptrack().running(), m.get("gui-module-rptrack"), m));

        inv.setItem(SLOT_PLAYERS,    navItem(Material.PLAYER_HEAD,  m.get("gui-btn-players"),    m.get("gui-btn-players-lore")));
        inv.setItem(SLOT_DETECTIONS, navItem(Material.WRITTEN_BOOK, m.get("gui-btn-detections"), m.get("gui-btn-detections-lore")));
        if (mayBrands(player))
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
        if (player.hasPermission(Alerts.PERMISSION))
            inv.setItem(SLOT_ALERTS_TOGGLE, alertsToggleItem(player, m));
        if (player.hasPermission("veneer.lang"))
            inv.setItem(SLOT_LANG, navItem(Material.BOOK, m.get("gui-btn-lang", Map.of("lang", m.language())), m.get("gui-btn-lang-lore")));
        if (plugin.hacks().running() && player.hasPermission("veneer.hacks.editor"))
            inv.setItem(SLOT_BROWSERS, navItem(Material.SPYGLASS, m.get("gui-btn-browsers"), m.get("gui-btn-browsers-lore")));
        inv.setItem(SLOT_CLOSE,   barItem(Material.BARRIER, m.get("gui-nav-close")));

        player.openInventory(inv);
    }

    private ItemStack alertsToggleItem(Player player, Messages m) {
        boolean on = plugin.alerts().isEnabled(player.getUniqueId());
        return navItem(on ? Material.LIME_DYE : Material.GRAY_DYE, m.get("gui-btn-alerts-toggle"),
                m.get(on ? "gui-btn-alerts-toggle-on" : "gui-btn-alerts-toggle-off"));
    }

    // ── Web panel browsers ────────────────────────────────────────────────────

    private void openBrowsers(Player player, String target) {
        openBrowsers(player, target, 0);
    }

    /** An account has a handful of browsers: all are read and the page is cut out of them. */
    private void openBrowsers(Player player, String target, int pageNum) {
        load(player, () -> plugin.database().tokens().listSessions(null, target), rows -> showBrowsers(player, target, pageNum, rows));
    }

    private void showBrowsers(Player player, String target, int pageNum, List<Map<String, Object>> all) {
        Messages m = plugin.messages().in(player);
        Inventory inv = menu(Page.BROWSERS, pageNum, target, 54, m.get("gui-page-browsers", Map.of("player", target)));
        boolean showIp = player.hasPermission("veneer.web.ips");
        int from = Math.min(pageNum * PAGE_SIZE, all.size());
        List<Map<String, Object>> rows = all.subList(from, Math.min(from + PAGE_SIZE, all.size()));
        int slot = 0;
        for (Map<String, Object> row : rows) {
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            String id = String.valueOf(row.get("id"));
            meta.displayName(Component.text(id).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    m.get("gui-browser-dates", Map.of(
                            "created", DATE_FMT.format(Instant.ofEpochMilli((Long) row.get("created"))),
                            "used", DATE_FMT.format(Instant.ofEpochMilli((Long) row.get("used"))))).decoration(TextDecoration.ITALIC, false),
                    m.get("gui-browser-ip", Map.of("ip", showIp ? String.valueOf(row.get("ip")) : "-")).decoration(TextDecoration.ITALIC, false),
                    m.get("gui-browser-hint").decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(browserKey, PersistentDataType.STRING, id);
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }
        if (slot == 0) inv.setItem(EMPTY_SLOT, barItem(Material.STRUCTURE_VOID, m.get("gui-list-empty")));
        buildNavRow(inv, pageNum, all.size() > from + PAGE_SIZE, false, m);
        player.openInventory(inv);
    }

    private void handleBrowsers(Player player, VeneerMenuHolder holder, int slot, ItemStack item, ClickType click) {
        if (slot < PAGE_SIZE) {
            if (item == null || !item.hasItemMeta()) return;
            String id = item.getItemMeta().getPersistentDataContainer().get(browserKey, PersistentDataType.STRING);
            if (id == null) return;
            // The commands check the rights; block asks for confirmation in chat. Shift guards against a stray ban.
            if (click == ClickType.LEFT) {
                player.closeInventory();
                player.performCommand("hackeditor unauth " + holder.query() + " " + id);
            } else if (click == ClickType.SHIFT_RIGHT) {
                player.closeInventory();
                player.performCommand("hackeditor block " + holder.query() + " " + id);
            }
            return;
        }
        switch (slot) {
            case NAV_PREV   -> { if (holder.pageNum() > 0) openBrowsers(player, holder.query(), holder.pageNum() - 1); }
            case NAV_NEXT   -> { if (holder.hasNext()) openBrowsers(player, holder.query(), holder.pageNum() + 1); }
            // Back to where the page was opened from: the player's page, or the main menu for one's own browsers
            case NAV_BACK   -> {
                if (holder.query() != null && !holder.query().equalsIgnoreCase(player.getName())) openPlayerInfo(player, holder.query());
                else open(player);
            }
            case NAV_CLOSE2 -> player.closeInventory();
        }
    }

    // ── Delete confirmation ───────────────────────────────────────────────────

    private void openDeleteConfirm(Player player, String target) {
        Messages m = plugin.messages().in(player);
        Inventory inv = menu(Page.CONFIRM_DELETE, 0, target, 27, m.get("gui-page-delete", Map.of("player", target)));
        inv.setItem(CONFIRM_YES, navItem(Material.RED_CONCRETE, m.get("gui-delete-yes"), m.get("gui-delete-yes-lore", Map.of("player", target))));
        inv.setItem(CONFIRM_NO, navItem(Material.LIME_CONCRETE, m.get("gui-delete-no"), null));
        player.openInventory(inv);
    }

    private void handleDeleteConfirm(Player player, VeneerMenuHolder holder, int slot) {
        if (slot == CONFIRM_YES) {
            player.closeInventory();
            player.performCommand("veneer db delete " + holder.query());
        } else if (slot == CONFIRM_NO) {
            openPlayerInfo(player, holder.query());
        }
    }

    /** Opens the panel when this account has a linked browser, otherwise links one. */
    private void openEditor(Player player) {
        player.closeInventory();
        String uuid = player.getUniqueId().toString();
        FoliaScheduler.runAsync(plugin, () -> {
            boolean linked = plugin.database().tokens().countSessions(uuid) > 0;
            FoliaScheduler.runAtEntity(plugin, player, () -> {
                if (player.isOnline()) player.performCommand(linked ? "hackeditor" : "hackeditor new");
            });
        });
    }

    /** Left click moves to the next language, right click follows the client's language. */
    private void switchLanguage(Player player, ClickType click) {
        if (!player.hasPermission("veneer.lang")) return;
        String code;
        if (click.isRightClick()) {
            code = Messages.AUTO;
        } else if (click.isLeftClick()) {
            List<String> codes = plugin.messages().languages();
            if (codes.isEmpty()) return;
            code = codes.get((codes.indexOf(plugin.messages().in(player).language()) + 1) % codes.size());
        } else {
            return;
        }
        player.performCommand("veneer lang " + code);
        openMainChest(player);
    }

    // ── Players page ──────────────────────────────────────────────────────────

    private void openPlayers(Player player, int pageNum, String query) {
        boolean search = query != null && !query.isBlank();
        load(player, () -> search
                        ? plugin.database().players().searchPlayers(query, PAGE_SIZE + 1, pageNum * PAGE_SIZE)
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

        Inventory inv = menu(Page.PLAYERS, pageNum, query, 54, title);

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
            skullMeta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, name);
            skull.setItemMeta(skullMeta);
            inv.setItem(slot++, skull);
        }

        if (slot == 0) inv.setItem(EMPTY_SLOT, barItem(Material.STRUCTURE_VOID, m.get("gui-list-empty")));
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

        Inventory inv = menu(Page.DETECTIONS, pageNum, playerFilter, 54, title);

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
            if (pName != null) meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, pName);
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }

        if (slot == 0) inv.setItem(EMPTY_SLOT, barItem(Material.STRUCTURE_VOID, m.get("gui-list-empty")));
        buildNavRow(inv, pageNum, mayHaveMore, false, m);
        player.openInventory(inv);
    }

    // ── Alerts page ───────────────────────────────────────────────────────────

    private void openAlerts(Player player, int pageNum, String targetPlayer) {
        // As /veneer alerts: the history belongs to those who receive the alerts
        if (!player.hasPermission(Alerts.PERMISSION)) {
            player.sendMessage(plugin.messages().in(player).get("no-permission"));
            return;
        }
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

        Inventory inv = menu(Page.ALERTS, pageNum, targetPlayer, 54, title);

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
            if (pName != null) meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, pName);
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }

        if (slot == 0) inv.setItem(EMPTY_SLOT, barItem(Material.STRUCTURE_VOID, m.get("gui-alerts-empty")));

        buildNavRow(inv, pageNum, mayHaveMore, false, m);
        player.openInventory(inv);
    }

    // ── Brands page ───────────────────────────────────────────────────────────

    private void openBrands(Player player, int pageNum) {
        Messages m = plugin.messages().in(player);
        if (!plugin.spoof().running()) { open(player); return; }
        if (!mayBrands(player)) { player.sendMessage(m.get("no-permission")); return; }
        List<BrandVerdicts.Entry> all = plugin.spoof().verdicts().all();
        int from = pageNum * PAGE_SIZE, to = Math.min(from + PAGE_SIZE, all.size());
        List<BrandVerdicts.Entry> entries = from < all.size() ? all.subList(from, to) : List.of();

        Inventory inv = menu(Page.BRANDS, pageNum, null, 54, m.get("gui-page-brands"));

        int slot = 0;
        for (BrandVerdicts.Entry e : entries)
            inv.setItem(slot++, buildBrandItem(e, m));

        if (slot == 0) inv.setItem(EMPTY_SLOT, barItem(Material.STRUCTURE_VOID, m.get("gui-list-empty")));
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
        meta.getPersistentDataContainer().set(brandKey, PersistentDataType.INTEGER, e.id());
        item.setItemMeta(meta);
        return item;
    }

    // ── GrimAC page ───────────────────────────────────────────────────────────

    private void openGrim(Player player, int pageNum) {
        Messages m = plugin.messages().in(player);
        List<GrimViolationTracker.Violation> all = grimTracker.getRecent(PAGE_SIZE * 10);
        int from = pageNum * PAGE_SIZE, to = Math.min(from + PAGE_SIZE, all.size());
        List<GrimViolationTracker.Violation> violations = from < all.size() ? all.subList(from, to) : List.of();

        Inventory inv = menu(Page.GRIM, pageNum, null, 54, m.get("gui-page-grim"));

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
            meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, v.playerName());
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }

        if (slot == 0) inv.setItem(EMPTY_SLOT, barItem(Material.STRUCTURE_VOID, m.get("gui-grim-empty")));

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

        Inventory inv = menu(Page.CHANNELS, pageNum, null, 54, m.get("gui-page-channels"));

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

        if (slot == 0) inv.setItem(EMPTY_SLOT, barItem(Material.STRUCTURE_VOID, m.get("gui-channels-empty")));

        buildNavRow(inv, pageNum, mayHaveMore, false, m);
        player.openInventory(inv);
    }

    // ── Navigation row ────────────────────────────────────────────────────────

    private void buildNavRow(Inventory inv, int pageNum, boolean hasNext, boolean hasSearch, Messages m) {
        // The click handlers ask the holder: the filler in the arrow's place must not turn the page
        if (inv.getHolder(false) instanceof VeneerMenuHolder holder) holder.hasNext = hasNext;
        if (pageNum > 0) inv.setItem(NAV_PREV, barItem(Material.ARROW, m.get("gui-nav-prev")));
        if (hasSearch)
            inv.setItem(NAV_SEARCH, barItem(Material.NAME_TAG, m.get("gui-nav-search")));
        inv.setItem(NAV_BACK, barItem(Material.RED_STAINED_GLASS_PANE, m.get("gui-nav-back")));
        if (hasNext) inv.setItem(NAV_NEXT, barItem(Material.ARROW, m.get("gui-nav-next")));
        inv.setItem(NAV_CLOSE2, barItem(Material.BARRIER, m.get("gui-nav-close")));
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    /** Dragging would drop the player's own items into the menu, where they are lost when it closes. */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof VeneerMenuHolder) event.setCancelled(true);
    }

    /** The clicks that press a button. Number keys, Q, F, a double click's third event and the rest only get cancelled. */
    private static final Set<ClickType> PRESSES = EnumSet.of(
            ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT, ClickType.MIDDLE);

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // getHolder(false): without it Paper copies the block state of every chest and furnace clicked on the server
        if (!(event.getInventory().getHolder(false) instanceof VeneerMenuHolder holder)) return;
        // Whatever the click is (shift from below, collect by double click, number key, offhand, drop, creative
        // clone): nothing moves while a menu is open
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        // Outside the window (-999) or in the player's own inventory below the menu: nothing to press there
        if (slot < 0 || slot >= event.getInventory().getSize() || !PRESSES.contains(event.getClick())) return;
        // The right may have been taken away while the menu was open
        if (!player.hasPermission("veneer.admin")) {
            FoliaScheduler.runAtEntity(plugin, player, player::closeInventory);
            return;
        }
        Click click = new Click(event.getInventory(), event.getCurrentItem(), event.getClick());
        // A tick later: an inventory must not be closed or opened while its click is still being handled
        FoliaScheduler.runAtEntity(plugin, player, () -> {
            // Closed or replaced meanwhile (a second click in the same tick): that menu is gone
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != click.inventory()) return;
            press(player, holder, slot, click);
        });
    }

    private void press(Player player, VeneerMenuHolder holder, int slot, Click click) {
        switch (holder.page()) {
            case MAIN        -> handleMain(player, slot, click);
            case PLAYERS     -> handlePlayers(player, holder, slot, click.item());
            case DETECTIONS  -> handleDetections(player, holder, slot, click.item());
            case BRANDS      -> handleBrands(player, holder, slot, click);
            case GRIM        -> handleGrim(player, holder, slot, click.item());
            case PLAYER_INFO -> handlePlayerInfo(player, holder, slot);
            case CHANNELS    -> handleChannels(player, holder, slot, click.item(), click.type());
            case ALERTS      -> handleAlerts(player, holder, slot, click.item());
            case BROWSERS    -> handleBrowsers(player, holder, slot, click.item(), click.type());
            case CONFIRM_DELETE -> handleDeleteConfirm(player, holder, slot);
        }
    }

    private void handleMain(Player player, int slot, Click click) {
        switch (slot) {
            case SLOT_PLAYERS    -> { openPlayers(player, 0, null); }
            case SLOT_DETECTIONS -> { openDetections(player, 0, null); }
            case SLOT_BRANDS     -> { if (mayBrands(player)) { openBrands(player, 0); } }
            case SLOT_GRIM       -> { if (grimTracker != null) { openGrim(player, 0); } }
            case SLOT_ALERTS     -> { if (player.hasPermission(Alerts.PERMISSION)) { openAlerts(player, 0, null); } }
            case SLOT_CHANNELS   -> { openChannels(player, 0); }
            case SLOT_EDITOR     -> { if (plugin.hacks().running() && player.hasPermission("veneer.hacks.editor")) openEditor(player); }
            case SLOT_ALERTS_TOGGLE -> {
                if (player.hasPermission(Alerts.PERMISSION)) {
                    player.performCommand("veneer alerts toggle");
                    click.inventory().setItem(slot, alertsToggleItem(player, plugin.messages().in(player)));
                }
            }
            case SLOT_LANG       -> switchLanguage(player, click.type());
            case SLOT_BROWSERS   -> { if (plugin.hacks().running() && player.hasPermission("veneer.hacks.editor")) { openBrowsers(player, player.getName()); } }
            case SLOT_RELOAD     -> { player.closeInventory(); player.performCommand("veneer reload"); }
            case SLOT_VERSION    -> { player.closeInventory(); player.performCommand("veneer version"); }
            case SLOT_CLOSE      -> player.closeInventory();
        }
    }

    private void handlePlayers(Player player, VeneerMenuHolder holder, int slot, ItemStack item) {
        if (slot < PAGE_SIZE) {
            String name = targetOf(item);
            if (name != null) openPlayerInfo(player, name);
            return;
        }
        switch (slot) {
            case NAV_PREV   -> { if (holder.pageNum() > 0) { openPlayers(player, holder.pageNum() - 1, holder.query()); } }
            case NAV_SEARCH -> startSearch(player, Page.PLAYERS);
            case NAV_BACK   -> { open(player); }
            case NAV_NEXT   -> { if (holder.hasNext()) openPlayers(player, holder.pageNum() + 1, holder.query()); }
            case NAV_CLOSE2 -> player.closeInventory();
        }
    }

    private void handleDetections(Player player, VeneerMenuHolder holder, int slot, ItemStack item) {
        if (slot < PAGE_SIZE) {
            String name = targetOf(item);
            if (name != null) openPlayerInfo(player, name);
            return;
        }
        switch (slot) {
            case NAV_PREV   -> { if (holder.pageNum() > 0) { openDetections(player, holder.pageNum() - 1, holder.query()); } }
            case NAV_BACK   -> { open(player); }
            case NAV_NEXT   -> { if (holder.hasNext()) openDetections(player, holder.pageNum() + 1, holder.query()); }
            case NAV_CLOSE2 -> player.closeInventory();
        }
    }

    private void handleBrands(Player player, VeneerMenuHolder holder, int slot, Click click) {
        if (slot < PAGE_SIZE) {
            ItemStack item = click.item();
            if (item == null || !item.hasItemMeta() || !plugin.spoof().running()) return;
            // By the id stored on the item, not by position: a verdict moves its brand to the end of the list,
            // so after one click the positions no longer match what the page shows
            Integer id = item.getItemMeta().getPersistentDataContainer().get(brandKey, PersistentDataType.INTEGER);
            BrandVerdicts.Entry e = id == null ? null : plugin.spoof().verdicts().get(id);
            if (e == null) return;
            // Same right as /spoof brands allow|block|forget
            if (!mayBrands(player)) {
                player.sendMessage(plugin.messages().in(player).get("no-permission"));
                return;
            }
            ClickType ct = click.type();
            BrandVerdicts.Verdict next;
            // Shift first: a shift-click also counts as a left or right click. Middle click only reaches the server in creative.
            if (ct.isShiftClick() || ct == ClickType.MIDDLE) next = null;
            else if (ct.isLeftClick()) next = BrandVerdicts.Verdict.ALLOW;
            else if (ct.isRightClick()) next = BrandVerdicts.Verdict.BLOCK;
            else return; // drop, number keys and the like must not reset a verdict
            // As /spoof brands allow|block|forget: logged, and applied to the players online with that brand
            plugin.spoof().decideBrand(e.id(), next, player.getName());
            Messages m = plugin.messages().in(player);
            click.inventory().setItem(slot, buildBrandItem(e, m));
            return;
        }
        switch (slot) {
            case NAV_PREV   -> { if (holder.pageNum() > 0) { openBrands(player, holder.pageNum() - 1); } }
            case NAV_BACK   -> { open(player); }
            case NAV_NEXT   -> { if (holder.hasNext()) openBrands(player, holder.pageNum() + 1); }
            case NAV_CLOSE2 -> player.closeInventory();
        }
    }

    private void handleGrim(Player player, VeneerMenuHolder holder, int slot, ItemStack item) {
        if (slot < PAGE_SIZE) {
            String name = targetOf(item);
            if (name != null) openPlayerInfo(player, name);
            return;
        }
        switch (slot) {
            case NAV_PREV   -> { if (holder.pageNum() > 0) { openGrim(player, holder.pageNum() - 1); } }
            case NAV_BACK   -> { open(player); }
            case NAV_NEXT   -> { if (holder.hasNext()) openGrim(player, holder.pageNum() + 1); }
            case NAV_CLOSE2 -> player.closeInventory();
        }
    }

    private void handlePlayerInfo(Player player, VeneerMenuHolder holder, int slot) {
        String targetName = holder.query();
        switch (slot) {
            case PI_CHECK      -> { player.closeInventory(); if (targetName != null) openCheckDialog(player, targetName); }
            case PI_DETECTIONS -> { if (targetName != null) openDetections(player, 0, targetName); }
            case PI_ALERTS     -> { if (targetName != null) openAlerts(player, 0, targetName); }
            case PI_BROWSERS   -> { if (targetName != null && plugin.hacks().running() && player.hasPermission("veneer.hacks.editor.admin")) { openBrowsers(player, targetName); } }
            case PI_DELETE     -> { if (targetName != null) { openDeleteConfirm(player, targetName); } }
            case PI_BACK       -> { open(player); }
            case PI_CLOSE      -> player.closeInventory();
        }
    }

    private void handleAlerts(Player player, VeneerMenuHolder holder, int slot, ItemStack item) {
        if (slot < PAGE_SIZE) {
            String name = targetOf(item);
            if (name != null) openPlayerInfo(player, name);
            return;
        }
        switch (slot) {
            case NAV_PREV   -> { if (holder.pageNum() > 0) { openAlerts(player, holder.pageNum() - 1, holder.query()); } }
            case NAV_BACK   -> { open(player); }
            case NAV_NEXT   -> { if (holder.hasNext()) openAlerts(player, holder.pageNum() + 1, holder.query()); }
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
            case NAV_PREV   -> { if (holder.pageNum() > 0) { openChannels(player, holder.pageNum() - 1); } }
            case NAV_BACK   -> { open(player); }
            case NAV_NEXT   -> { if (holder.hasNext()) openChannels(player, holder.pageNum() + 1); }
            case NAV_CLOSE2 -> player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Search search = awaitingSearch.remove(player.getUniqueId());
        // Asked long ago: this line is ordinary chat again
        if (search == null || System.currentTimeMillis() > search.until()) return;
        event.setCancelled(true);
        String query = plain(event.message()).strip();
        if (query.isEmpty() || ".".equals(query)) return;
        FoliaScheduler.runAtEntity(plugin, player, () -> {
            if (search.page() == Page.PLAYERS && player.hasPermission("veneer.admin")) openPlayers(player, 0, query);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void startSearch(Player player, Page page) {
        player.closeInventory();
        long now = System.currentTimeMillis();
        // Entries of players who never answered (or left) go here, so the map cannot grow
        awaitingSearch.values().removeIf(s -> now > s.until());
        awaitingSearch.put(player.getUniqueId(), new Search(page, now + SEARCH_WAIT_MS));
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
        ItemStack glass = noName(FILLER);
        for (int i = from; i < to; i++) inv.setItem(i, glass);
    }

    private String targetOf(ItemStack item) {
        return item == null || !item.hasItemMeta() ? null
                : item.getItemMeta().getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
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
