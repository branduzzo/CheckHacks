package me.branduzzo.checkHacks.utils;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;

public final class PacketSignProbe {

    private static final int LINES = 4;
    private static volatile boolean packetEventsEnabled;

    private PacketSignProbe() {
    }

    public static void setPacketEventsEnabled(boolean enabled) {
        packetEventsEnabled = enabled;
    }

    public static Location findProbeLocation(Player player) {
        Location base = player.getLocation();
        int x = base.getBlockX();
        int z = base.getBlockZ();
        int min = player.getWorld().getMinHeight();
        int max = player.getWorld().getMaxHeight() - 1;
        int above = Math.min(max, base.getBlockY() + 8);
        int below = Math.max(min, base.getBlockY() - 8);
        for (int distance = 0; distance <= 16; distance++) {
            int up = above + distance;
            if (up <= max && player.getWorld().getBlockAt(x, up, z).getType().isAir()) {
                return new Location(player.getWorld(), x, up, z);
            }
            int down = below - distance;
            if (down >= min && player.getWorld().getBlockAt(x, down, z).getType().isAir()) {
                return new Location(player.getWorld(), x, down, z);
            }
        }
        return new Location(player.getWorld(), x, base.getBlockY(), z);
    }

    public static void open(Player player, Location loc, List<Component> lines) {
        if (!packetEventsEnabled) {
            LegacySignProbe.open(player, loc, lines);
            return;
        }
        User user = user(player);
        if (user == null) return;
        Vector3i pos = position(loc);
        ClientVersion version = user.getClientVersion();
        WrappedBlockState sign = StateTypes.OAK_SIGN.createBlockState(version);
        user.sendPacket(new WrapperPlayServerBlockChange(pos, sign));
        user.sendPacket(new WrapperPlayServerBlockEntityData(pos, BlockEntityTypes.SIGN, buildNbt(lines, version)));
        user.sendPacket(new WrapperPlayServerOpenSignEditor(pos, true));
    }

    public static void close(Player player) {
        if (!packetEventsEnabled) {
            LegacySignProbe.close(player);
            return;
        }
        User user = user(player);
        if (user != null) user.sendPacket(new WrapperPlayServerCloseWindow(0));
    }

    public static void restore(Player player, Location loc) {
        if (!packetEventsEnabled) {
            LegacySignProbe.restore(player, loc);
            return;
        }
        User user = user(player);
        if (user == null) return;
        try {
            WrappedBlockState air = WrappedBlockState.getByString(user.getClientVersion(), "minecraft:air");
            user.sendPacket(new WrapperPlayServerBlockChange(position(loc), air));
        } catch (Exception ignored) {
        }
    }

    private static User user(Player player) {
        if (PacketEvents.getAPI() == null || player == null) return null;
        return PacketEvents.getAPI().getPlayerManager().getUser(player);
    }

    private static Vector3i position(Location loc) {
        return new Vector3i(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private static NBTCompound buildNbt(List<Component> lines, ClientVersion version) {
        boolean structured = version.isNewerThanOrEquals(ClientVersion.V_1_21_5);
        NBTCompound root = new NBTCompound();
        root.setTag("front_text", side(lines, structured));
        root.setTag("back_text", side(List.of(), structured));
        root.setTag("is_waxed", new NBTByte((byte) 0));
        return root;
    }

    private static NBTCompound side(List<Component> lines, boolean structured) {
        NBTCompound side = new NBTCompound();
        side.setTag("messages", messages(lines, structured));
        side.setTag("color", new NBTString("black"));
        side.setTag("has_glowing_text", new NBTByte((byte) 0));
        return side;
    }

    private static NBTList<?> messages(List<Component> lines, boolean structured) {
        if (structured) {
            NBTList<NBTCompound> list = NBTList.createCompoundList();
            for (int i = 0; i < LINES; i++) {
                list.addTag(i < lines.size() ? structured(lines.get(i)) : empty());
            }
            return list;
        }
        NBTList<NBTString> list = NBTList.createStringList();
        for (int i = 0; i < LINES; i++) {
            list.addTag(new NBTString(i < lines.size()
                    ? GsonComponentSerializer.gson().serialize(lines.get(i))
                    : "{\"text\":\"\"}"));
        }
        return list;
    }

    private static NBTCompound structured(Component component) {
        if (component instanceof TranslatableComponent translatable) {
            NBTCompound tag = new NBTCompound();
            tag.setTag("translate", new NBTString(translatable.key()));
            String fallback = translatable.fallback();
            if (fallback != null && !fallback.isEmpty()) {
                tag.setTag("fallback", new NBTString(fallback));
            }
            return tag;
        }
        if (component instanceof KeybindComponent keybind) {
            NBTCompound tag = new NBTCompound();
            tag.setTag("keybind", new NBTString(keybind.keybind()));
            return tag;
        }
        NBTCompound tag = new NBTCompound();
        tag.setTag("text", new NBTString(PlainTextComponentSerializer.plainText().serialize(component)));
        return tag;
    }

    private static NBTCompound empty() {
        NBTCompound tag = new NBTCompound();
        tag.setTag("text", new NBTString(""));
        return tag;
    }
}
