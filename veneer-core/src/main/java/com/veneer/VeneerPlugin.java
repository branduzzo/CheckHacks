package com.veneer;

import com.veneer.core.Alerts;
import com.veneer.core.Bedrock;
import com.veneer.core.Messages;
import com.veneer.core.PacketEventsHook;
import com.veneer.core.PermissionSource;
import com.veneer.core.PlayerEvents;
import com.veneer.core.PlayerProfile;
import com.veneer.core.UpdateChecker;
import com.veneer.core.VeneerConfig;
import com.veneer.core.VeneerModule;
import com.veneer.core.Webhook;
import com.veneer.core.db.Storage;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Veneer: client spoof detection (AntiSpoof by GigaZelensky) and mod detection (CheckHacks by Branduzzo)
 * merged into one plugin, plus RPTrack and optional hooks.
 *
 * <p>The shared services (config, messages, alerts, Bedrock detection, webhook, storage, player profiles, update
 * checker) live here, in veneer-core. Each feature area is a {@link VeneerModule} in its own Maven module; the
 * plugin class in veneer-plugin creates them ({@link #createModules()}) and adds the user interface
 * ({@link #createInterface()}). A module that needs another one looks it up with {@link #module(Class)}.
 */
public abstract class VeneerPlugin extends JavaPlugin {

    private VeneerConfig config;
    private Messages messages;
    private Alerts alerts;
    private Bedrock bedrock;
    private Webhook webhook;
    private Storage database;
    private UpdateChecker updates;
    private PermissionSource permissions = PermissionSource.BUKKIT;

    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();

    private List<VeneerModule> modules = List.of();
    private final Map<Class<?>, VeneerModule> byType = new LinkedHashMap<>();

    /** The feature modules, in start order. Called once, after the core services exist. */
    protected abstract List<VeneerModule> createModules();

    /** Commands and menus that span modules (/veneer, the GUI). Called after {@link #createModules()}. */
    protected abstract void createInterface();

    @Override
    public void onEnable() {
        long begin = System.currentTimeMillis();
        // The lite jar has no PacketEvents of its own, and the spoof module and the mod scan's version check use it
        if (!PacketEventsHook.available(this)) {
            getLogger().severe("This is the lite build: it needs the PacketEvents plugin "
                    + "(https://modrinth.com/plugin/packetevents), or use the full veneer jar instead. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        config = new VeneerConfig(this);
        messages = new Messages(this);
        alerts = new Alerts(this);
        bedrock = new Bedrock(this);
        webhook = new Webhook(this);
        database = new Storage(new File(getDataFolder(), "data.db"), getLogger());
        messages.loadChoices();
        updates = new UpdateChecker(this);

        modules = List.copyOf(createModules());
        for (VeneerModule module : modules) {
            byType.put(module.getClass(), module);
            if (module instanceof PermissionSource source) permissions = source;
        }
        getServer().getPluginManager().registerEvents(new PlayerEvents(this), this);
        createInterface();

        StringBuilder started = new StringBuilder();
        for (VeneerModule module : modules) {
            if (!module.wanted()) {
                disableCommands(module);
                continue;
            }
            try {
                module.startup();
                started.append(started.isEmpty() ? "" : ", ").append(module.id());
            } catch (Throwable t) {
                getLogger().log(java.util.logging.Level.SEVERE, "Module '" + module.id() + "' failed to start", t);
                module.shutdown();
                disableCommands(module);
            }
        }

        long loadedMs = System.currentTimeMillis() - begin;
        if (config.updateCheckEnabled()) updates.start(() -> printBanner(loadedMs, started.toString()));
        else printBanner(loadedMs, started.toString());
    }

    private void printBanner(long loadedMs, String started) {
        UpdateChecker.Status status = updates.status();
        String version = version();
        if (status != null && status.latest() != null) version += " \\ " + status.latest();
        String bar = "+-------------------------------------------------------+";
        String[] lines = {
                bar,
                "|",
                "|  /\\   /\\___ _ __   ___  ___ _ __",
                "|  \\ \\ / / _ \\ '_ \\ / _ \\/ _ \\ '__|",
                "|   \\ V /  __/ | | |  __/  __/ |",
                "|    \\_/ \\___|_| |_|\\___|\\___|_|",
                "|",
                "|  " + messages.plain("banner-loaded", "ms", loadedMs),
                "|  " + messages.plain("banner-modules", "modules", started.isEmpty() ? messages.plain("banner-no-modules") : started),
                "|  " + messages.plain("banner-version", "version", version),
                "|  " + messages.plain("banner-author"),
                bar};
        for (String line : lines) getLogger().info(line);
    }

    @Override
    public void onDisable() {
        if (config == null) return;   // disabled at startup (lite build without PacketEvents)
        // Each step on its own: an exception in one module must not leave the others running or the database open
        for (int i = modules.size() - 1; i >= 0; i--) {
            try {
                modules.get(i).shutdown();
            } catch (Throwable t) {
                getLogger().warning("Module '" + modules.get(i).id() + "' failed to shut down: " + t);
            }
        }
        profiles.clear();
        try {
            if (database != null) database.close();
        } catch (Throwable t) {
            getLogger().warning("Database failed to close: " + t);
        }
        getLogger().info("Veneer disabled.");
    }

    /** The version from plugin.yml. Read through the Bukkit description so Spigot, which has no plugin meta, still works. */
    @SuppressWarnings("deprecation")
    public String version() {
        return getDescription().getVersion();
    }

    /** Re-reads config.yml and the language files, then lets every running module refresh itself. */
    public void reloadAll() {
        config.reload();
        messages.load();
        for (VeneerModule module : modules) {
            if (module.running()) {
                module.refresh();
            } else if (module.wanted()) {
                getLogger().info("Module '" + module.id() + "' is enabled in config.yml but not running: restart the server to start it.");
            }
        }
        getLogger().info("Configuration reloaded.");
    }

    private void disableCommands(VeneerModule module) {
        CommandExecutor off = (sender, cmd, label, args) -> {
            messages.send(sender, "module-not-running", "module", module.id());
            return true;
        };
        for (String name : module.commandNames()) {
            PluginCommand command = getCommand(name);
            if (command != null) command.setExecutor(off);
        }
    }

    // ---------------------------------------------------------------- services

    public VeneerConfig config() { return config; }
    public Messages messages() { return messages; }
    public Alerts alerts() { return alerts; }
    public Bedrock bedrock() { return bedrock; }
    public Webhook webhook() { return webhook; }
    public Storage database() { return database; }
    public UpdateChecker updates() { return updates; }
    public PermissionSource permissions() { return permissions; }
    public List<VeneerModule> modules() { return modules; }

    /**
     * The module of this type (whether it runs or not: check {@link VeneerModule#running()}).
     *
     * @throws IllegalStateException when the plugin has no such module
     */
    public <T extends VeneerModule> T module(Class<T> type) {
        VeneerModule module = byType.get(type);
        if (module == null) {
            for (VeneerModule m : modules) if (type.isInstance(m)) { module = m; break; }
            if (module == null) throw new IllegalStateException("No module " + type.getSimpleName());
        }
        return type.cast(module);
    }

    // ---------------------------------------------------------------- profiles

    /** The live profiles by player. Prefer {@link #profile(Player)} and {@link #profileOf(UUID)}. */
    public Map<UUID, PlayerProfile> profiles() { return profiles; }

    /** The player's profile, created on first use. */
    public PlayerProfile profile(Player player) {
        return profiles.computeIfAbsent(player.getUniqueId(), id -> new PlayerProfile());
    }

    /** The player's profile, or null when nothing has been recorded for them. */
    public PlayerProfile profileOf(UUID uuid) {
        return profiles.get(uuid);
    }

    /**
     * The client brand: what the brand channel delivered, else what Paper recorded. Since 1.20.2 the client
     * sends it during the configuration phase, before Bukkit's plugin channel listeners can see it.
     */
    public String brandOf(Player player) {
        if (player == null) return null;
        PlayerProfile profile = profiles.get(player.getUniqueId());
        if (profile != null && profile.getBrand() != null) return profile.getBrand();
        String brand = player.getClientBrandName();
        if (brand != null) profile(player).setBrand(brand);
        return brand;
    }

    public void dropProfile(UUID uuid) {
        profiles.remove(uuid);
    }

    /**
     * Whether the player skips the automatic {@code module} check ("spoof", "hacks" or "rptrack"); {@code join}
     * for the check on joining, which {@code veneer.bypass.<module>.join} skips too. The most specific of
     * {@code veneer.bypass.<module>.join}, {@code veneer.bypass.<module>} and {@code veneer.bypass} that is set
     * decides, so a false below a true wins (veneer.bypass true and veneer.bypass.hacks false: mods are still
     * scanned). Nodes set on the player come before group ones (group veneer.bypass.hacks false and the player's
     * own veneer.bypass: no scan). Unset everywhere means no bypass; {@code veneer.*} and {@code *} never grant one.
     * Resolved here rather than through plugin.yml children: a child granted by its parent hides a false set on it.
     */
    public boolean bypasses(Player player, String module, boolean join) {
        return bypass(player, module, join).value();
    }

    /** How a bypass was decided: its value and the node (and group, null for the player's own) that set it; node null = none set. */
    public record Bypass(boolean value, String node, String group) {}

    /** {@link #bypasses} with the node that decided it. */
    public Bypass bypass(Player player, String module, boolean join) {
        String base = "veneer.bypass." + module;
        List<String> chain = join ? List.of(base + ".join", base, "veneer.bypass") : List.of(base, "veneer.bypass");
        List<PermissionSource.SetNode> set = permissions.setNodes(player, "veneer.bypass");
        // The player's own nodes beat group ones, as in LuckPerms; within one of them the most specific node wins.
        for (boolean own : new boolean[]{true, false})
            for (String node : chain)
                for (String key : List.of(node, node + ".*"))
                    for (PermissionSource.SetNode n : set)
                        if (n.node().equals(key) && (n.group() == null) == own) return new Bypass(n.value(), key, n.group());
        return new Bypass(false, null, null);
    }

    // ----------------------------------------------------------------- logging

    /** Logs when {@code general.debug} is on. */
    public void debug(String message) {
        if (config != null && config.debug()) getLogger().info("[Debug] " + message);
    }
}
