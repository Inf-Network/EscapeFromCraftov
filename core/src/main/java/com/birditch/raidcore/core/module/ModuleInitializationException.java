package com.birditch.raidcore.core.module;

import com.birditch.raidcore.api.RaidException;

/// Thrown by [ModuleManager#initializeAll] when a module fails to initialize; aborts
/// the remaining initialization sequence.
public final class ModuleInitializationException extends RaidException {

  public ModuleInitializationException(String moduleId, Throwable cause) {
    super("Failed to initialize module: " + moduleId, cause);
  }
}
