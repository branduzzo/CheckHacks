package me.branduzzo.checkHacks.utils;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.block.Sign;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class SignUtil {

    /** A block has no player yaw/pitch; Location.equals compares those as well. */
    public static boolean isSameBlock(Location expected, Location actual) {
        return expected != null && actual != null && expected.getWorld() != null
                && expected.getWorld().equals(actual.getWorld())
                && expected.getBlockX() == actual.getBlockX()
                && expected.getBlockY() == actual.getBlockY()
                && expected.getBlockZ() == actual.getBlockZ();
    }

    public static void setAllowedEditor(Location loc, UUID playerUUID, Plugin plugin) {
        try {
            Object world = loc.getWorld().getClass().getMethod("getHandle").invoke(loc.getWorld());
            Class<?> bpClass = Class.forName("net.minecraft.core.BlockPos");
            Object bp = bpClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            Method gbe = Arrays.stream(world.getClass().getMethods())
                    .filter(m -> m.getName().equals("getBlockEntity") && m.getParameterCount() == 1)
                    .findFirst().orElse(null);
            if (gbe == null) return;
            Object be = gbe.invoke(world, bp);
            if (be == null) return;
            for (Method m : be.getClass().getMethods()) {
                if (m.getName().equals("setAllowedPlayerEditor") && m.getParameterCount() == 1) {
                    m.invoke(be, playerUUID);
                    return;
                }
            }
            for (Field f : getAllFields(be.getClass())) {
                if (f.getType().equals(UUID.class)) {
                    f.setAccessible(true);
                    f.set(be, playerUUID);
                    return;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[CheckHacks] setAllowedEditor: " + e.getMessage());
        }
    }

    /** Sends the captured sign state without reflection or reading its world on the entity thread. */
    public static boolean sendSignSnapshot(Player player, Location loc, Sign sign, Plugin plugin) {
        try {
            player.sendBlockUpdate(loc, sign);
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("[CheckHacks] Cannot send sign data to " + player.getName()
                    + " at " + loc + ": " + e);
            return false;
        }
    }

    public static void sendOpenSignPacket(Player player, Location loc, Plugin plugin) {
        try {
            Class<?> bpClass  = Class.forName("net.minecraft.core.BlockPos");
            Class<?> pktClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket");
            Object bp     = bpClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            Object packet = pktClass.getConstructor(bpClass, boolean.class).newInstance(bp, true);
            sendPacket(player, packet, plugin);
        } catch (Exception e) {
            plugin.getLogger().warning("[CheckHacks] sendOpenSignPacket: " + e.getMessage());
        }
    }

    public static void sendPacket(Player player, Object packet, Plugin plugin) {
        if (packet == null) return;
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object conn = null;
            for (String name : new String[]{"connection", "networkManager", "playerConnection"}) {
                try {
                    Field f;
                    try { f = handle.getClass().getField(name); }
                    catch (NoSuchFieldException ex) {
                        f = handle.getClass().getDeclaredField(name);
                        f.setAccessible(true);
                    }
                    Object v = f.get(handle);
                    if (v != null) { conn = v; break; }
                } catch (Exception ignored) {}
            }
            if (conn == null) throw new IllegalStateException("connection not found");
            Method send = null;
            for (Method m : conn.getClass().getMethods())
                if (m.getName().equals("send") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(packet.getClass())) {
                    send = m; break;
                }
            if (send == null)
                for (Method m : conn.getClass().getMethods())
                    if (m.getName().equals("send") && m.getParameterCount() == 1) { send = m; break; }
            if (send == null) throw new IllegalStateException("send() not found");
            send.invoke(conn, packet);
        } catch (Exception e) {
            plugin.getLogger().warning("[CheckHacks] sendPacket: " + e.getMessage());
        }
    }

    public static Location findAirBlock(Player player) {
        Location base = player.getLocation().clone();
        for (int dy = 1; dy <= 5; dy++) {
            Location loc = base.clone().add(0, dy, 0);
            if (loc.getBlock().getType().isAir()) return loc;
        }
        int[][] offsets = {
                {1,1,0},{-1,1,0},{0,1,1},{0,1,-1},
                {1,0,0},{-1,0,0},{0,0,1},{0,0,-1},
                {2,1,0},{-2,1,0},{0,1,2},{0,1,-2}
        };
        for (int[] off : offsets) {
            Location loc = base.clone().add(off[0], off[1], off[2]);
            if (loc.getBlock().getType().isAir()) return loc;
        }
        return null;
    }

    public static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
    }
}
