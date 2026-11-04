package com.veneer.core;

/**
 * Common abstraction over Bukkit's {@code BukkitTask} and Folia's
 * {@code ScheduledTask}, so the rest of the plugin can hold scheduler
 * handles without caring which platform produced them.
 */
public interface WrappedTask {
    void cancel();
    boolean isCancelled();
}
