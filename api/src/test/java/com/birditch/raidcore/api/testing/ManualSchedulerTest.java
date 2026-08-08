package com.birditch.raidcore.api.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.api.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ManualSchedulerTest {

    private ManualScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ManualScheduler();
    }

    @Test
    void reportsMainThreadByDefaultAndCanBeToggled() {
        assertTrue(scheduler.isMainThread());
        scheduler.setMainThread(false);
        assertFalse(scheduler.isMainThread());
    }

    @Test
    void runSyncAndRunAsyncExecuteInline() {
        AtomicInteger runs = new AtomicInteger();
        scheduler.runSync(runs::incrementAndGet);
        scheduler.runAsync(runs::incrementAndGet);
        assertEquals(2, runs.get());
    }

    @Nested
    @DisplayName("runLater")
    class RunLater {

        @Test
        void doesNotFireBeforeTheDelayElapsed() {
            AtomicInteger runs = new AtomicInteger();
            scheduler.runLater(Duration.ofSeconds(5), runs::incrementAndGet);
            scheduler.advanceBy(Duration.ofSeconds(4));
            assertEquals(0, runs.get());
            assertEquals(1, scheduler.pendingCount());
        }

        @Test
        void firesExactlyOnceWhenAdvancedPastTheDelay() {
            AtomicInteger runs = new AtomicInteger();
            scheduler.runLater(Duration.ofSeconds(5), runs::incrementAndGet);
            scheduler.advanceBy(Duration.ofSeconds(5));
            assertEquals(1, runs.get());
            assertEquals(0, scheduler.pendingCount());
            scheduler.advanceBy(Duration.ofMinutes(1));
            assertEquals(1, runs.get());
        }

        @Test
        void dueTasksRunInChronologicalOrder() {
            List<String> order = new ArrayList<>();
            scheduler.runLater(Duration.ofSeconds(2), () -> order.add("second"));
            scheduler.runLater(Duration.ofSeconds(1), () -> order.add("first"));
            scheduler.advanceBy(Duration.ofSeconds(3));
            assertEquals(List.of("first", "second"), order);
        }

        @Test
        void tasksScheduledWhileAdvancingRunInsideTheSameWindow() {
            List<String> order = new ArrayList<>();
            scheduler.runLater(Duration.ofSeconds(1), () -> {
                order.add("outer");
                scheduler.runLater(Duration.ofSeconds(1), () -> order.add("inner"));
            });
            scheduler.advanceBy(Duration.ofSeconds(3));
            assertEquals(List.of("outer", "inner"), order);
        }

        @Test
        void cancelledTaskNeverRuns() {
            AtomicInteger runs = new AtomicInteger();
            ScheduledTask task = scheduler.runLater(Duration.ofSeconds(1), runs::incrementAndGet);
            assertFalse(task.cancelled());
            task.cancel();
            assertTrue(task.cancelled());
            assertEquals(0, scheduler.pendingCount());
            scheduler.advanceBy(Duration.ofSeconds(2));
            assertEquals(0, runs.get());
        }

        @Test
        void rejectsNegativeDelay() {
            Runnable task = () -> {
            };
            assertThrows(IllegalArgumentException.class,
                    () -> scheduler.runLater(Duration.ofSeconds(-1), task));
        }
    }

    @Nested
    @DisplayName("runTimer")
    class RunTimer {

        @Test
        void firesAfterTheInitialDelayAndRepeatsEveryPeriod() {
            AtomicInteger runs = new AtomicInteger();
            scheduler.runTimer(Duration.ofSeconds(2), Duration.ofSeconds(3), runs::incrementAndGet);
            scheduler.advanceBy(Duration.ofSeconds(1));
            assertEquals(0, runs.get());
            scheduler.advanceBy(Duration.ofSeconds(1));
            assertEquals(1, runs.get());
            scheduler.advanceBy(Duration.ofSeconds(9));
            assertEquals(4, runs.get());
            assertEquals(1, scheduler.pendingCount());
        }

        @Test
        void cancelDuringItsOwnRunStopsFutureFires() {
            AtomicInteger runs = new AtomicInteger();
            ScheduledTask[] handle = new ScheduledTask[1];
            handle[0] = scheduler.runTimer(Duration.ofSeconds(1), Duration.ofSeconds(1), () -> {
                runs.incrementAndGet();
                handle[0].cancel();
            });
            scheduler.advanceBy(Duration.ofSeconds(10));
            assertEquals(1, runs.get());
            assertEquals(0, scheduler.pendingCount());
        }

        @Test
        void cancelBetweenFiresStopsTheTimer() {
            AtomicInteger runs = new AtomicInteger();
            ScheduledTask task =
                    scheduler.runTimer(Duration.ofSeconds(1), Duration.ofSeconds(1), runs::incrementAndGet);
            scheduler.advanceBy(Duration.ofSeconds(1));
            assertEquals(1, runs.get());
            task.cancel();
            scheduler.advanceBy(Duration.ofSeconds(10));
            assertEquals(1, runs.get());
        }

        @Test
        void rejectsNonPositivePeriod() {
            Runnable task = () -> {
            };
            assertThrows(IllegalArgumentException.class,
                    () -> scheduler.runTimer(Duration.ZERO, Duration.ZERO, task));
        }
    }

    @Test
    void pendingCountTracksScheduledNotYetRunTasks() {
        assertEquals(0, scheduler.pendingCount());
        scheduler.runLater(Duration.ofSeconds(1), () -> {
        });
        scheduler.runTimer(Duration.ofSeconds(2), Duration.ofSeconds(2), () -> {
        });
        assertEquals(2, scheduler.pendingCount());
    }
}
