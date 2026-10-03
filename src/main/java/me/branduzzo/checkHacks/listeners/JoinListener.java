package me.branduzzo.checkHacks.listeners;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.HackDefinition;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class JoinListener implements Listener {

    private final CheckHacksPlugin plugin;
    private final Set<UUID> alreadyHackChecked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alreadyLangChecked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alreadyClientChecked = ConcurrentHashMap.newKeySet();

    public JoinListener(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (plugin.getUpdateManager() != null && plugin.getUpdateManager().isUpdateAvailable() && player.hasPermission("checkhacks.update")) {
            plugin.getUpdateManager().notifyIfAvailable(player);
        }

        if (plugin.getConfigManager().isJoinCheckEnabled()) {
            if (!plugin.getConfigManager().isOnlyFirstJoin() || alreadyHackChecked.add(uuid)) {
                FoliaScheduler.runAtEntityLater(plugin, player, () -> {
                    if (!player.isOnline()) return;
                    java.util.List<HackDefinition> hacks = plugin.getConfigManager().getJoinCheckHacks();
                    if (hacks.isEmpty()) return;
                    plugin.getMessageManager().broadcastAlerts(
                            plugin.getMessageManager().get("join-check", Map.of("player", player.getName())));
                    plugin.getCheckManager().startCheck(player, null, hacks, true, "Auto-join check");
                }, 60L);
            }
        }

        if (plugin.getConfigManager().isLangJoinCheckEnabled()) {
            boolean isFirst = alreadyLangChecked.add(uuid);
            if (!plugin.getConfigManager().isLangOnlyFirstJoin() || isFirst) {
                FoliaScheduler.runAtEntityLater(plugin, player, () -> {
                    if (!player.isOnline()) return;
                    Map<String, String> langs = plugin.getConfigManager().getLanguages();
                    if (langs.isEmpty()) return;
                    plugin.getMessageManager().broadcastAlerts(
                            plugin.getMessageManager().get("lang-join-check", Map.of("player", player.getName())));
                    plugin.getLangCheckManager().startCheck(player, null, langs);
                }, 80L);
            }
        }

        // Instant client checks (brand / mod list) - nothing is placed in the
        // world here, so they can run while the sign checks are still queued.
        if (plugin.getConfigManager().isClientJoinCheckEnabled()) {
            if (alreadyClientChecked.add(uuid)) {
                FoliaScheduler.runAtEntityLater(plugin, player, () -> {
                    if (!player.isOnline()) return;
                    plugin.getCheckRunner().runAuto(player,
                            plugin.getConfigManager().getClientJoinCheckTypes(),
                            "Auto-join client check");
                }, Math.max(20L, plugin.getConfigManager().getClientJoinDelayTicks()));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getClientDataManager().remove(event.getPlayer().getUniqueId());
    }
}
