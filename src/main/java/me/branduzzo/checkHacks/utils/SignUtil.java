package me.branduzzo.checkHacks.utils;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

public final class SignUtil {

    private static final String[] CONNECTION_FIELD_NAMES = {
            "connection", "networkManager", "playerConnection"
    };

    private SignUtil() {}

    public static void setAllowedEditor(Location loc, java.util.UUID playerUUID, Plugin plugin) {
        try {
            Object be = getBlockEntity(loc);
            if (be == null) return;
            for (Method m : be.getClass().getMethods()) {
                if (m.getName().equals("setAllowedPlayerEditor") && m.getParameterCount() == 1) {
                    m.invoke(be, playerUUID);
                    return;
                }
            }
            plugin.getLogger().warning(
                    "[CheckHacks] setAllowedPlayerEditor not found on block entity; sign may reject edits");
        } catch (Exception e) {
            plugin.getLogger().warning("[CheckHacks] setAllowedEditor: " + e.getMessage());
        }
    }

    public static void sendBlockEntityPacket(Player player, Location loc, Plugin plugin) {
        try {
            Object be = getBlockEntity(loc);
            if (be == null) return;
            Class<?> pktClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket");
            Method create = Arrays.stream(pktClass.getMethods())
                    .filter(m -> m.getName().equals("create") && m.getParameterCount() == 1)
                    .findFirst().orElse(null);
            if (create == null) return;
            sendPacket(player, create.invoke(null, be), plugin);
        } catch (Exception e) {
            plugin.getLogger().warning("[CheckHacks] sendBlockEntityPacket: " + e.getMessage());
        }
    }

    public static void sendOpenSignPacket(Player player, Location loc, Plugin plugin) {
        try {
            Class<?> bpClass = Class.forName("net.minecraft.core.BlockPos");
            Class<?> pktClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket");
            Object bp = newBlockPos(bpClass, loc);
            Object packet = pktClass.getConstructor(bpClass, boolean.class).newInstance(bp, true);
            sendPacket(player, packet, plugin);
        } catch (Exception e) {
            plugin.getLogger().warning("[CheckHacks] sendOpenSignPacket: " + e.getMessage());
        }
    }

    public static void sendPacket(Player player, Object packet, Plugin plugin) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object conn = findConnection(handle);
            if (conn == null) throw new IllegalStateException("connection not found");
            Method send = findSendMethod(conn.getClass(), packet.getClass());
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
                {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
                {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
                {2, 1, 0}, {-2, 1, 0}, {0, 1, 2}, {0, 1, -2}
        };
        for (int[] off : offsets) {
            Location loc = base.clone().add(off[0], off[1], off[2]);
            if (loc.getBlock().getType().isAir()) return loc;
        }
        return null;
    }

    private static Object getBlockEntity(Location loc) throws Exception {
        Object world = loc.getWorld().getClass().getMethod("getHandle").invoke(loc.getWorld());
        Class<?> bpClass = Class.forName("net.minecraft.core.BlockPos");
        Object bp = newBlockPos(bpClass, loc);
        Method gbe = Arrays.stream(world.getClass().getMethods())
                .filter(m -> m.getName().equals("getBlockEntity") && m.getParameterCount() == 1)
                .findFirst().orElse(null);
        if (gbe == null) return null;
        return gbe.invoke(world, bp);
    }

    private static Object newBlockPos(Class<?> bpClass, Location loc) throws Exception {
        return bpClass.getConstructor(int.class, int.class, int.class)
                .newInstance(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private static Object findConnection(Object handle) {
        for (String name : CONNECTION_FIELD_NAMES) {
            try {
                Field f;
                try {
                    f = handle.getClass().getField(name);
                } catch (NoSuchFieldException ex) {
                    f = handle.getClass().getDeclaredField(name);
                    f.setAccessible(true);
                }
                Object v = f.get(handle);
                if (v != null) return v;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Method findSendMethod(Class<?> connClass, Class<?> packetClass) {
        for (Method m : connClass.getMethods()) {
            if (m.getName().equals("send") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(packetClass)) {
                return m;
            }
        }
        for (Method m : connClass.getMethods()) {
            if (m.getName().equals("send") && m.getParameterCount() == 1) return m;
        }
        return null;
    }
}
