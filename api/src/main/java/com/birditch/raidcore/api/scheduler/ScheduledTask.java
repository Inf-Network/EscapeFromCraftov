package com.birditch.raidcore.api.scheduler;

/// Handle to a task scheduled via [RaidScheduler]; cancelling is idempotent.
public interface ScheduledTask {

    void cancel();

    boolean cancelled();
}
