package me.branduzzo.checkHacks.session;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import me.branduzzo.checkHacks.utils.SignUtil;
import me.branduzzo.checkHacks.utils.WrappedTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class SignSession {

    private final CheckHacksPlugin plugin;
    private Location signLocation;
    private BlockState originalState;
    private boolean barrierPlaced;
    private Location barrierLocation;
    private WrappedTask timeoutTask;
    private final AtomicBoolean settled = new AtomicBoolean(false);

    private SignSession(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isActive() {
        return signLocation != null;
    }

    public static Optional<SignSession> open(
            CheckHacksPlugin plugin,
            Player target,
            List<Component> lines,
            long timeoutTicks,
            Supplier<Boolean> stillActive,
            Runnable onTimeout) {

        Location signLoc = SignUtil.findAirBlock(target);
        if (signLoc == null) return Optional.empty();

        SignSession session = new SignSession(plugin);
        Block block = signLoc.getBlock();
        BlockState originalState = block.getState();

        Location belowLoc = signLoc.clone().subtract(0, 1, 0);
        Block belowBlock = belowLoc.getBlock();
        boolean placedBarrier = belowBlock.getType().isAir();
        if (placedBarrier) belowBlock.setType(Material.BARRIER, false);

        block.setType(Material.OAK_SIGN, false);
        BlockState freshState = block.getState();
        if (!(freshState instanceof Sign sign)) {
            originalState.update(true, false);
            if (placedBarrier) belowBlock.setType(Material.AIR, false);
            return Optional.empty();
        }

        applyLines(sign, lines);
        sign.update(true, false);

        session.signLocation = signLoc;
        session.originalState = originalState;
        session.barrierPlaced = placedBarrier;
        session.barrierLocation = belowLoc;

        session.armEditor(target, stillActive, timeoutTicks, onTimeout);
        return Optional.of(session);
    }

    public boolean reopen(
            Player target,
            List<Component> lines,
            long timeoutTicks,
            Supplier<Boolean> stillActive,
            Runnable onTimeout) {

        if (signLocation == null) return false;
        BlockState state = signLocation.getBlock().getState();
        if (!(state instanceof Sign sign)) return false;

        cancelTimeout();
        applyLines(sign, lines);
        sign.update(true, false);
        armEditor(target, stillActive, timeoutTicks, onTimeout);
        return true;
    }

    private static void applyLines(Sign sign, List<Component> lines) {
        var front = sign.getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) {
            front.line(i, i < lines.size() ? lines.get(i) : Component.empty());
        }
    }

    private void armEditor(
            Player target,
            Supplier<Boolean> stillActive,
            long timeoutTicks,
            Runnable onTimeout) {

        settled.set(false);
        UUID uuid = target.getUniqueId();
        Location loc = signLocation;
        SignUtil.setAllowedEditor(loc, uuid, plugin);

        SignUtil.sendBlockEntityPacket(target, loc, plugin);
        FoliaScheduler.runAtEntityLater(plugin, target, () -> {
            if (!Boolean.TRUE.equals(stillActive.get()) || signLocation == null || settled.get()) return;
            SignUtil.sendOpenSignPacket(target, loc, plugin);
            target.sendBlockChange(loc, Material.AIR.createBlockData());
        }, 1L);

        timeoutTask = FoliaScheduler.runAtLocationLater(plugin, loc, () -> {
            if (!Boolean.TRUE.equals(stillActive.get())) return;
            if (!settled.compareAndSet(false, true)) return;
            cancelTimeoutHandleOnly();
            restoreWorldOnly();
            onTimeout.run();
        }, Math.max(1L, timeoutTicks));
    }

    public boolean claimForResponse() {
        if (!settled.compareAndSet(false, true)) return false;
        cancelTimeoutHandleOnly();
        return true;
    }

    private void cancelTimeoutHandleOnly() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
    }

    public void cancelTimeout() {
        cancelTimeoutHandleOnly();
    }

    public void restore() {
        cancelTimeoutHandleOnly();
        settled.set(true);
        restoreWorldOnly();
    }

    private void restoreWorldOnly() {
        Location loc = signLocation;
        if (loc == null) return;
        final BlockState original = originalState;
        final boolean barrier = barrierPlaced;
        final Location barrierLoc = barrierLocation;
        signLocation = null;
        originalState = null;
        barrierPlaced = false;
        barrierLocation = null;

        FoliaScheduler.runAtLocation(plugin, loc, () -> {
            try {
                if (original != null) original.update(true, false);
            } catch (Exception e) {
                plugin.getLogger().warning("[CheckHacks] Sign restore: " + e.getMessage());
            }
            if (barrier && barrierLoc != null) {
                try {
                    barrierLoc.getBlock().setType(Material.AIR, false);
                } catch (Exception e) {
                    plugin.getLogger().warning("[CheckHacks] Barrier restore: " + e.getMessage());
                }
            }
        });
    }

    public Location getSignLocation() {
        return signLocation;
    }
}
