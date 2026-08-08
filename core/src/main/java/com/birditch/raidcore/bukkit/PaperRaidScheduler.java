package com.birditch.raidcore.bukkit;

import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.api.scheduler.ScheduledTask;
import com.birditch.raidcore.common.check.Preconditions;
import java.time.Duration;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/// Bridges [RaidScheduler] onto the Bukkit scheduler. Durations are converted to
/// ticks as `max(1, millis / 50)`.
///
/// Defensive shutdown behavior: once the plugin is disabled the Bukkit scheduler
/// rejects new tasks, so [#runSync] executes inline on the calling thread and
/// [#runLater]/[#runTimer] return an inert, already-cancelled task instead of
/// throwing during teardown callbacks.
public final class PaperRaidScheduler implements RaidScheduler {

  private final Plugin plugin;

  public PaperRaidScheduler(Plugin plugin) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
  }

  @Override
  public boolean isMainThread() {
    return Bukkit.isPrimaryThread();
  }

  @Override
  public void runSync(Runnable task) {
    Preconditions.checkNotNull(task, "task");
    if (Bukkit.isPrimaryThread() || !plugin.isEnabled()) {
      task.run();
      return;
    }
    Bukkit.getScheduler().runTask(plugin, task);
  }

  @Override
  public void runAsync(Runnable task) {
    Preconditions.checkNotNull(task, "task");
    Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
  }

  @Override
  public ScheduledTask runLater(Duration delay, Runnable task) {
    Preconditions.checkNotNull(delay, "delay");
    Preconditions.checkNotNull(task, "task");
    if (!plugin.isEnabled()) {
      return InertTask.INSTANCE;
    }
    return new BukkitScheduledTask(
        Bukkit.getScheduler().runTaskLater(plugin, task, toTicks(delay)));
  }

  @Override
  public ScheduledTask runTimer(Duration initialDelay, Duration period, Runnable task) {
    Preconditions.checkNotNull(initialDelay, "initialDelay");
    Preconditions.checkNotNull(period, "period");
    Preconditions.checkNotNull(task, "task");
    if (!plugin.isEnabled()) {
      return InertTask.INSTANCE;
    }
    return new BukkitScheduledTask(
        Bukkit.getScheduler().runTaskTimer(plugin, task, toTicks(initialDelay), toTicks(period)));
  }

  private static long toTicks(Duration duration) {
    return Math.max(1L, duration.toMillis() / 50L);
  }

  private record BukkitScheduledTask(BukkitTask task) implements ScheduledTask {

    @Override
    public void cancel() {
      task.cancel();
    }

    @Override
    public boolean cancelled() {
      return task.isCancelled();
    }
  }

  private enum InertTask implements ScheduledTask {
    INSTANCE;

    @Override
    public void cancel() {}

    @Override
    public boolean cancelled() {
      return true;
    }
  }
}
