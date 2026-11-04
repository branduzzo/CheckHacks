package com.veneer.core;

import org.bukkit.plugin.Plugin;

/**
 * Which PacketEvents Veneer runs on. The full jar bundles a relocated copy and starts it itself; the lite jar
 * bundles none and uses the PacketEvents plugin, which then owns the injection and its lifecycle.
 */
public final class PacketEventsHook {

    /** The shade relocation rewrites this to com.veneer.libs.* in the full jar; the lite jar keeps it as is. */
    private static final String API_CLASS = "com.github.retrooper.packetevents.PacketEvents";

    private PacketEventsHook() {}

    /** True in the full jar (own relocated PacketEvents), false in the lite jar. */
    public static boolean bundled() {
        return API_CLASS.startsWith("com.veneer.");
    }

    /** Whether PacketEvents classes can be used: always in the full jar, with the plugin enabled in the lite one. */
    public static boolean available(Plugin plugin) {
        return bundled() || plugin.getServer().getPluginManager().isPluginEnabled("packetevents");
    }
}
