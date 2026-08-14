package com.birditch.escapefromcraftov.bukkit;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Guarded teardown helper shared by both plugin applications: every step
/// failure is logged and the remaining steps still run, so one broken resource
/// can never leak the rest.
public final class ShutdownSteps {

  private static final long EXECUTOR_GRACE_SECONDS = 5;

  private final Logger logger;

  public ShutdownSteps(Logger logger) {
    this.logger = Preconditions.checkNotNull(logger, "logger");
  }

  public void run(String step, Step action) {
    Preconditions.checkNotNull(step, "step");
    Preconditions.checkNotNull(action, "action");
    try {
      action.run();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.log(Level.SEVERE, "Shutdown step interrupted: " + step, e);
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Shutdown step failed: " + step, e);
    }
  }

  /// Orderly executor shutdown with a short grace period, then force.
  public void shutdownExecutor(String step, ExecutorService executor) {
    run(step, () -> {
      executor.shutdown();
      if (!executor.awaitTermination(EXECUTOR_GRACE_SECONDS, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    });
  }

  @FunctionalInterface
  public interface Step {
    void run() throws Exception;
  }
}
