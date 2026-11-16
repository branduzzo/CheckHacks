package me.branduzzo.checkHacks.listeners;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import me.branduzzo.checkHacks.CheckHacksPlugin;
import org.bukkit.entity.Player;

public class PacketSignListener extends PacketListenerAbstract {

    private final CheckHacksPlugin plugin;

    public PacketSignListener(CheckHacksPlugin plugin) {
        super(PacketListenerPriority.LOW);
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) return;
        Player player = event.getPlayer();
        if (player == null) return;
        WrapperPlayClientUpdateSign packet = new WrapperPlayClientUpdateSign(event);
        Vector3i position = packet.getBlockPosition();
        String[] lines = packet.getTextLines();
        if (plugin.getCheckManager().acceptSignUpdate(player, position, lines)
                || plugin.getLangCheckManager().acceptSignUpdate(player, position, lines)) {
            event.setCancelled(true);
        }
    }
}
