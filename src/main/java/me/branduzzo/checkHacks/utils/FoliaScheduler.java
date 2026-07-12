package me.branduzzo.checkHacks.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Scheduler abstraction that auto-detects Folia and routes scheduling
 * calls to the appropriate Folia regionised schedulers, falling back to
 * the regular Bukkit scheduler on Paper/Spigot.
 *
 * Folia rules respected:
 *   - block / location work runs on the RegionScheduler at that location
 *   - per-entity work (packets, player state) runs on the entity scheduler
 *   - thread-unbound work (console dispatch, caches) runs on the GlobalRegionScheduler
 */
public final class FoliaScheduler {

    public static final boolean FOLIA;

    private static Object globalScheduler;
    private static Object regionScheduler;
    private static Object asyncScheduler;

    private static Method globalRun;
    private static Method globalRunDelayed;
    private static Method globalRunAtFixedRate;

    private static Method regionRun;
    private static Method regionRunDelayed;

    private static Method asyncRunNow;

    private static Method entityGetScheduler;
    private static Method entitySchedRun;
    private static Method entitySchedRunDelayed;

    private static Method scheduledTaskCancel;
    private static Method scheduledTaskIsCancelled;

    static {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            folia = false;
        }
        FOLIA = folia;
        if (FOLIA) initFoliaReflection();
    }

    private FoliaScheduler() {}

    public static boolean isFolia() { return FOLIA; }

    private static void initFoliaReflection() {
        try {
            Class<?> server = Bukkit.getServer().getClass();

            globalScheduler = server.getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
            Class<?> globalCls = globalScheduler.getClass();
            globalRun = findMethod(globalCls, "run", Plugin.class, Consumer.class);
            globalRunDelayed = findMethod(globalCls, "runDelayed", Plugin.class, Consumer.class, long.class);
            globalRunAtFixedRate = findMethod(globalCls, "runAtFixedRate",
                    Plugin.class, Consumer.class, long.class, long.class);

            regionScheduler = server.getMethod("getRegionScheduler").invoke(Bukkit.getServer());
            Class<?> regionCls = regionScheduler.getClass();
            regionRun = findMethod(regionCls, "run",
                    Plugin.class, org.bukkit.World.class, int.class, int.class, Consumer.class);
            regionRunDelayed = findMethod(regionCls, "runDelayed",
                    Plugin.class, org.bukkit.World.class, int.class, int.class, Consumer.class, long.class);

            asyncScheduler = server.getMethod("getAsyncScheduler").invoke(Bukkit.getServer());
            Class<?> asyncCls = asyncScheduler.getClass();
            asyncRunNow = findMethod(asyncCls, "runNow", Plugin.class, Consumer.class);

            entityGetScheduler = Entity.class.getMethod("getScheduler");
            Class<?> entitySchedCls = entityGetScheduler.getReturnType();
            entitySchedRun = findMethod(entitySchedCls, "run",
                    Plugin.class, Consumer.class, Runnable.class);
            entitySchedRunDelayed = findMethod(entitySchedCls, "runDelayed",
                    Plugin.class, Consumer.class, Runnable.class, long.class);

            Class<?> scheduledTask = Class.forName(
                    "io.papermc.paper.threadedregions.scheduler.ScheduledTask");
            scheduledTaskCancel = scheduledTask.getMethod("cancel");
            try {
                scheduledTaskIsCancelled = scheduledTask.getMethod("isCancelled");
            } catch (NoSuchMethodException ignored) {
                scheduledTaskIsCancelled = null;
            }
        } catch (Throwable t) {
            Bukkit.getLogger().severe("[CheckHacks] Failed to init Folia reflection: " + t);
        }
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... params)
            throws NoSuchMethodException {
        return c.getMethod(name, params);
    }

    public static WrappedTask runGlobal(Plugin plugin, Runnable task) {
        if (!FOLIA) return wrap(Bukkit.getScheduler().runTask(plugin, task));
        try {
            Object handle = globalRun.invoke(globalScheduler, plugin, asConsumer(task));
            return wrapFolia(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static WrappedTask runGlobalLater(Plugin plugin, Runnable task, long delayTicks) {
        if (delayTicks <= 0) return runGlobal(plugin, task);
        long delay = delayTicks;
        if (!FOLIA) return wrap(Bukkit.getScheduler().runTaskLater(plugin, task, delay));
        try {
            Object handle = globalRunDelayed.invoke(globalScheduler, plugin, asConsumer(task), delay);
            return wrapFolia(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static WrappedTask runGlobalTimer(Plugin plugin, Runnable task,
                                             long delayTicks, long periodTicks) {
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        if (!FOLIA) return wrap(Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period));
        try {
            Object handle = globalRunAtFixedRate.invoke(
                    globalScheduler, plugin, asConsumer(task), delay, period);
            return wrapFolia(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static WrappedTask runAtEntity(Plugin plugin, Entity entity, Runnable task) {
        if (!FOLIA) return wrap(Bukkit.getScheduler().runTask(plugin, task));
        try {
            Object sched = entityGetScheduler.invoke(entity);
            Object handle = entitySchedRun.invoke(sched, plugin, asConsumer(task), (Runnable) null);
            return wrapFolia(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static WrappedTask runAtEntityLater(Plugin plugin, Entity entity,
                                               Runnable task, long delayTicks) {
        if (delayTicks <= 0) return runAtEntity(plugin, entity, task);
        long delay = delayTicks;
        if (!FOLIA) return wrap(Bukkit.getScheduler().runTaskLater(plugin, task, delay));
        try {
            Object sched = entityGetScheduler.invoke(entity);
            Object handle = entitySchedRunDelayed.invoke(
                    sched, plugin, asConsumer(task), (Runnable) null, delay);
            return wrapFolia(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static WrappedTask runAtLocation(Plugin plugin, Location loc, Runnable task) {
        if (!FOLIA) return wrap(Bukkit.getScheduler().runTask(plugin, task));
        try {
            Object handle = regionRun.invoke(regionScheduler,
                    plugin, loc.getWorld(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4,
                    asConsumer(task));
            return wrapFolia(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static WrappedTask runAtLocationLater(Plugin plugin, Location loc,
                                                 Runnable task, long delayTicks) {
        if (delayTicks <= 0) return runAtLocation(plugin, loc, task);
        long delay = delayTicks;
        if (!FOLIA) return wrap(Bukkit.getScheduler().runTaskLater(plugin, task, delay));
        try {
            Object handle = regionRunDelayed.invoke(regionScheduler,
                    plugin, loc.getWorld(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4,
                    asConsumer(task), delay);
            return wrapFolia(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static WrappedTask runAsync(Plugin plugin, Runnable task) {
        if (!FOLIA) return wrap(Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
        try {
            Object handle = asyncRunNow.invoke(asyncScheduler, plugin, asConsumer(task));
            return wrapFolia(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Consumer asConsumer(Runnable task) {
        return (Consumer) o -> task.run();
    }

    private static WrappedTask wrap(BukkitTask t) {
        return new WrappedTask() {
            @Override public void cancel() { if (t != null) t.cancel(); }
            @Override public boolean isCancelled() { return t == null || t.isCancelled(); }
        };
    }

    private static WrappedTask wrapFolia(Object foliaTask) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        return new WrappedTask() {
            @Override
            public void cancel() {
                if (foliaTask == null || scheduledTaskCancel == null) return;
                try {
                    scheduledTaskCancel.invoke(foliaTask);
                    cancelled.set(true);
                } catch (Throwable ignored) {}
            }

            @Override
            public boolean isCancelled() {
                if (cancelled.get()) return true;
                if (foliaTask == null || scheduledTaskIsCancelled == null) return false;
                try {
                    Object v = scheduledTaskIsCancelled.invoke(foliaTask);
                    return v instanceof Boolean b && b;
                } catch (Throwable ignored) {
                    return false;
                }
            }
        };
    }
}
