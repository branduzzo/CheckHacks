package me.branduzzo.checkHacks.listeners;

import me.branduzzo.checkHacks.CheckHacksPlugin;
import me.branduzzo.checkHacks.HackDefinition;
import me.branduzzo.checkHacks.utils.FoliaScheduler;
import me.branduzzo.checkHacks.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

public class AntiCheatListener implements Listener {

    private final CheckHacksPlugin plugin;

    private static final String[] GRIM_CLASSES = {
            "ac.grim.grimac.api.events.FlagEvent",
            "ac.grim.grimac.events.FlagEvent"
    };
    private static final String VULCAN_CLASS  = "me.frep.vulcan.spigot.events.PlayerFlagEvent";
    private static final String SPARTAN_CLASS = "me.vagdedes.spartan.api.PlayerViolationEvent";
    private static final String MATRIX_CLASS  = "me.rerere.matrix.api.events.PlayerViolationEvent";

    public AntiCheatListener(CheckHacksPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerHooks() {
        if (!plugin.getConfigManager().isDetectFlagEnabled()) return;

        if (plugin.getConfigManager().isGrimEnabled()) {
            boolean ok = false;
            for (String cls : GRIM_CLASSES)
                if (tryRegister(cls, "Grim", true)) { ok = true; break; }
            if (!ok) plugin.getLogger().info("[CheckHacks] Grim not found, skipping.");
        }
        if (plugin.getConfigManager().isVulcanEnabled())
            if (!tryRegister(VULCAN_CLASS, "Vulcan", false))
                plugin.getLogger().info("[CheckHacks] Vulcan not found, skipping.");
        if (plugin.getConfigManager().isSpartanEnabled())
            if (!tryRegister(SPARTAN_CLASS, "Spartan", false))
                plugin.getLogger().info("[CheckHacks] Spartan not found, skipping.");
        if (plugin.getConfigManager().isMatrixEnabled())
            if (!tryRegister(MATRIX_CLASS, "Matrix", false))
                plugin.getLogger().info("[CheckHacks] Matrix not found, skipping.");
    }

    @SuppressWarnings("unchecked")
    private boolean tryRegister(String className, String acName, boolean isGrim) {
        try {
            Class<? extends Event> eventClass = (Class<? extends Event>) Class.forName(className);
            Bukkit.getPluginManager().registerEvent(
                    eventClass, this, EventPriority.NORMAL,
                    (l, e) -> handleFlag(e, isGrim, acName), plugin, false);
            plugin.getLogger().info("[CheckHacks] " + acName + " hook registered (" + className + ").");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            plugin.getLogger().warning("[CheckHacks] " + acName + " hook failed: " + e.getMessage());
            return false;
        }
    }

    private void handleFlag(Event event, boolean isGrim, String acName) {
        if (!plugin.getConfigManager().isDetectFlagEnabled()) return;
        Player player = extractPlayer(event, isGrim, acName);
        if (player == null) return;
        if (!plugin.getCheckManager().canAutoCheck(player.getUniqueId())) return;
        if (plugin.getCheckManager().isChecking(player.getUniqueId())) return;

        plugin.getLogger().info("[CheckHacks] " + acName + " flagged " + player.getName() + " — queuing check.");
        MessageUtil.broadcastAlerts(plugin,
                MessageUtil.parse(plugin, "anticheat-trigger", Map.of("player", player.getName())));

        List<HackDefinition> hacks = plugin.getConfigManager().getFlagCheckHacks();
        if (hacks.isEmpty()) return;

        FoliaScheduler.runAtEntity(plugin, player, () ->
                plugin.getCheckManager().startCheck(player, null, hacks, true,
                        "Anticheat flag: " + acName));
    }

    private Player extractPlayer(Event event, boolean isGrim, String acName) {
        Object raw = invokeNoArgs(event, "getPlayer");

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

        plugin.getLogger().warning("[CheckHacks] Could not extract player from event: "
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