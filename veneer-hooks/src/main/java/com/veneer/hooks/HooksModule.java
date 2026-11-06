package com.veneer.hooks;

import com.veneer.VeneerPlugin;
import com.veneer.core.FoliaScheduler;
import com.veneer.core.PermissionSource;
import com.veneer.core.VeneerModule;
import org.bukkit.entity.Player;

import java.util.List;

/** Optional integrations: PlaceholderAPI ({@code %veneer_*%}), LuckPerms (primary group) and AuthMe (alt graph). */
public class HooksModule extends VeneerModule implements PermissionSource {

    private static final long AUTHME_FIRST_SYNC_TICKS = 200, AUTHME_SYNC_TICKS = 6 * 60 * 60 * 20;

    private VeneerPlaceholders placeholders;
    private LuckPermsHook luckPerms;
    private com.veneer.core.WrappedTask authMeSync;

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
        startAuthMe();
    }

    private void startAuthMe() {
        var links = plugin.config().section("hacks.web-editor.links");
        if (!plugin.getServer().getPluginManager().isPluginEnabled("AuthMe") || links == null || !links.getBoolean("authme", true)) {
            // Turned off or removed: the copy must not keep linking accounts
            FoliaScheduler.runAsync(plugin, () -> plugin.database().players().replaceAuthMeAccounts(List.of()));
            return;
        }
        try {
            AuthMeHook hook = new AuthMeHook(plugin);
            int retention = links.getInt("retention-days", 90);
            authMeSync = FoliaScheduler.runGlobalTimer(plugin, () -> FoliaScheduler.runAsync(plugin, () -> {
                try {
                    plugin.getLogger().info("AuthMe: " + hook.sync(retention) + " accounts copied for the alt graph.");
                } catch (Exception | LinkageError e) {
                    plugin.getLogger().warning("AuthMe: could not read its accounts: " + e);
                }
            }), AUTHME_FIRST_SYNC_TICKS, AUTHME_SYNC_TICKS);
            plugin.getLogger().info("Hooked into AuthMe.");
        } catch (Exception | LinkageError e) {
            plugin.getLogger().warning("Failed to hook into AuthMe: " + e);
        }
    }

    @Override
    protected void disable() {
        if (authMeSync != null) authMeSync.cancel();
        authMeSync = null;
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

    @Override
    public java.util.concurrent.CompletableFuture<java.util.Set<String>> offlinePermissions(java.util.UUID uuid, java.util.Collection<String> nodes) {
        return luckPerms == null ? null : luckPerms.offlinePermissions(uuid, nodes);
    }

    /** Primary LuckPerms group of the player, or "" when LuckPerms is absent. */
    @Override
    public String primaryGroup(Player player) {
        return luckPerms == null ? "" : luckPerms.getPrimaryGroup(player);
    }
}
