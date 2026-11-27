package com.sandlotminecraft.teambridge;

/** The two Bukkit scheduler calls TeamBridge uses. A port, so tests can drive a fake main thread. */
public interface Scheduler {

    /** Runs the task on the main thread, later. Callable from any thread. */
    void runTask(Runnable task);

    /** Runs the task off the main thread, later. */
    void runTaskAsynchronously(Runnable task);
}
