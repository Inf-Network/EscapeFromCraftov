package com.birditch.raidcore.api.testing;

import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.api.scheduler.ScheduledTask;
import com.birditch.raidcore.common.check.Preconditions;
import java.time.Duration;
import java.util.Comparator;
import java.util.PriorityQueue;

/// Deterministic [RaidScheduler] double with a manually advanced millisecond
/// clock starting at 0.
///
/// `runSync`/`runAsync` execute inline. `runLater`/`runTimer` enqueue until
/// [#advanceBy] moves the clock past their due time; due tasks run in
/// chronological order and timers re-fire every period within the window.
public final class ManualScheduler implements RaidScheduler {

    private static final Comparator<PendingTask> ORDER =
            Comparator.comparingLong(PendingTask::dueAtMillis)
                    .thenComparingLong(PendingTask::sequence);

    private final PriorityQueue<PendingTask> queue = new PriorityQueue<>(ORDER);
    private long nowMillis;
    private long nextSequence;
    private boolean mainThread = true;

    @Override
    public boolean isMainThread() {
        return mainThread;
    }

    /// Controls what [#isMainThread] reports; defaults to `true`.
    public void setMainThread(boolean mainThread) {
        this.mainThread = mainThread;
    }

    @Override
    public void runSync(Runnable task) {
        Preconditions.checkNotNull(task, "task");
        task.run();
    }

    @Override
    public void runAsync(Runnable task) {
        Preconditions.checkNotNull(task, "task");
        task.run();
    }

    @Override
    public ScheduledTask runLater(Duration delay, Runnable task) {
        Preconditions.checkNotNull(delay, "delay");
        Preconditions.checkArgument(!delay.isNegative(), "delay must not be negative");
        Preconditions.checkNotNull(task, "task");
        return enqueue(Math.addExact(nowMillis, delay.toMillis()), -1L, task);
    }

    @Override
    public ScheduledTask runTimer(Duration initialDelay, Duration period, Runnable task) {
        Preconditions.checkNotNull(initialDelay, "initialDelay");
        Preconditions.checkArgument(!initialDelay.isNegative(), "initialDelay must not be negative");
        Preconditions.checkNotNull(period, "period");
        Preconditions.checkArgument(period.isPositive(), "period must be positive");
        Preconditions.checkNotNull(task, "task");
        return enqueue(Math.addExact(nowMillis, initialDelay.toMillis()), period.toMillis(), task);
    }

    /// Advances the clock by `d`, running every task due within the window in
    /// chronological order. Tasks scheduled while advancing also run when they
    /// fall inside the window.
    public void advanceBy(Duration d) {
        Preconditions.checkNotNull(d, "d");
        Preconditions.checkArgument(!d.isNegative(), "duration must not be negative");
        long target = Math.addExact(nowMillis, d.toMillis());
        while (true) {
            PendingTask head = queue.peek();
            if (head == null || head.dueAtMillis() > target) {
                break;
            }
            queue.poll();
            if (head.handle().cancelled()) {
                continue;
            }
            nowMillis = head.dueAtMillis();
            head.task().run();
            if (head.periodMillis() > 0 && !head.handle().cancelled()) {
                queue.add(new PendingTask(head.dueAtMillis() + head.periodMillis(),
                        head.periodMillis(), nextSequence++, head.task(), head.handle()));
            }
        }
        nowMillis = target;
    }

    /// Number of scheduled, not-yet-run, not-cancelled tasks.
    public int pendingCount() {
        return (int) queue.stream().filter(pending -> !pending.handle().cancelled()).count();
    }

    private ScheduledTask enqueue(long dueAtMillis, long periodMillis, Runnable task) {
        ManualTask handle = new ManualTask();
        queue.add(new PendingTask(dueAtMillis, periodMillis, nextSequence++, task, handle));
        return handle;
    }

    private static final class ManualTask implements ScheduledTask {

        private boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }
    }

    private record PendingTask(long dueAtMillis, long periodMillis, long sequence,
                               Runnable task, ManualTask handle) {
    }
}
