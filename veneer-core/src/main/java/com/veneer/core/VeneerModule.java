package com.veneer.core;

import com.veneer.VeneerPlugin;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.HandlerList;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One feature area of Veneer (spoof detection, mod scans, RPTrack, hooks). A module owns its
 * listeners and commands: everything it registers through {@link #listen} and {@link #command}
 * is removed again by {@link #shutdown()}.
 */
public abstract class VeneerModule {

    protected final VeneerPlugin plugin;
    private final List<Listener> listeners = new ArrayList<>();
    private boolean running;

    protected VeneerModule(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    /** Short id, also the name of the config section that holds {@code enabled}. */
    public abstract String id();

    /** Whether the config asks for this module to run. */
    public boolean wanted() {
        return plugin.config().section(id()).getBoolean("enabled", true);
    }

    public final boolean running() {
        return running;
    }

    /** Starts the module. Called once, after the core services exist. */
    protected abstract void enable();

    /** Releases resources that are not listeners/commands (sockets, tasks, API hooks). */
    protected void disable() {}

    /** Re-reads the module's config section. Settings that need a restart should say so in the log. */
    protected void reload() {}

    /** A player joined. Called by {@link PlayerEvents} for running modules only. */
    public void playerJoin(Player player) {}

    /** A player left. Called by {@link PlayerEvents} for running modules only. */
    public void playerQuit(UUID uuid) {}

    public final void startup() {
        try {
            enable();
        } catch (Throwable t) {
            // Undo what enable() got to: shutdown() skips a module that never ran, so its listeners would stay
            for (Listener l : listeners) HandlerList.unregisterAll(l);
            listeners.clear();
            try { disable(); } catch (Throwable ignored) {}
            throw t;
        }
        running = true;
    }

    public final void shutdown() {
        if (!running) return;
        running = false;
        for (Listener l : listeners) HandlerList.unregisterAll(l);
        listeners.clear();
        disable();
    }

    public final void refresh() {
        if (running) reload();
    }

    /** Registers a Bukkit listener that is unregistered on shutdown. */
    protected final <T extends Listener> T listen(T listener) {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
        return listener;
    }

    /** Binds a plugin.yml command to this module. */
    protected final void command(String name, CommandExecutor executor) {
        PluginCommand cmd = plugin.getCommand(name);
        if (cmd == null) {
            plugin.getLogger().warning("Command '" + name + "' is missing from plugin.yml");
            return;
        }
        cmd.setExecutor(executor);
        if (executor instanceof TabCompleter tc) cmd.setTabCompleter(tc);
    }

    /** plugin.yml commands that only work while this module runs. */
    public List<String> commandNames() {
        return List.of();
    }
}
