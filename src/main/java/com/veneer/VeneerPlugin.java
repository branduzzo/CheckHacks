package com.veneer;

import com.veneer.core.Alerts;
import com.veneer.core.Bedrock;
import com.veneer.core.DatabaseManager;
import com.veneer.core.Messages;
import com.veneer.core.PacketEventsHook;
import com.veneer.core.PlayerEvents;
import com.veneer.core.PlayerProfile;
import com.veneer.core.UpdateChecker;
import com.veneer.core.VeneerCommand;
import com.veneer.core.VeneerConfig;
import com.veneer.core.VeneerModule;
import com.veneer.core.Webhook;
import com.veneer.core.gui.VeneerGui;
import com.veneer.hacks.HacksModule;
import com.veneer.hooks.HooksModule;
import com.veneer.rptrack.RPTrackManager;
import com.veneer.spoof.SpoofModule;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Veneer: client spoof detection (AntiSpoof by GigaZelensky) and mod detection (CheckHacks by Branduzzo)
 * merged into one plugin, plus RPTrack and optional hooks.
 *
 * <p>This class only wires things together. The shared services (config, messages, alerts, Bedrock detection,
 * webhook, database, player profiles, update checker) live here; each feature area is a
 * {@link VeneerModule} that is started if its {@code enabled} setting is on.
 */
public class VeneerPlugin extends JavaPlugin {

    private VeneerConfig config;
    private Messages messages;
    private Alerts alerts;
    private Bedrock bedrock;
    private Webhook webhook;
    private DatabaseManager database;
    private UpdateChecker updates;

    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();

    private SpoofModule spoof;
    private HacksModule hacks;
    private RPTrackManager rptrack;
    private HooksModule hooks;
    private List<VeneerModule> modules = List.of();
    private VeneerGui gui;

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
        database = new DatabaseManager(this);
        messages.loadChoices();
        updates = new UpdateChecker(this);

        spoof = new SpoofModule(this);
        hacks = new HacksModule(this);
        rptrack = new RPTrackManager(this);
        hooks = new HooksModule(this);
        modules = List.of(spoof, hacks, rptrack, hooks);
        getServer().getPluginManager().registerEvents(new PlayerEvents(this), this);

        gui = new VeneerGui(this);
        getServer().getPluginManager().registerEvents(gui, this);

        PluginCommand veneer = getCommand("veneer");
        if (veneer != null) {
            VeneerCommand executor = new VeneerCommand(this);
            veneer.setExecutor(executor);
            veneer.setTabCompleter(executor);
        }

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
                getLogger().severe("Module '" + module.id() + "' failed to start: " + t);
                t.printStackTrace();
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
        if (modules == null) return;   // disabled at startup (lite build without PacketEvents)
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

    /** Re-reads the config files and the language files, then lets every running module refresh itself. */
    public void reloadAll() {
        config.reload();
        messages.load();
        for (VeneerModule module : modules) {
            if (module.running()) {
                module.refresh();
            } else if (module.wanted()) {
                getLogger().info("Module '" + module.id() + "' is enabled in its config file but not running: restart the server to start it.");
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
    public DatabaseManager database() { return database; }
    public UpdateChecker updates() { return updates; }

    public SpoofModule spoof() { return spoof; }
    public HacksModule hacks() { return hacks; }
    public RPTrackManager rptrack() { return rptrack; }
    public HooksModule hooks() { return hooks; }
    public List<VeneerModule> modules() { return modules; }
    public VeneerGui gui() { return gui; }

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
     * for the check on joining, which {@code veneer.bypass.<module>.join} skips too. A denial wins over any grant:
     * one explicit false anywhere on {@code veneer.bypass.<module>.join}, {@code veneer.bypass.<module>} or
     * {@code veneer.bypass} - on the player or in any of their groups - skips nothing, even with a true set above
     * or below it (veneer.bypass true and veneer.bypass.hacks false: mods are still scanned). Among grants the
     * most specific node set on the player decides, then the ones inherited from groups. Unset everywhere means no
     * bypass; {@code veneer.*} and {@code *} never grant one. Resolved here rather than through plugin.yml
     * children: a child granted by its parent hides a false set on it.
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
        List<HooksModule.SetNode> set = hooks.setNodes(player, "veneer.bypass");
        // Any explicit false decides at once; the first grant found stays only if no denial is set anywhere.
        HooksModule.SetNode grant = null;
        // Own nodes are looked at before group ones; within them the most specific node wins.
        for (boolean own : new boolean[]{true, false})
            for (String node : chain)
                for (String key : List.of(node, node + ".*"))
                    for (HooksModule.SetNode n : set)
                        if (n.node().equals(key) && (n.group() == null) == own) {
                            if (!n.value()) return new Bypass(false, key, n.group());
                            if (grant == null) grant = n;
                        }
        return grant == null ? new Bypass(false, null, null) : new Bypass(true, grant.node(), grant.group());
    }

    // ----------------------------------------------------------------- logging

    /** Logs when {@code general.debug} is on. */
    public void debug(String message) {
        if (config != null && config.debug()) getLogger().info("[Debug] " + message);
    }
}
