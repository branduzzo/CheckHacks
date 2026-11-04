package com.veneer.hacks.listeners;

import com.veneer.hacks.HacksModule;
import com.veneer.VeneerPlugin;
import com.veneer.hacks.HackDefinition;
import com.veneer.core.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

public class AntiCheatListener implements Listener {

    private final VeneerPlugin plugin;

    // Legacy Bukkit-event fallbacks for Grim (tried only when the event-bus path fails)
    private static final String[] GRIM_CLASSES = {
            "ac.grim.grimac.api.event.events.FlagEvent",   // modern API package
            "ac.grim.grimac.api.events.FlagEvent",         // older builds
            "ac.grim.grimac.events.FlagEvent"              // very old builds
    };
    private static final String[] VULCAN_CLASSES = {
            "me.frep.vulcan.api.event.VulcanFlagEvent",
            "me.frep.vulcan.spigot.events.PlayerFlagEvent"
    };
    private static final String[] SPARTAN_CLASSES = {
            "ai.idealistic.spartan.api.PlayerViolationEvent",
            "me.vagdedes.spartan.api.PlayerViolationEvent"
    };
    private static final String[] MATRIX_CLASSES  = {"me.rerere.matrix.api.events.PlayerViolationEvent"};
    private static final String[] INTAVE_CLASSES = {"de.jpx3.intave.access.check.event.IntaveViolationEvent"};
    private static final String[] AAC_CLASSES = {"me.konsolas.aac.api.PlayerViolationEvent"};
    private static final String[] THEMIS_CLASSES = {"me.activated.themis.api.event.ThemisFlagEvent"};

    public AntiCheatListener(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerHooks() {
        if (!plugin.module(HacksModule.class).config().isDetectFlagEnabled()) return;

        if (plugin.module(HacksModule.class).config().isAnticheatEnabled("grim")) {
            boolean ok = tryRegisterGrimBus();
            for (String cls : GRIM_CLASSES)
                if (!ok && tryRegister(cls, "Grim")) ok = true;
            if (!ok) plugin.getLogger().info("[Hacks] Grim not found, skipping.");
        }
        registerAny(plugin.module(HacksModule.class).config().isAnticheatEnabled("vulcan"), "Vulcan", VULCAN_CLASSES);
        registerAny(plugin.module(HacksModule.class).config().isAnticheatEnabled("spartan"), "Spartan", SPARTAN_CLASSES);
        registerAny(plugin.module(HacksModule.class).config().isAnticheatEnabled("matrix"), "Matrix", MATRIX_CLASSES);
        registerAny(plugin.module(HacksModule.class).config().isAnticheatEnabled("intave"), "Intave", INTAVE_CLASSES);
        registerAny(plugin.module(HacksModule.class).config().isAnticheatEnabled("aac"), "AAC", AAC_CLASSES);
        registerAny(plugin.module(HacksModule.class).config().isAnticheatEnabled("themis"), "Themis", THEMIS_CLASSES);
    }

    private void registerAny(boolean enabled, String acName, String[] classes) {
        if (!enabled) return;
        for (String cls : classes)
            if (tryRegister(cls, acName)) return;
        plugin.getLogger().info("[Hacks] " + acName + " not found, skipping.");
    }

    private boolean tryRegisterGrimBus() {
        if (Bukkit.getPluginManager().getPlugin("GrimAC") == null) return false;
        try {
            GrimHook.register(plugin, this);
            plugin.getLogger().info("[Hacks] Grim hook registered (event bus).");
            return true;
        } catch (Throwable t) {
            plugin.getLogger().info("[Hacks] Grim event bus unavailable (" + t.getClass().getSimpleName()
                    + "), trying legacy event classes.");
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean tryRegister(String className, String acName) {
        try {
            Class<? extends Event> eventClass = (Class<? extends Event>) Class.forName(className);
            Bukkit.getPluginManager().registerEvent(
                    eventClass, this, EventPriority.NORMAL,
                    (l, e) -> handleFlag(e, acName), plugin, false);
            plugin.getLogger().info("[Hacks] " + acName + " hook registered (" + className + ").");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            plugin.getLogger().warning("[Hacks] " + acName + " hook failed: " + e.getMessage());
            return false;
        }
    }

    private void handleFlag(Event event, String acName) {
        if (!plugin.module(HacksModule.class).config().isDetectFlagEnabled()) return;
        Player player = extractPlayer(event, acName);
        if (player == null) return;
        queueCheck(player, acName);
    }

    public void queueCheck(Player player, String acName) {
        if (!plugin.module(HacksModule.class).config().isDetectFlagEnabled()) return;
        if (plugin.bypasses(player, "hacks", false)) return;
        if (plugin.module(HacksModule.class).checks().isChecking(player.getUniqueId())) return;
        List<HackDefinition> hacks = plugin.module(HacksModule.class).config().getFlagCheckHacks();
        if (hacks.isEmpty()) return;
        // Anticheats flag many times a second: one alert and one scan per cooldown
        if (!plugin.module(HacksModule.class).checks().claimAutoCheck(player.getUniqueId())) return;

        plugin.getLogger().info("[Hacks] " + acName + " flagged " + player.getName() + " — queuing check.");
        plugin.alerts().send(m -> m.get("anticheat-trigger", Map.of("player", player.getName())));

        FoliaScheduler.runAtEntity(plugin, player, () ->
                plugin.module(HacksModule.class).checks().startCheck(player, null, hacks, true,
                        "Anticheat flag: " + acName));
    }

    private Player extractPlayer(Event event, String acName) {
        Object raw = invokeNoArgs(event, "getPlayer");
        if (raw == null) raw = invokeNoArgs(event, "player");

        if (raw instanceof Player p) return p;

        if (raw != null) {
            Object bukkit = invokeNoArgs(raw, "getBukkitPlayer");
            if (bukkit instanceof Player p) return p;

            Object nameObj = invokeNoArgs(raw, "getName");
            if (nameObj instanceof String name) {
                Player p = Bukkit.getPlayerExact(name);
                if (p != null) return p;
            }

            for (Method m : raw.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == Player.class) {
                    try {
                        Object result = m.invoke(raw);
                        if (result instanceof Player p) return p;
                    } catch (Exception ignored) {}
                }
            }
        }

        for (String methodName : new String[]{"getUser", "getViolator", "getViolation", "getTarget"}) {
            Object obj = invokeNoArgs(event, methodName);
            if (obj instanceof Player p) return p;
            if (obj != null) {
                Object bukkit = invokeNoArgs(obj, "getBukkitPlayer");
                if (bukkit instanceof Player p) return p;
                Object nameObj = invokeNoArgs(obj, "getName");
                if (nameObj instanceof String name) {
                    Player p = Bukkit.getPlayerExact(name);
                    if (p != null) return p;
                }
            }
        }

        plugin.getLogger().warning("[Hacks] Could not extract player from event: "
                + event.getClass().getName() + " (" + acName + ")");
        return null;
    }

    private Object invokeNoArgs(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }
}