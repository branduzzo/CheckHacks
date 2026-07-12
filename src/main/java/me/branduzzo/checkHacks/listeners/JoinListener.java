package me.branduzzo.checkHacks.listeners;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.HackDefinition;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class JoinListener implements Listener {

    private final CheckHacksPlugin plugin;
    private final Set<UUID> alreadyHackChecked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alreadyLangChecked = ConcurrentHashMap.newKeySet();

    public JoinListener(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        boolean scheduleHack = plugin.getConfigManager().isJoinCheckEnabled()
                && (!plugin.getConfigManager().isOnlyFirstJoin() || alreadyHackChecked.add(uuid));
        boolean scheduleLang = plugin.getConfigManager().isLangJoinCheckEnabled()
                && (!plugin.getConfigManager().isLangOnlyFirstJoin() || alreadyLangChecked.add(uuid));

        if (scheduleHack) {
            FoliaScheduler.runAtEntityLater(plugin, player, () -> {
                if (!player.isOnline() || plugin.hasActiveSignSession(uuid)) return;
                List<HackDefinition> hacks = plugin.getConfigManager().getJoinCheckHacks();
                if (hacks.isEmpty()) {
                    if (scheduleLang) startLangJoin(player, uuid);
                    return;
                }
                plugin.getMessageManager().broadcastAlerts(
                        plugin.getMessageManager().get("join-check", Map.of("player", player.getName())));
                plugin.getCheckManager().startCheck(player, null, hacks, true, "Auto-join check");
            }, 60L);
        } else if (scheduleLang) {
            FoliaScheduler.runAtEntityLater(plugin, player, () -> startLangJoin(player, uuid), 80L);
        }
    }

    private void startLangJoin(Player player, UUID uuid) {
        if (!player.isOnline() || plugin.hasActiveSignSession(uuid)) return;
        Map<String, String> langs = plugin.getConfigManager().getLanguages();
        if (langs.isEmpty()) return;
        plugin.getMessageManager().broadcastAlerts(
                plugin.getMessageManager().get("lang-join-check", Map.of("player", player.getName())));
        plugin.getLangCheckManager().startCheck(player, null, langs);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.abortSignSessions(event.getPlayer().getUniqueId());
    }
}
