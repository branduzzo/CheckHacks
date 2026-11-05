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

public class JoinListener {

    private final VeneerPlugin plugin;
    private final Set<UUID> alreadyHackChecked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alreadyLangChecked = ConcurrentHashMap.newKeySet();

    public JoinListener(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    public void onJoin(Player player) {
        UUID uuid = player.getUniqueId();
        if (plugin.bypasses(player, "hacks", true)) return;

        if (plugin.module(HacksModule.class).config().isJoinCheckEnabled()) {
            if (!plugin.module(HacksModule.class).config().isOnlyFirstJoin() || alreadyHackChecked.add(uuid)) {
                FoliaScheduler.runAtEntityLater(plugin, player, () -> {
                    if (!player.isOnline()) return;
                    java.util.List<HackDefinition> hacks = plugin.module(HacksModule.class).config().getJoinCheckHacks();
                    if (hacks.isEmpty()) return;
                    plugin.alerts().send(m -> m.get("join-check", Map.of("player", player.getName())));
                    plugin.module(HacksModule.class).checks().startCheck(player, null, hacks, true, "Auto-join check");
                }, 60L);
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
