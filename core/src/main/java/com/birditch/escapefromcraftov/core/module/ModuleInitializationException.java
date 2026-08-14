package com.birditch.escapefromcraftov.core.module;

import com.birditch.escapefromcraftov.api.CraftovException;

/// Thrown by [ModuleManager#initializeAll] when a module fails to initialize; aborts
/// the remaining initialization sequence.
public final class ModuleInitializationException extends CraftovException {

  public ModuleInitializationException(String moduleId, Throwable cause) {
    super("Failed to initialize module: " + moduleId, cause);
  }
}
