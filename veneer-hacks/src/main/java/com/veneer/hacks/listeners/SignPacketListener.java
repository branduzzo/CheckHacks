package com.veneer.hacks.listeners;

import com.veneer.hacks.HacksModule;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.veneer.VeneerPlugin;

/**
 * Reads the language probe sign's answer from the packet, before Paper cuts every line to 80 characters
 * ({@code Paper.maxSignLength}); the version probe packs far more into a line. The answer is still handled by
 * {@link SignListener}, which runs after this on the main thread.
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
        Vector3i at = packet.getBlockPosition();
        langChecks.rawAnswer(event.getUser().getUUID(), at.getX(), at.getY(), at.getZ(), packet.getTextLines().clone());
    }
}
