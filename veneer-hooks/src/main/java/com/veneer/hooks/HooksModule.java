package com.veneer.hooks;

import com.veneer.VeneerPlugin;
import com.veneer.core.PermissionSource;
import com.veneer.core.VeneerModule;
import org.bukkit.entity.Player;

import java.util.List;

/** Optional integrations: PlaceholderAPI ({@code %veneer_*%}) and LuckPerms (primary group). */
public class HooksModule extends VeneerModule implements PermissionSource {

    private VeneerPlaceholders placeholders;
    private LuckPermsHook luckPerms;

    public HooksModule(VeneerPlugin plugin) {
        super(plugin);
    }

    @Override public String id() { return "hooks"; }

    /** Hooks have no switch of their own: they run when the plugin they bridge to is installed. */
    @Override public boolean wanted() { return true; }

    @Override
    protected void enable() {
        if (plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholders = new VeneerPlaceholders(plugin);
            placeholders.register();
            plugin.getLogger().info("Registered the PlaceholderAPI expansion.");
        }
        if (plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                luckPerms = new LuckPermsHook();
                plugin.getLogger().info("Hooked into LuckPerms.");
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to hook into LuckPerms: " + t.getMessage());
            }
        }
    }

    @Override
    protected void disable() {
        if (placeholders != null) placeholders.unregister();
        placeholders = null;
        luckPerms = null;
    }

    /**
     * Nodes under {@code prefix} set on the player: from LuckPerms (own nodes first, then groups; no wildcards above
     * the prefix) when it is installed, else what Bukkit reports as set (a group is not known there).
     */
    @Override
    public List<SetNode> setNodes(Player player, String prefix) {
        return luckPerms != null ? luckPerms.nodes(player, prefix) : BUKKIT.setNodes(player, prefix);
    }

    /** Primary LuckPerms group of the player, or "" when LuckPerms is absent. */
    @Override
    public String primaryGroup(Player player) {
        return luckPerms == null ? "" : luckPerms.getPrimaryGroup(player);
    }
}
