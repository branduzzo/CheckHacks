package com.veneer.hacks.utils;

import com.veneer.core.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class SignUtil {

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
            plugin.getLogger().warning("[Hacks] setAllowedEditor: " + e.getMessage());
        }
    }

    public static void sendBlockEntityPacket(Player player, Location loc, Plugin plugin) {
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
            Class<?> pktClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket");
            Method create = Arrays.stream(pktClass.getMethods())
                    .filter(m -> m.getName().equals("create") && m.getParameterCount() == 1)
                    .findFirst().orElse(null);
            if (create == null) return;
            sendPacket(player, create.invoke(null, be), plugin);
        } catch (Exception e) {
            plugin.getLogger().warning("[Hacks] sendBlockEntityPacket: " + e.getMessage());
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
            plugin.getLogger().warning("[Hacks] sendOpenSignPacket: " + e.getMessage());
        }
    }

    public static void sendPacket(Player player, Object packet, Plugin plugin) {
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
            plugin.getLogger().warning("[Hacks] sendPacket: " + e.getMessage());
        }
    }

    /**
     * A free spot for the probe sign, from two blocks above the feet (just over the head). The sign stands on
     * nothing; {@code SignListener} keeps physics from breaking it. {@code avoid} (the previous probe sign, may
     * be null) is skipped, so a late answer to an earlier sign never lands on the next one.
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

    /**
     * Puts back what a probe sign replaced. The values are passed in rather than read from the check data later,
     * because the next batch overwrites that data before a scheduled restore would run.
     * {@code now} restores on the calling thread: used on shutdown, when the scheduler no longer accepts tasks.
     * Folia disables plugins on a thread that cannot write blocks; a sign left that way goes to {@link #LEFTOVERS}
     * and is removed on the next start ({@link #saveLeftovers}, {@link #clearLeftovers}).
     */
    public static void restore(Plugin plugin, Location signLoc, BlockState original, boolean now) {
        if (signLoc == null) return;
        if (!now) {
            FoliaScheduler.runAtLocation(plugin, signLoc, () -> {
                try { if (original != null) original.update(true, false); }
                catch (Exception e) { plugin.getLogger().warning("[Hacks] Restore: " + e.getMessage()); }
            });
            return;
        }
        try { if (original != null) original.update(true, false); }
        catch (Exception e) { LEFTOVERS.add(signLoc.clone()); }
    }

    private static final List<Location> LEFTOVERS = new ArrayList<>();
    private static final String LEFTOVER_FILE = "probe-signs.txt";

    /** Writes the probe signs shutdown could not remove. */
    public static void saveLeftovers(Plugin plugin) {
        if (LEFTOVERS.isEmpty()) return;
        List<String> lines = LEFTOVERS.stream().map(l -> l.getWorld().getName() + " " + l.getBlockX()
                + " " + l.getBlockY() + " " + l.getBlockZ()).toList();
        try { Files.write(new File(plugin.getDataFolder(), LEFTOVER_FILE).toPath(), lines); }
        catch (Exception e) { plugin.getLogger().warning("[Hacks] Could not save probe sign positions: " + e.getMessage()); }
        LEFTOVERS.clear();
    }

    /**
     * Removes probe signs a previous shutdown left in the world. A probe sign only ever replaces air
     * ({@link #findAirBlock}), so the block is set back to air when it is still an oak sign.
     */
    public static void clearLeftovers(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), LEFTOVER_FILE);
        if (!file.isFile()) return;
        try {
            for (String line : Files.readAllLines(file.toPath())) {
                String[] p = line.trim().split(" ");
                World world = p.length == 4 ? Bukkit.getWorld(p[0]) : null;
                if (world == null) continue;
                Location loc = new Location(world, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                FoliaScheduler.runAtLocation(plugin, loc, () -> {
                    if (loc.getBlock().getType() == Material.OAK_SIGN) loc.getBlock().setType(Material.AIR, false);
                });
            }
            plugin.getLogger().info("[Hacks] Removed probe signs left by the last shutdown.");
        } catch (Exception e) {
            plugin.getLogger().warning("[Hacks] Could not read probe sign positions: " + e.getMessage());
        }
        file.delete();
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
