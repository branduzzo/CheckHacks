package com.veneer.hooks;

import com.veneer.core.PermissionSource;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.metadata.types.InheritanceOriginMetadata;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional LuckPerms integration: a player's primary group and own permission nodes. Only loaded when LuckPerms is present. */
final class LuckPermsHook {

    private final LuckPerms lp = LuckPermsProvider.get();

    /** Primary group of a player, or "" when LuckPerms has no loaded user for them. */
    String getPrimaryGroup(Player player) {
        User user = lp.getUserManager().getUser(player.getUniqueId());
        return user == null ? "" : user.getPrimaryGroup();
    }

    /**
     * Permission nodes under {@code prefix} set on the player in their current contexts, own nodes first, then
     * groups by inheritance order; a node set in several places is listed once, where LuckPerms takes it from.
     * Wildcards above the prefix ({@code veneer.*}, {@code *}) and Bukkit child permissions do not count: LuckPerms
     * applies those when a permission is checked, not here.
     */
    List<PermissionSource.SetNode> nodes(Player player, String prefix) {
        User user = lp.getUserManager().getUser(player.getUniqueId());
        if (user == null) return List.of();
        QueryOptions options = lp.getContextManager().getQueryOptions(player);
        Map<String, PermissionSource.SetNode> found = new LinkedHashMap<>();
        for (PermissionNode node : user.resolveInheritedNodes(NodeType.PERMISSION, options)) {
            String key = node.getPermission();
            if (!key.startsWith(prefix) || found.containsKey(key)) continue;
            PermissionHolder.Identifier origin = node.metadata(InheritanceOriginMetadata.KEY).getOrigin();
            String group = PermissionHolder.Identifier.GROUP_TYPE.equals(origin.getType()) ? origin.getName() : null;
            found.put(key, new PermissionSource.SetNode(key, node.getValue(), group));
        }
        return List.copyOf(found.values());
    }

    /** Loads the account from LuckPerms storage; a node LuckPerms leaves undefined follows the Bukkit default (op). */
    java.util.concurrent.CompletableFuture<java.util.Set<String>> offlinePermissions(java.util.UUID uuid, java.util.Collection<String> nodes) {
        boolean op = org.bukkit.Bukkit.getOfflinePlayer(uuid).isOp();
        return lp.getUserManager().loadUser(uuid).thenApply(user -> {
            java.util.Set<String> granted = new java.util.HashSet<>();
            var data = user.getCachedData().getPermissionData();
            for (String node : nodes) {
                var value = data.checkPermission(node);
                if (value.asBoolean() || (op && value == net.luckperms.api.util.Tristate.UNDEFINED)) granted.add(node);
            }
            return granted;
        });
    }
}
