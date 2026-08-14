package com.birditch.escapefromcraftov.api.scheduler;

import java.time.Duration;

/// Scheduler abstraction for main-thread-confined domain code.
///
/// Threading contract:
/// - [#runSync] executes the task inline when already on the main thread,
///   otherwise submits it to run on the main thread.
/// - [#runLater] and [#runTimer] always fire their tasks on the main thread.
/// - [#runAsync] runs the task off the main thread; callbacks that touch
///   domain state must hop back via [#runSync].
public interface CraftovScheduler {

    boolean isMainThread();

    void runSync(Runnable task);

    void runAsync(Runnable task);

    ScheduledTask runLater(Duration delay, Runnable task);

    ScheduledTask runTimer(Duration initialDelay, Duration period, Runnable task);
}
