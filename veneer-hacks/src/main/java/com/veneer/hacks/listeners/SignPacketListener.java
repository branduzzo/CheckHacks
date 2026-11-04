package com.veneer.hacks.listeners;

import com.veneer.hacks.HacksModule;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.veneer.VeneerPlugin;
import com.veneer.core.FoliaScheduler;
import com.veneer.hacks.utils.SignUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Intercepts UPDATE_SIGN client packets for virtual probe signs (mod check and language/version check).
 * Cancels the packet so Paper/Spigot never sees a sign update for a non-existent physical block,
 * reads full raw lines without truncation, resets virtual air on the client, and forwards the response
 * to CheckManager or LangCheckManager on the target's entity thread.
 */
public class SignPacketListener extends PacketListenerAbstract {

    private final VeneerPlugin plugin;

    public SignPacketListener(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN || event.getUser().getUUID() == null) return;
        var langChecks = plugin.module(HacksModule.class).langChecks();
        if (langChecks == null || !langChecks.isChecking(event.getUser().getUUID())) return;
        WrapperPlayClientUpdateSign packet = new WrapperPlayClientUpdateSign(event);
        UUID uuid = event.getUser().getUUID();
        Vector3i at = packet.getBlockPosition();

        var checks = plugin.hacks().checks();
        var langChecks = plugin.hacks().langChecks();

        boolean hackCheck = checks != null && checks.matchesProbe(uuid, at.getX(), at.getY(), at.getZ());
        boolean langCheck = !hackCheck && langChecks != null && langChecks.matchesProbe(uuid, at.getX(), at.getY(), at.getZ());

        if (!hackCheck && !langCheck) return;

        // Cancel the packet so the server never processes a sign update for a virtual block that doesn't exist in the world
        event.setCancelled(true);

        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        String[] lines = packet.getTextLines().clone();

        // Reset virtual air on client
        SignUtil.sendVirtualAir(player, player.getWorld(), at.getX(), at.getY(), at.getZ());

        if (hackCheck) {
            FoliaScheduler.runAtEntity(plugin, player, () -> checks.handleBatchResponse(player, lines));
        } else {
            FoliaScheduler.runAtEntity(plugin, player, () -> langChecks.handleResponse(player, lines));
        }
    }
}
