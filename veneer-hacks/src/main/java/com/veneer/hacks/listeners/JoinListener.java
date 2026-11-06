package com.veneer.hacks.listeners;

import com.veneer.hacks.HacksModule;
import com.veneer.VeneerPlugin;
import com.veneer.hacks.HackDefinition;
import com.veneer.core.FoliaScheduler;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class JoinListener implements org.bukkit.event.Listener {

    private final VeneerPlugin plugin;
    private final Set<UUID> alreadyHackChecked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alreadyLangChecked = ConcurrentHashMap.newKeySet();
    /** Join scans not started yet: whichever comes first of the pack event and the fallback timer takes the entry. */
    private final Set<UUID> pendingScan = ConcurrentHashMap.newKeySet();

    public JoinListener(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    private void startJoinScan(Player player) {
        if (!pendingScan.remove(player.getUniqueId()) || !player.isOnline()) return;
        java.util.List<HackDefinition> hacks = plugin.module(HacksModule.class).config().getJoinCheckHacks();
        if (hacks.isEmpty()) return;
        plugin.alerts().send(m -> m.get("join-check", Map.of("player", player.getName())));
        plugin.module(HacksModule.class).checks().startCheck(player, null, hacks, true, "Auto-join check");
    }

    /** BETA: scans once the pack is downloaded, so the sign opens behind the client's resource reload screen. */
    @org.bukkit.event.EventHandler
    public void onResourcePack(org.bukkit.event.player.PlayerResourcePackStatusEvent event) {
        if (event.getStatus() != org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DOWNLOADED) return;
        if (!plugin.module(HacksModule.class).config().isScanDuringResourcePack()) return;
        Player player = event.getPlayer();
        FoliaScheduler.runAtEntity(plugin, player, () -> startJoinScan(player));
    }

    public void forget(UUID uuid) { pendingScan.remove(uuid); }

    public void onJoin(Player player) {
        UUID uuid = player.getUniqueId();
        if (plugin.bypasses(player, "hacks", true)) return;

        if (plugin.module(HacksModule.class).config().isJoinCheckEnabled()) {
            if (!plugin.module(HacksModule.class).config().isOnlyFirstJoin() || alreadyHackChecked.add(uuid)) {
                pendingScan.add(uuid);
                // With the beta option the pack gets time to arrive; a player without one is scanned afterwards
                boolean duringPack = plugin.module(HacksModule.class).config().isScanDuringResourcePack();
                FoliaScheduler.runAtEntityLater(plugin, player, () -> startJoinScan(player), duringPack ? 400L : 60L);
            }
        }

        if (plugin.module(HacksModule.class).config().isLangJoinCheckEnabled()) {
            boolean isFirst = alreadyLangChecked.add(uuid);
            if (!plugin.module(HacksModule.class).config().isLangOnlyFirstJoin() || isFirst) {
                FoliaScheduler.runAtEntityLater(plugin, player, () -> {
                    if (!player.isOnline()) return;
                    Map<String, String> langs = plugin.module(HacksModule.class).config().getLanguages();
                    if (langs.isEmpty()) return;
                    plugin.alerts().send(m -> m.get("lang-join-check", Map.of("player", player.getName())));
                    plugin.module(HacksModule.class).langChecks().startCheck(player, null, langs);
                }, 80L);
            }
        }
    }
}
