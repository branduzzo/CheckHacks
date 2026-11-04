package com.veneer.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
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

    /** Resolved lazily on first scheduling call: true only when Folia is present AND reflection init worked.
     *  Lazy because {@code Bukkit.getServer()} cannot be touched from a static initialiser. */
    private static volatile Boolean folia;

    private static Method globalRun;
    private static Method globalRunDelayed;
    private static Method globalRunAtFixedRate;

    private static Method regionRun;
    private static Method regionRunDelayed;

    private static Method asyncRunNow;
    private static Method asyncRunDelayed;

    private static Method entityGetScheduler;
    private static Method entitySchedRun;
    private static Method entitySchedRunDelayed;

    private static Method scheduledTaskCancel;
    private static Method scheduledTaskIsCancelled;

    private FoliaScheduler() {}

    /** True when running on Folia with all reflective handles resolved; otherwise the Bukkit path is used. */
    public static boolean isFolia() {
        Boolean f = folia;
        if (f == null) { initFolia(); f = folia; }
        return f;
    }

    private static synchronized void initFolia() {
        if (folia != null) return;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
        } catch (ClassNotFoundException e) {
            folia = false;  // not Folia at all: plain Bukkit scheduler
            return;
        }
        try {
            initFoliaReflection();
            folia = true;
        } catch (Throwable t) {
            Bukkit.getLogger().severe("[Veneer] Folia reflection init failed, falling back to the Bukkit scheduler: " + t);
            folia = false;
        }
    }

    private static void initFoliaReflection() throws Exception {
        Class<?> server = Bukkit.getServer().getClass();

        Object global = server.getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
        Class<?> globalCls = global.getClass();
        globalRun            = findMethod(globalCls, "run",            Plugin.class, Consumer.class);
        globalRunDelayed     = findMethod(globalCls, "runDelayed",     Plugin.class, Consumer.class, long.class);
        globalRunAtFixedRate = findMethod(globalCls, "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);

        Object region = server.getMethod("getRegionScheduler").invoke(Bukkit.getServer());
        Class<?> regionCls = region.getClass();
        regionRun        = findMethod(regionCls, "run",        Plugin.class, org.bukkit.World.class, int.class, int.class, Consumer.class);
        regionRunDelayed = findMethod(regionCls, "runDelayed", Plugin.class, org.bukkit.World.class, int.class, int.class, Consumer.class, long.class);

        Object async = server.getMethod("getAsyncScheduler").invoke(Bukkit.getServer());
        Class<?> asyncCls = async.getClass();
        asyncRunNow     = findMethod(asyncCls, "runNow",     Plugin.class, Consumer.class);
        asyncRunDelayed = findMethod(asyncCls, "runDelayed", Plugin.class, Consumer.class, long.class, java.util.concurrent.TimeUnit.class);

        entityGetScheduler = Entity.class.getMethod("getScheduler");
        Class<?> entitySchedCls = entityGetScheduler.getReturnType();
        entitySchedRun        = findMethod(entitySchedCls, "run",        Plugin.class, Consumer.class, Runnable.class);
        entitySchedRunDelayed = findMethod(entitySchedCls, "runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);

        Class<?> scheduledTask = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
        scheduledTaskCancel      = scheduledTask.getMethod("cancel");
        scheduledTaskIsCancelled = scheduledTask.getMethod("isCancelled");
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... params) throws NoSuchMethodException {
        return c.getMethod(name, params);
    }

    /* ===========================================================
     *                       Global / main
     * =========================================================== */

    public static WrappedTask runGlobal(Plugin plugin, Runnable task) {
        if (!isFolia()) return wrap(Bukkit.getScheduler().runTask(plugin, task));
        try {
            Object handle = globalRun.invoke(getGlobalScheduler(), plugin, asConsumer(task));
            return wrap(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static WrappedTask runGlobalLater(Plugin plugin, Runnable task, long delayTicks) {
        long delay = Math.max(1L, delayTicks);
        if (!isFolia()) return wrap(Bukkit.getScheduler().runTaskLater(plugin, task, delay));
        try {
            Object handle = globalRunDelayed.invoke(getGlobalScheduler(), plugin, asConsumer(task), delay);
            return wrap(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static WrappedTask runGlobalTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        long delay  = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        if (!isFolia()) return wrap(Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period));
        try {
            Object handle = globalRunAtFixedRate.invoke(getGlobalScheduler(), plugin, asConsumer(task), delay, period);
            return wrap(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /* ===========================================================
     *                       Entity-bound
     * =========================================================== */

    public static WrappedTask runAtEntity(Plugin plugin, Entity entity, Runnable task) {
        if (!isFolia()) return wrap(Bukkit.getScheduler().runTask(plugin, task));
        try {
            Object sched = entityGetScheduler.invoke(entity);
            Object handle = entitySchedRun.invoke(sched, plugin, asConsumer(task), (Runnable) null);
            return wrap(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static WrappedTask runAtEntityLater(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        long delay = Math.max(1L, delayTicks);
        if (!isFolia()) return wrap(Bukkit.getScheduler().runTaskLater(plugin, task, delay));
        try {
            Object sched = entityGetScheduler.invoke(entity);
            Object handle = entitySchedRunDelayed.invoke(sched, plugin, asConsumer(task), (Runnable) null, delay);
            return wrap(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /* ===========================================================
     *                      Location / region
     * =========================================================== */

    public static WrappedTask runAtLocation(Plugin plugin, Location loc, Runnable task) {
        if (!isFolia()) return wrap(Bukkit.getScheduler().runTask(plugin, task));
        try {
            Object handle = regionRun.invoke(getRegionScheduler(),
                    plugin, loc.getWorld(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4, asConsumer(task));
            return wrap(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static WrappedTask runAtLocationLater(Plugin plugin, Location loc, Runnable task, long delayTicks) {
        long delay = Math.max(1L, delayTicks);
        if (!isFolia()) return wrap(Bukkit.getScheduler().runTaskLater(plugin, task, delay));
        try {
            Object handle = regionRunDelayed.invoke(getRegionScheduler(),
                    plugin, loc.getWorld(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4, asConsumer(task), delay);
            return wrap(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /* ===========================================================
     *                            Async
     * =========================================================== */

    public static WrappedTask runAsync(Plugin plugin, Runnable task) {
        if (!isFolia()) return wrap(Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
        try {
            Object handle = asyncRunNow.invoke(getAsyncScheduler(), plugin, asConsumer(task));
            return wrap(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static WrappedTask runAsyncLater(Plugin plugin, Runnable task, long delayTicks) {
        if (!isFolia()) return wrap(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks));
        try {
            Object handle = asyncRunDelayed.invoke(getAsyncScheduler(), plugin, asConsumer(task),
                    Math.max(1, delayTicks) * 50L, java.util.concurrent.TimeUnit.MILLISECONDS);
            return wrap(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /* ===========================================================
     *                          internals
     * =========================================================== */

    private static Object getGlobalScheduler() throws Exception {
        return Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
    }

    private static Object getRegionScheduler() throws Exception {
        return Bukkit.getServer().getClass().getMethod("getRegionScheduler").invoke(Bukkit.getServer());
    }

    private static Object getAsyncScheduler() throws Exception {
        return Bukkit.getServer().getClass().getMethod("getAsyncScheduler").invoke(Bukkit.getServer());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Consumer asConsumer(Runnable task) {
        return (Consumer) o -> task.run();
    }

    private static WrappedTask wrap(BukkitTask t) {
        return new WrappedTask() {
            @Override public void cancel()       { if (t != null) t.cancel(); }
            @Override public boolean isCancelled() { return t == null || t.isCancelled(); }
        };
    }

    private static WrappedTask wrap(Object foliaTask) {
        return new WrappedTask() {
            private volatile boolean cancelled;
            @Override public void cancel() {
                if (foliaTask == null || scheduledTaskCancel == null) return;
                cancelled = true;
                try { scheduledTaskCancel.invoke(foliaTask); }
                catch (Throwable t) { Bukkit.getLogger().warning("[Veneer] Failed to cancel Folia task: " + t); }
            }
            @Override public boolean isCancelled() {
                if (cancelled) return true;
                if (foliaTask == null || scheduledTaskIsCancelled == null) return false;
                try { return (boolean) scheduledTaskIsCancelled.invoke(foliaTask); }
                catch (Throwable t) { return false; }
            }
        };
    }
}
