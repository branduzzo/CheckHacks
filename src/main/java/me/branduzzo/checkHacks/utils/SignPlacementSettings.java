package me.branduzzo.checkHacks.utils;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Immutable settings, snapshotted when a scan places its sign. */
public record SignPlacementSettings(boolean barriersEnabled, boolean removeBarriers,
                                    int yOffset, boolean fallbackNearPlayer,
                                    boolean requireSentChunk,
                                    boolean replaceOccupiedStaticBlock,
                                    Target staticTarget, List<Region> regions) {

    public SignPlacementSettings {
        regions = List.copyOf(regions);
    }

    /** Extra remote-position eligibility checks must not change the original nearby-air search. */
    public boolean usesLegacyPositioning() {
        return yOffset == 1 && fallbackNearPlayer && staticTarget == null && regions.isEmpty();
    }

    /**
     * Reads optional placement overrides without writing back to the YAML file.
     * Invalid targets are ignored with a warning; cuboid corners are normalized
     * and retain declaration order because the first matching region wins.
     */
    public static SignPlacementSettings from(ConfigurationSection section, Consumer<String> warning) {
        if (section == null) return new SignPlacementSettings(true, true, 1, true, true, false, null, List.of());
        Target fixed = null;
        ConfigurationSection staticSection = section.getConfigurationSection("static-target");
        if (staticSection != null && staticSection.getBoolean("enabled", false)) {
            fixed = readTarget(staticSection, staticSection.getString("world", ""), warning,
                    "sign-placement.static-target");
        }
        List<Region> regions = new ArrayList<>();
        ConfigurationSection regionSection = section.getConfigurationSection("regions");
        if (regionSection != null) {
            for (String name : regionSection.getKeys(false)) {
                ConfigurationSection region = regionSection.getConfigurationSection(name);
                if (region == null || !region.getBoolean("enabled", true)) continue;
                String world = region.getString("world", "");
                Target target = readTarget(region.getConfigurationSection("target"), world, warning,
                        "sign-placement.regions." + name + ".target");
                ConfigurationSection min = region.getConfigurationSection("min");
                ConfigurationSection max = region.getConfigurationSection("max");
                if (target == null || min == null || max == null
                        || !hasCoordinates(min) || !hasCoordinates(max)) {
                    warning.accept("Ignoring sign placement region '" + name + "': integer min/max/target coordinates are required.");
                    continue;
                }
                regions.add(new Region(world,
                        Math.min(min.getInt("x"), max.getInt("x")),
                        Math.min(min.getInt("y"), max.getInt("y")),
                        Math.min(min.getInt("z"), max.getInt("z")),
                        Math.max(min.getInt("x"), max.getInt("x")),
                        Math.max(min.getInt("y"), max.getInt("y")),
                        Math.max(min.getInt("z"), max.getInt("z")), target));
            }
        }
        return new SignPlacementSettings(section.getBoolean("barriers.enabled", true),
                section.getBoolean("barriers.remove-after-check", true),
                section.getInt("y-offset", 1), section.getBoolean("fallback-near-player", true),
                section.getBoolean("require-sent-chunk", true),
                section.getBoolean("static-target.replace-occupied-block", false),
                fixed, regions);
    }

    private static Target readTarget(ConfigurationSection section, String world,
                                     Consumer<String> warning, String path) {
        if (section == null || world == null || world.isBlank() || !hasCoordinates(section)) {
            warning.accept("Ignoring " + path + ": a world and integer x/y/z coordinates are required.");
            return null;
        }
        return new Target(world, section.getInt("x"), section.getInt("y"), section.getInt("z"));
    }

    private static boolean hasCoordinates(ConfigurationSection section) {
        return section.isInt("x") && section.isInt("y") && section.isInt("z");
    }

    public record Target(String world, int x, int y, int z) {
        public Location inWorld(Location playerLocation) {
            if (playerLocation.getWorld() == null || !playerLocation.getWorld().getName().equals(world)) return null;
            return new Location(playerLocation.getWorld(), x, y, z);
        }
    }

    public record Region(String world, int minX, int minY, int minZ,
                         int maxX, int maxY, int maxZ, Target target) {
        public boolean contains(Location location) {
            return location.getWorld() != null && location.getWorld().getName().equals(world)
                    && location.getBlockX() >= minX && location.getBlockX() <= maxX
                    && location.getBlockY() >= minY && location.getBlockY() <= maxY
                    && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
        }
    }
}
