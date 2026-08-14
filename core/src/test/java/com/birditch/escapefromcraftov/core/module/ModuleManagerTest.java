package com.birditch.escapefromcraftov.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.testing.ManualScheduler;
import com.birditch.escapefromcraftov.common.lifecycle.LifecycleState;
import com.birditch.escapefromcraftov.core.CraftovContext;
import com.birditch.escapefromcraftov.core.config.EscapeFromCraftovConfig;
import com.birditch.escapefromcraftov.core.database.Database;
import com.birditch.escapefromcraftov.core.database.DatabaseConfig;
import com.birditch.escapefromcraftov.core.event.SimpleEventBus;
import com.birditch.escapefromcraftov.core.service.ServiceRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ModuleManagerTest {

  private final List<String> journal = new ArrayList<>();
  private final CapturingHandler logCapture = new CapturingHandler();
  private ExecutorService ioExecutor;
  private ModuleManager manager;

  @BeforeEach
  void setUp() {
    ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
    manager = new ModuleManager(quietLogger(logCapture));
  }

  @AfterEach
  void tearDown() {
    ioExecutor.shutdown();
  }

  @Test
  void modulesAndCountReflectRegistrationOrder() {
    RecordingModule first = module("alpha");
    RecordingModule second = module("beta");
    manager.register(first);
    manager.register(second);
    assertEquals(2, manager.count());
    assertEquals(List.of(first, second), manager.modules());
    assertEquals(LifecycleState.CREATED, manager.stateOf("alpha"));
  }

  @Test
  void modulesListIsUnmodifiable() {
    manager.register(module("alpha"));
    List<CraftovModule> modules = manager.modules();
    assertThrows(UnsupportedOperationException.class, modules::removeFirst);
  }

  @Test
  void duplicateModuleIdIsRejected() {
    manager.register(module("alpha"));
    RecordingModule duplicate = module("alpha");
    assertThrows(IllegalArgumentException.class, () -> manager.register(duplicate));
  }

  @Test
  void registrationAfterInitializationIsRejected() {
    manager.register(module("alpha"));
    manager.initializeAll(newContext());
    RecordingModule late = module("beta");
    assertThrows(IllegalStateException.class, () -> manager.register(late));
  }

  @Test
  void stateOfUnknownModuleThrows() {
    assertThrows(IllegalArgumentException.class, () -> manager.stateOf("ghost"));
  }

  @Nested
  @DisplayName("initializeAll and startAll")
  class InitializeAndStart {

    @Test
    void runInRegistrationOrderAndAdvanceStates() {
      manager.register(module("alpha"));
      manager.register(module("beta"));
      manager.initializeAll(newContext());
      assertEquals(List.of("alpha:init", "beta:init"), journal);
      assertEquals(LifecycleState.INITIALIZED, manager.stateOf("alpha"));
      assertEquals(LifecycleState.INITIALIZED, manager.stateOf("beta"));
      manager.startAll();
      assertEquals(List.of("alpha:init", "beta:init", "alpha:start", "beta:start"), journal);
      assertEquals(LifecycleState.RUNNING, manager.stateOf("alpha"));
      assertEquals(LifecycleState.RUNNING, manager.stateOf("beta"));
    }

    @Test
    void initializationFailureAbortsSequenceAndMarksFailed() {
      manager.register(module("alpha"));
      manager.register(module("beta").failingInit());
      manager.register(module("gamma"));
      CraftovContext context = newContext();
      ModuleInitializationException e =
          assertThrows(ModuleInitializationException.class, () -> manager.initializeAll(context));
      assertTrue(e.getMessage().contains("beta"), "message should name the module: " + e.getMessage());
      assertTrue(e.getCause() instanceof IllegalStateException, "cause should be preserved");
      assertEquals(List.of("alpha:init", "beta:init"), journal);
      assertEquals(LifecycleState.INITIALIZED, manager.stateOf("alpha"));
      assertEquals(LifecycleState.FAILED, manager.stateOf("beta"));
      assertEquals(LifecycleState.CREATED, manager.stateOf("gamma"));
    }

    @Test
    void startFailureAbortsSequenceMarksFailedAndRethrows() {
      manager.register(module("alpha"));
      IllegalStateException boom = new IllegalStateException("start boom");
      manager.register(module("beta").failingStart(boom));
      manager.register(module("gamma"));
      manager.initializeAll(newContext());
      RuntimeException e = assertThrows(IllegalStateException.class, manager::startAll);
      assertSame(boom, e);
      assertEquals(LifecycleState.RUNNING, manager.stateOf("alpha"));
      assertEquals(LifecycleState.FAILED, manager.stateOf("beta"));
      assertEquals(LifecycleState.INITIALIZED, manager.stateOf("gamma"));
    }
  }

  @Nested
  @DisplayName("stopAll")
  class StopAll {

    @Test
    void stopsInReverseRegistrationOrder() {
      manager.register(module("alpha"));
      manager.register(module("beta"));
      manager.register(module("gamma"));
      manager.initializeAll(newContext());
      manager.startAll();
      journal.clear();
      manager.stopAll();
      assertEquals(List.of("gamma:stop", "beta:stop", "alpha:stop"), journal);
      assertEquals(LifecycleState.STOPPED, manager.stateOf("alpha"));
      assertEquals(LifecycleState.STOPPED, manager.stateOf("beta"));
      assertEquals(LifecycleState.STOPPED, manager.stateOf("gamma"));
    }

    @Test
    void stopFailureIsLoggedSevereAndRemainingModulesStillStop() {
      manager.register(module("alpha"));
      manager.register(module("beta").failingStop());
      manager.register(module("gamma"));
      manager.initializeAll(newContext());
      manager.startAll();
      journal.clear();
      manager.stopAll();
      assertEquals(List.of("gamma:stop", "beta:stop", "alpha:stop"), journal);
      assertEquals(LifecycleState.STOPPED, manager.stateOf("gamma"));
      assertEquals(LifecycleState.FAILED, manager.stateOf("beta"));
      assertEquals(LifecycleState.STOPPED, manager.stateOf("alpha"));
      assertTrue(logCapture.hasSevereMentioning("beta"), "stop failure should be logged SEVERE");
    }
  }

  private RecordingModule module(String id) {
    return new RecordingModule(id, journal);
  }

  private CraftovContext newContext() {
    EscapeFromCraftovConfig config = new EscapeFromCraftovConfig(false,
        new EscapeFromCraftovConfig.GameSettings(12, 3, 1800, 4),
        new EscapeFromCraftovConfig.ExtractionSettings(10));
    DatabaseConfig databaseConfig = new DatabaseConfig(
        "localhost", 5432, "escapefromcraftov", "escapefromcraftov", "secret",
        new DatabaseConfig.PoolSettings(10, 2, 10_000L));
    return new CraftovContext(config, databaseConfig, new ServiceRegistry(),
        new SimpleEventBus(quietLogger(new CapturingHandler())), new ManualScheduler(),
        ioExecutor, new UnreachableDatabase(), quietLogger(new CapturingHandler()));
  }

  /// Modules under test never touch the database; failing loudly on contact
  /// keeps that assumption honest.
  private static final class UnreachableDatabase implements Database {

    @Override
    public <T> T transaction(java.util.function.Function<org.hibernate.Session, T> work) {
      throw new AssertionError("test context has no real database");
    }

    @Override
    public void close() {
    }
  }

  private static Logger quietLogger(Handler handler) {
    Logger logger = Logger.getAnonymousLogger();
    logger.setUseParentHandlers(false);
    logger.addHandler(handler);
    return logger;
  }

  private static final class CapturingHandler extends Handler {

    private final List<LogRecord> records = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    boolean hasSevereMentioning(String text) {
      return records.stream().anyMatch(record ->
          record.getLevel() == Level.SEVERE && record.getMessage().contains(text));
    }
  }

  private static final class RecordingModule implements CraftovModule {

    private final String id;
    private final List<String> journal;
    private boolean failInit;
    private RuntimeException startFailure;
    private boolean failStop;

    RecordingModule(String id, List<String> journal) {
      this.id = id;
      this.journal = journal;
    }

    RecordingModule failingInit() {
      failInit = true;
      return this;
    }

    RecordingModule failingStart(RuntimeException failure) {
      startFailure = failure;
      return this;
    }

    RecordingModule failingStop() {
      failStop = true;
      return this;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public void initialize(CraftovContext context) {
      journal.add(id + ":init");
      if (failInit) {
        throw new IllegalStateException("init boom: " + id);
      }
    }

    @Override
    public void start() {
      journal.add(id + ":start");
      if (startFailure != null) {
        throw startFailure;
      }
    }

    @Override
    public void stop() {
      journal.add(id + ":stop");
      if (failStop) {
        throw new IllegalStateException("stop boom: " + id);
      }
    }
  }
}
