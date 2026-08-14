package com.birditch.escapefromcraftov.api.scheduler;

/// Handle to a task scheduled via [CraftovScheduler]; cancelling is idempotent.
public interface ScheduledTask {

    void cancel();

    boolean cancelled();
}
