package me.branduzzo.checkHacks.utils;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import me.branduzzo.checkHacks.CheckHacksPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.List;

public final class LegacySignProbe {

    private static final int LINES = 4;
    private static final String HANDLER_PREFIX = "checkhacks_sign_";
    private static final String SIGN_UPDATE = "net.minecraft.network.protocol.game.ServerboundSignUpdatePacket";

    private LegacySignProbe() {
    }

    public static void open(Player player, Location loc, List<Component> lines) {
        try {
            Object connection = connection(player);
            if (connection == null) return;
            inject(player);
            Object pos = blockPos(loc);
            send(connection, blockUpdate(pos, blockState("OAK_SIGN")));
            send(connection, blockEntity(pos, lines));
            send(connection, openEditor(pos));
        } catch (Throwable error) {
            log("open", error);
        }
    }

    public static void close(Player player) {
        try {
            Object connection = connection(player);
            if (connection == null) return;
            Class<?> packet = Class.forName("net.minecraft.network.protocol.game.ClientboundContainerClosePacket");
            send(connection, packet.getConstructor(int.class).newInstance(0));
        } catch (Throwable error) {
            log("close", error);
        }
    }

    public static void restore(Player player, Location loc) {
        try {
            Object connection = connection(player);
            if (connection == null) return;
            send(connection, blockUpdate(blockPos(loc), blockState("AIR")));
        } catch (Throwable error) {
            log("restore", error);
        }
    }

    private static void inject(Player player) {
        try {
            Channel channel = channel(player);
            if (channel == null) return;
            ChannelPipeline pipeline = channel.pipeline();
            String name = HANDLER_PREFIX + player.getUniqueId();
            if (pipeline.get(name) != null) return;
            ChannelDuplexHandler handler = new ChannelDuplexHandler() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    if (msg.getClass().getName().equals(SIGN_UPDATE) && handleSignUpdate(player, msg)) {
                        return;
                    }
                    super.channelRead(ctx, msg);
                }
            };
            if (pipeline.get("packet_handler") != null) {
                pipeline.addBefore("packet_handler", name, handler);
            } else {
                pipeline.addLast(name, handler);
            }
        } catch (Throwable error) {
            log("inject", error);
        }
    }

    private static boolean handleSignUpdate(Player player, Object packet) {
        try {
            Object front = packet.getClass().getMethod("isFrontText").invoke(packet);
            if (front instanceof Boolean value && !value) return false;
            Object pos = packet.getClass().getMethod("getPos").invoke(packet);
            int x = (int) pos.getClass().getMethod("getX").invoke(pos);
            int y = (int) pos.getClass().getMethod("getY").invoke(pos);
            int z = (int) pos.getClass().getMethod("getZ").invoke(pos);
            String[] lines = (String[]) packet.getClass().getMethod("getLines").invoke(packet);
            CheckHacksPlugin plugin = CheckHacksPlugin.getInstance();
            if (plugin == null) return false;
            return plugin.getCheckManager().acceptSignUpdate(player, x, y, z, lines)
                    || plugin.getLangCheckManager().acceptSignUpdate(player, x, y, z, lines);
        } catch (Throwable error) {
            log("response", error);
            return false;
        }
    }

    private static Object connection(Player player) throws Exception {
        Object serverPlayer = player.getClass().getMethod("getHandle").invoke(player);
        Object listener = serverPlayer.getClass().getField("connection").get(serverPlayer);
        return listener.getClass().getField("connection").get(listener);
    }

    private static Channel channel(Player player) throws Exception {
        Object connection = connection(player);
        if (connection == null) return null;
        return (Channel) connection.getClass().getField("channel").get(connection);
    }

    private static Object blockPos(Location loc) throws Exception {
        Class<?> type = Class.forName("net.minecraft.core.BlockPos");
        return type.getConstructor(int.class, int.class, int.class)
                .newInstance(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private static Object blockState(String field) throws Exception {
        Class<?> blocks = Class.forName("net.minecraft.world.level.block.Blocks");
        Object block = blocks.getField(field).get(null);
        return block.getClass().getMethod("defaultBlockState").invoke(block);
    }

    private static Object blockUpdate(Object pos, Object state) throws Exception {
        Class<?> type = Class.forName("net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket");
        Class<?> stateType = Class.forName("net.minecraft.world.level.block.state.BlockState");
        return type.getConstructor(Class.forName("net.minecraft.core.BlockPos"), stateType)
                .newInstance(pos, state);
    }

    private static Object blockEntity(Object pos, List<Component> lines) throws Exception {
        Class<?> type = Class.forName("net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket");
        Class<?> entityType = Class.forName("net.minecraft.world.level.block.entity.BlockEntityType");
        Object signType = entityType.getField("SIGN").get(null);
        return type.getConstructor(
                        Class.forName("net.minecraft.core.BlockPos"),
                        entityType,
                        Class.forName("net.minecraft.nbt.CompoundTag"))
                .newInstance(pos, signType, buildNbt(lines));
    }

    private static Object openEditor(Object pos) throws Exception {
        Class<?> type = Class.forName("net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket");
        return type.getConstructor(Class.forName("net.minecraft.core.BlockPos"), boolean.class)
                .newInstance(pos, true);
    }

    private static void send(Object connection, Object packet) throws Exception {
        Method send = connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));
        send.invoke(connection, packet);
    }

    private static Object buildNbt(List<Component> lines) throws Exception {
        Class<?> compoundClass = Class.forName("net.minecraft.nbt.CompoundTag");
        Class<?> listClass = Class.forName("net.minecraft.nbt.ListTag");
        Class<?> tagClass = Class.forName("net.minecraft.nbt.Tag");
        Class<?> stringTagClass = Class.forName("net.minecraft.nbt.StringTag");
        boolean structured = structured();
        Object root = compoundClass.getConstructor().newInstance();
        Object front = compoundClass.getConstructor().newInstance();
        Object back = compoundClass.getConstructor().newInstance();
        Object frontMessages = listClass.getConstructor().newInstance();
        Object backMessages = listClass.getConstructor().newInstance();
        Method add = listClass.getMethod("add", int.class, tagClass);
        Method put = compoundClass.getMethod("put", String.class, tagClass);
        Method putString = compoundClass.getMethod("putString", String.class, String.class);
        Method putByte = compoundClass.getMethod("putByte", String.class, byte.class);
        Method valueOf = stringTagClass.getMethod("valueOf", String.class);
        for (int i = 0; i < LINES; i++) {
            Component line = i < lines.size() ? lines.get(i) : Component.empty();
            Object tag = structured
                    ? structured(line, compoundClass)
                    : valueOf.invoke(null, GsonComponentSerializer.gson().serialize(line));
            add.invoke(frontMessages, i, tag);
            add.invoke(backMessages, i, structured
                    ? structured(Component.empty(), compoundClass)
                    : valueOf.invoke(null, "{\"text\":\"\"}"));
        }
        put.invoke(front, "messages", frontMessages);
        put.invoke(back, "messages", backMessages);
        putString.invoke(front, "color", "black");
        putString.invoke(back, "color", "black");
        putByte.invoke(front, "has_glowing_text", (byte) 0);
        putByte.invoke(back, "has_glowing_text", (byte) 0);
        put.invoke(root, "front_text", front);
        put.invoke(root, "back_text", back);
        putByte.invoke(root, "is_waxed", (byte) 0);
        return root;
    }

    private static Object structured(Component component, Class<?> compoundClass) throws Exception {
        Object tag = compoundClass.getConstructor().newInstance();
        Method putString = compoundClass.getMethod("putString", String.class, String.class);
        if (component instanceof TranslatableComponent translatable) {
            putString.invoke(tag, "translate", translatable.key());
            String fallback = translatable.fallback();
            if (fallback != null && !fallback.isEmpty()) putString.invoke(tag, "fallback", fallback);
        } else if (component instanceof KeybindComponent keybind) {
            putString.invoke(tag, "keybind", keybind.keybind());
        } else {
            putString.invoke(tag, "text", PlainTextComponentSerializer.plainText().serialize(component));
        }
        return tag;
    }

    private static boolean structured() {
        String version = Bukkit.getBukkitVersion().split("-")[0];
        String[] current = version.split("\\.");
        String[] target = {"1", "21", "5"};
        for (int i = 0; i < target.length; i++) {
            int left = i < current.length ? parseInt(current[i]) : 0;
            int right = parseInt(target[i]);
            if (left != right) return left > right;
        }
        return true;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void log(String stage, Throwable error) {
        CheckHacksPlugin plugin = CheckHacksPlugin.getInstance();
        if (plugin != null) plugin.getLogger().warning("Legacy sign probe " + stage + " failed: " + error);
    }
}
