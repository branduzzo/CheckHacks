package com.veneer.hacks.utils;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.nbt.NBTType;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import com.veneer.core.FoliaScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class SignUtil {

    private SignUtil() {}

    /**
     * Sends a virtual sign to the player via PacketEvents without touching the real Minecraft world.
     * The sign block change and its block entity data (NBT) are sent first, followed 1 tick later by
     * the open sign editor packet so the client's local level has initialized the block entity.
     * Then virtual air is sent to reset the client's local block state without leaving phantom collisions.
     */
    public static void sendVirtualSign(Player player, Location loc, Component[] lines, Plugin plugin) {
        if (player == null || !player.isOnline() || loc == null) return;
        Vector3i pos = new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

        // 1. Send virtual oak sign block
        WrapperPlayServerBlockChange blockChange = new WrapperPlayServerBlockChange(
                pos, WrappedBlockState.getDefaultState(StateTypes.OAK_SIGN));
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, blockChange);

        // 2. Build sign BlockEntityData NBT (supporting both modern 1.20+ front_text and legacy Text1..4)
        NBTCompound nbt = new NBTCompound();
        nbt.setTag("id", new NBTString("minecraft:sign"));
        nbt.setTag("x", new NBTInt(pos.getX()));
        nbt.setTag("y", new NBTInt(pos.getY()));
        nbt.setTag("z", new NBTInt(pos.getZ()));

        NBTCompound frontText = new NBTCompound();
        NBTList<NBTString> messages = new NBTList<>(NBTType.STRING);
        for (int i = 0; i < 4; i++) {
            Component c = (lines != null && i < lines.length && lines[i] != null) ? lines[i] : Component.empty();
            String json = GsonComponentSerializer.gson().serialize(c);
            messages.addTag(new NBTString(json));
            nbt.setTag("Text" + (i + 1), new NBTString(json));
        }
        frontText.setTag("messages", messages);
        nbt.setTag("front_text", frontText);

        WrapperPlayServerBlockEntityData blockEntity = new WrapperPlayServerBlockEntityData(
                pos, BlockEntityTypes.SIGN, nbt);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, blockEntity);

        // 3. Open editor 1 tick later, then restore virtual air to the client
        FoliaScheduler.runAtEntityLater(plugin, player, () -> {
            if (!player.isOnline()) return;
            WrapperPlayServerOpenSignEditor openEditor = new WrapperPlayServerOpenSignEditor(pos, true);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, openEditor);
            sendVirtualAir(player, loc);
        }, 1L);
    }

    /** Sends a virtual block change of AIR to the player at the given location. */
    public static void sendVirtualAir(Player player, Location loc) {
        if (player == null || !player.isOnline() || loc == null) return;
        sendVirtualAir(player, loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /** Sends a virtual block change of AIR to the player at the given coordinates. */
    public static void sendVirtualAir(Player player, World world, int x, int y, int z) {
        if (player == null || !player.isOnline() || world == null) return;
        if (player.getWorld() != world) return;
        Vector3i pos = new Vector3i(x, y, z);
        WrapperPlayServerBlockChange reset = new WrapperPlayServerBlockChange(
                pos, WrappedBlockState.getDefaultState(StateTypes.AIR));
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, reset);
    }

    /**
     * A free spot for the virtual probe sign, from two blocks above the feet (just over the head).
     * {@code avoid} (the previous probe sign, may be null) is skipped, so a late answer to an earlier
     * sign never matches the next one.
     */
    public static Location findAirBlock(Player player, Location avoid) {
        Location base = player.getLocation().clone();
        for (int dy = 2; dy <= 4; dy++) {
            Location loc = base.clone().add(0, dy, 0);
            if (usable(loc, avoid)) return loc;
        }
        int[][] offsets = {
                {1,1,0},{-1,1,0},{0,1,1},{0,1,-1},
                {1,0,0},{-1,0,0},{0,0,1},{0,0,-1},
                {2,1,0},{-2,1,0},{0,1,2},{0,1,-2}
        };
        for (int[] off : offsets) {
            Location loc = base.clone().add(off[0], off[1], off[2]);
            if (usable(loc, avoid)) return loc;
        }
        return null;
    }

    /** The player cannot open a sign right now: on the death screen or in bed. */
    public static boolean cannotOpen(Player player) {
        return player.isDead() || player.isSleeping();
    }

    /**
     * Whether a sign that got no answer should be asked again rather than counted as TIMEOUT: the player died,
     * went to bed, changed world (the client dropped the sign with the old world) or moved out of reach.
     */
    public static boolean lostSign(Player player, Location sign) {
        if (cannotOpen(player)) return true;
        if (sign == null) return false;
        Location at = player.getLocation();
        return at.getWorld() != sign.getWorld() || at.distanceSquared(sign) > 64;
    }

    private static boolean usable(Location loc, Location avoid) {
        return loc.getBlock().getType().isAir() && !sameBlock(loc, avoid);
    }

    public static boolean sameBlock(Location a, Location b) {
        return a != null && b != null && a.getWorld() == b.getWorld()
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }
}
