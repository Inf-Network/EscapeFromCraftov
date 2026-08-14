package com.birditch.escapefromcraftov.core.module;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.lifecycle.LifecycleState;
import com.birditch.escapefromcraftov.core.CraftovContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Drives the [CraftovModule] lifecycle in registration order (reverse order for stop).
///
/// Main-thread confined; not thread-safe. Initialization and start failures abort the
/// sequence, stop failures are logged at [Level#SEVERE] and never propagate so every
/// module gets a shutdown chance.
public final class ModuleManager {

  private final Logger logger;
  private final SequencedMap<String, CraftovModule> modules = new LinkedHashMap<>();
  private final Map<String, LifecycleState> states = new LinkedHashMap<>();
  private boolean initializationStarted;

  public ModuleManager(Logger logger) {
    this.logger = Preconditions.checkNotNull(logger, "logger");
  }

  /// Registers a module; only allowed before [#initializeAll] has been called.
  ///
  /// @throws IllegalStateException if initialization has already started
  /// @throws IllegalArgumentException if a module with the same id is registered
  public void register(CraftovModule module) {
    Preconditions.checkNotNull(module, "module");
    if (initializationStarted) {
      throw new IllegalStateException("Cannot register modules after initialization has started");
    }
    String id = Preconditions.checkNotNull(module.id(), "module id");
    if (modules.containsKey(id)) {
      throw new IllegalArgumentException("Module already registered: " + id);
    }
    modules.put(id, module);
    states.put(id, LifecycleState.CREATED);
  }

  /// Initializes all modules in registration order. The first failure marks that
  /// module [LifecycleState#FAILED] and aborts the sequence.
  ///
  /// @throws ModuleInitializationException if a module fails to initialize
  public void initializeAll(CraftovContext context) {
    Preconditions.checkNotNull(context, "context");
    initializationStarted = true;
    for (CraftovModule module : modules.values()) {
      states.put(module.id(), LifecycleState.INITIALIZING);
      try {
        module.initialize(context);
      } catch (RuntimeException e) {
        states.put(module.id(), LifecycleState.FAILED);
        throw new ModuleInitializationException(module.id(), e);
      }
      states.put(module.id(), LifecycleState.INITIALIZED);
      logger.fine(() -> "Initialized module: " + module.id());
    }
  }

  /// Starts all modules in registration order. The first failure marks that module
  /// [LifecycleState#FAILED] and rethrows.
  public void startAll() {
    for (CraftovModule module : modules.values()) {
      states.put(module.id(), LifecycleState.STARTING);
      try {
        module.start();
      } catch (RuntimeException e) {
        states.put(module.id(), LifecycleState.FAILED);
        throw e;
      }
      states.put(module.id(), LifecycleState.RUNNING);
      logger.fine(() -> "Started module: " + module.id());
    }
  }

  /// Stops all modules in reverse registration order. Never throws: each failure is
  /// logged at [Level#SEVERE] and the remaining modules still stop.
  public void stopAll() {
    for (CraftovModule module : modules.sequencedValues().reversed()) {
      states.put(module.id(), LifecycleState.STOPPING);
      try {
        module.stop();
        states.put(module.id(), LifecycleState.STOPPED);
      } catch (RuntimeException e) {
        states.put(module.id(), LifecycleState.FAILED);
        logger.log(Level.SEVERE, "Failed to stop module: " + module.id(), e);
      }
    }
  }

  /// Returns the registered modules in registration order as an unmodifiable list.
  public List<CraftovModule> modules() {
    return List.copyOf(modules.values());
  }

  /// @throws IllegalArgumentException if no module with `moduleId` is registered
  public LifecycleState stateOf(String moduleId) {
    Preconditions.checkNotNull(moduleId, "moduleId");
    LifecycleState state = states.get(moduleId);
    if (state == null) {
      throw new IllegalArgumentException("Unknown module: " + moduleId);
    }
    return state;
  }

  public int count() {
    return modules.size();
  }
}
