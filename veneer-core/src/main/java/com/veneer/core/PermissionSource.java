package com.veneer.core;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Where permission nodes set on a player come from. Core resolves bypasses through it; the hooks module plugs in
 * LuckPerms, which knows the group a node comes from. {@link #BUKKIT} is used when nothing better is installed.
 */
public interface PermissionSource {

    /** A permission node set on a player: its value and the group it comes from (null when set on the player). */
    record SetNode(String node, boolean value, String group) {}

    /** Nodes under {@code prefix} set on the player, the player's own first. */
    List<SetNode> setNodes(Player player, String prefix);

    /**
     * Which of {@code nodes} an offline account has, or null when this source cannot tell (Bukkit knows only online players).
     */
    default java.util.concurrent.CompletableFuture<java.util.Set<String>> offlinePermissions(java.util.UUID uuid, java.util.Collection<String> nodes) {
        return null;
    }

    /** The player's primary group, or "" when not known. */
    default String primaryGroup(Player player) {
        return "";
    }

    /** What Bukkit reports as set (a group is not known there). */
    PermissionSource BUKKIT = (player, prefix) -> {
        List<SetNode> nodes = new ArrayList<>();
        for (PermissionAttachmentInfo info : player.getEffectivePermissions())
            if (info.getPermission().startsWith(prefix)) nodes.add(new SetNode(info.getPermission(), info.getValue(), null));
        return nodes;
    };
}
