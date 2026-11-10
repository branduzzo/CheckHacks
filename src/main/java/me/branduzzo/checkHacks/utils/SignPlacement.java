package me.branduzzo.checkHacks.utils;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Selects and reserves a temporary sign and its support block on the owning
 * server/region thread. Custom destinations must pass loaded-chunk, reach and
 * border checks; the legacy nearby-air fallback keeps its original eligibility.
 * A successful caller owns the returned lease and must restore it on every exit.
 */
public final class SignPlacement {
    private static final Attribute BLOCK_RANGE_ATTRIBUTE = resolveBlockRangeAttribute();
    private static final Set<BlockKey> RESERVED = new HashSet<>();
    private static final int[][] NEARBY_OFFSETS = {
            {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
            {2, 1, 0}, {-2, 1, 0}, {0, 1, 2}, {0, 1, -2}
    };

    private SignPlacement() {}

    /** Must be called on the player's entity thread (or the normal server thread). */
    public static Lease place(Player player, SignPlacementSettings settings) {
        return place(player, settings, ignored -> {});
    }

    /**
     * Tries configured candidates in priority order, returning {@code null} and
     * reporting rejection reasons when none can be used. Call on the player's
     * entity thread. The reservation covers both blocks so simultaneous scans
     * cannot replace another scan's sign or supporting barrier.
     */
    public static Lease place(Player player, SignPlacementSettings settings, Consumer<String> onFailure) {
        Location base = player.getLocation();
        boolean customPositioning = !settings.usesLegacyPositioning();
        Map<String, Integer> rejected = new LinkedHashMap<>();
        for (Candidate candidate : candidates(base, settings)) {
            Location location = candidate.location();
            String reason = rejectionReason(player, candidate, settings.requireSentChunk());
            if (reason != null) {
                rejected.merge(reason, 1, Integer::sum);
                continue;
            }
            Lease lease = tryPlace(location, settings);
            if (lease != null) return lease;
            rejected.merge("sign/support reserved or sign creation failed", 1, Integer::sum);
        }
        onFailure.accept("No usable sign position for " + player.getName() + " at " + base
                + "; positioning=" + (customPositioning ? "custom" : "legacy") + "; rejected=" + rejected);
        return null;
    }

    private record Candidate(Location location, boolean customPositioning, boolean replaceOccupiedBlock) {}

    private static List<Candidate> candidates(Location base, SignPlacementSettings settings) {
        List<Candidate> candidates = new ArrayList<>();
        for (SignPlacementSettings.Region region : settings.regions()) {
            if (!region.contains(base)) continue;
            Location target = region.target().inWorld(base);
            if (target != null) candidates.add(new Candidate(target, true, false));
            if (!settings.fallbackNearPlayer()) return candidates;
            break; // First matching region wins; do not try another region's destination.
        }
        if (candidates.isEmpty() && settings.staticTarget() != null) {
            Location target = settings.staticTarget().inWorld(base);
            if (target != null) candidates.add(new Candidate(target, true, settings.replaceOccupiedStaticBlock()));
            if (!settings.fallbackNearPlayer()) return candidates;
        }
        candidates.add(new Candidate(base.clone().add(0, settings.yOffset(), 0).toBlockLocation(), settings.yOffset() != 1, false));
        if (!settings.fallbackNearPlayer()) return candidates;
        // A rejected custom target must not impose its eligibility gates on the
        // original nearby-air fallback (sent-chunk bookkeeping may be false on join).
        for (int dy = 1; dy <= 5; dy++)
            candidates.add(new Candidate(base.clone().add(0, dy, 0).toBlockLocation(), false, false));
        for (int[] offset : NEARBY_OFFSETS)
            candidates.add(new Candidate(base.clone().add(offset[0], offset[1], offset[2]).toBlockLocation(), false, false));
        return candidates;
    }

    private static String rejectionReason(Player player, Candidate candidate, boolean requireSentChunk) {
        Location location = candidate.location();
        World world = location.getWorld();
        if (world == null || !world.equals(player.getWorld())) return "different world";
        if (location.getBlockY() <= world.getMinHeight() || location.getBlockY() >= world.getMaxHeight()) return "outside world height";
        if (FoliaScheduler.isFolia() && !Bukkit.getServer().isOwnedByCurrentRegion(location)) return "different Folia region";
        // Preserve the original air-only eligibility for default nearby placement.
        // Sent-chunk bookkeeping/borders/reach attributes were not gates in that path.
        if (candidate.customPositioning()) {
            if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return "chunk not loaded";
            if (requireSentChunk && !hasSentChunk(player, location)) return "chunk not sent to client";
            if (!canEdit(player, location)) return "outside sign edit range";
            if (!world.getWorldBorder().isInside(location)) return "outside world border";
        }
        return candidate.replaceOccupiedBlock() || location.getBlock().getType().isAir() ? null : "block occupied";
    }

    static boolean hasSentChunk(Player player, Location location) {
        // Paper 1.21.4's isChunkSent() reads the unused vanilla tracker and can
        // always return false with Moonrise (PaperMC/Paper#12304). This API uses
        // the actual player chunk loader on that same version.
        return player.getSentChunkKeys().contains(Chunk.getChunkKey(location));
    }

    /**
     * Resolve once without linking to a version-specific Attribute field.
     * Early 1.21 uses the enum constant PLAYER_BLOCK_INTERACTION_RANGE; newer
     * APIs expose BLOCK_INTERACTION_RANGE on an interface. Reflection supports
     * both representations without invoking version-specific enum helpers.
     */
    private static Attribute resolveBlockRangeAttribute() {
        for (String name : new String[]{"BLOCK_INTERACTION_RANGE", "PLAYER_BLOCK_INTERACTION_RANGE"}) {
            try {
                return (Attribute) Attribute.class.getField(name).get(null);
            } catch (NoSuchFieldException ignored) {
                // Try the other supported name before using the normal reach fallback.
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Cannot access the block interaction range attribute", exception);
            }
        }
        return null;
    }

    /** Sign edit range: eye-to-block AABB, the player's current reach plus four. */
    public static boolean canEdit(Player player, Location location) {
        if (location == null || location.getWorld() == null || !location.getWorld().equals(player.getWorld())) return false;
        AttributeInstance reachAttribute = BLOCK_RANGE_ATTRIBUTE == null ? null : player.getAttribute(BLOCK_RANGE_ATTRIBUTE);
        double range = (reachAttribute != null ? reachAttribute.getValue() : 4.5) + 4.0;
        if (!Double.isFinite(range) || range <= 0) return false;
        Location eye = player.getEyeLocation();
        double dx = axisDistance(eye.getX(), location.getBlockX());
        double dy = axisDistance(eye.getY(), location.getBlockY());
        double dz = axisDistance(eye.getZ(), location.getBlockZ());
        return dx * dx + dy * dy + dz * dz < range * range;
    }

    private static double axisDistance(double eye, int block) {
        return eye < block ? block - eye : Math.max(0.0, eye - (block + 1.0));
    }

    private static Lease tryPlace(Location location, SignPlacementSettings settings) {
        Block block = location.getBlock();
        Block below = block.getRelative(0, -1, 0);
        BlockKey signKey = BlockKey.of(block);
        BlockKey supportKey = BlockKey.of(below);
        synchronized (RESERVED) {
            if (RESERVED.contains(signKey) || RESERVED.contains(supportKey)) return null;
            RESERVED.add(signKey);
            RESERVED.add(supportKey);
        }
        BlockState original;
        BlockState support;
        try {
            // Capture the full state before replacing an occupied static target,
            // including block data and any tile-entity snapshot (inventory, text, etc.).
            original = block.getState();
            support = below.getState();
        } catch (RuntimeException exception) {
            release(signKey, supportKey);
            throw exception;
        }
        boolean barrier = settings.barriersEnabled() && below.getType().isAir();
        Lease lease = new Lease(location.clone(), original, support, barrier,
                settings.removeBarriers(), signKey, supportKey);
        try {
            if (barrier) below.setType(Material.BARRIER, false);
            block.setType(Material.OAK_SIGN, false);
            if (!(block.getState() instanceof Sign sign)) {
                lease.restore();
                return null;
            }
            lease.sign = sign;
            return lease;
        } catch (RuntimeException exception) {
            lease.restore();
            throw exception;
        }
    }

    private static void release(BlockKey signKey, BlockKey supportKey) {
        synchronized (RESERVED) {
            RESERVED.remove(signKey);
            RESERVED.remove(supportKey);
        }
    }

    /** Captured block states and exclusive reservations for one temporary sign. */
    public static final class Lease {
        private final Location location;
        private final BlockState original;
        private final BlockState support;
        private final boolean barrierPlaced;
        private final boolean removeBarrier;
        private final BlockKey signKey;
        private final BlockKey supportKey;
        private final AtomicBoolean restored = new AtomicBoolean();
        private Sign sign;

        private Lease(Location location, BlockState original, BlockState support,
                      boolean barrierPlaced, boolean removeBarrier, BlockKey signKey, BlockKey supportKey) {
            this.location = location;
            this.original = original;
            this.support = support;
            this.barrierPlaced = barrierPlaced;
            this.removeBarrier = removeBarrier;
            this.signKey = signKey;
            this.supportKey = supportKey;
        }

        public Location location() { return location.clone(); }
        public Sign sign() { return sign; }

        /**
         * Restores the captured states once, then releases both reservations even
         * if restoration fails. An unrelated replacement block is left intact.
         * A retained barrier is intentional only after successful sign creation.
         * Must run on this location's region thread; repeated calls are harmless.
         */
        public void restore() {
            if (!restored.compareAndSet(false, true)) return;
            try {
                try {
                    Material current = location.getBlock().getType();
                    if (current == Material.OAK_SIGN || current.isAir()) original.update(true, false);
                } finally {
                    if (barrierPlaced && (removeBarrier || sign == null) && support.getBlock().getType() == Material.BARRIER)
                        support.update(true, false);
                }
            } finally {
                release(signKey, supportKey);
            }
        }
    }

    private record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }
}
