package com.veneer.app;

import com.veneer.VeneerPlugin;
import com.veneer.app.gui.VeneerGui;
import com.veneer.core.VeneerModule;
import com.veneer.hacks.HacksModule;
import com.veneer.hooks.HooksModule;
import com.veneer.rptrack.RPTrackManager;
import com.veneer.spoof.SpoofModule;
import org.bukkit.command.PluginCommand;

import java.util.List;

/**
 * The Bukkit plugin: puts the feature modules of the other Maven modules together and adds what spans them, the
 * {@code /veneer} command and the chest GUI. Everything else lives in {@link VeneerPlugin} (veneer-core).
 */
public final class Veneer extends VeneerPlugin {

    private SpoofModule spoof;
    private HacksModule hacks;
    private RPTrackManager rptrack;
    private HooksModule hooks;
    private VeneerGui gui;

    @Override
    protected List<VeneerModule> createModules() {
        spoof = new SpoofModule(this);
        hacks = new HacksModule(this);
        rptrack = new RPTrackManager(this);
        hooks = new HooksModule(this);
        return List.of(spoof, hacks, rptrack, hooks);
    }

    @Override
    protected void createInterface() {
        gui = new VeneerGui(this);
        getServer().getPluginManager().registerEvents(gui, this);
        PluginCommand veneer = getCommand("veneer");
        if (veneer != null) {
            VeneerCommand executor = new VeneerCommand(this);
            veneer.setExecutor(executor);
            veneer.setTabCompleter(executor);
        }
        PluginCommand accountlink = getCommand("accountlink");
        if (accountlink != null) {
            AccountLinkCommand executor = new AccountLinkCommand(this);
            accountlink.setExecutor(executor);
            accountlink.setTabCompleter(executor);
        }
    }

    public SpoofModule spoof() { return spoof; }
    public HacksModule hacks() { return hacks; }
    public RPTrackManager rptrack() { return rptrack; }
    public HooksModule hooks() { return hooks; }
    public VeneerGui gui() { return gui; }
}
