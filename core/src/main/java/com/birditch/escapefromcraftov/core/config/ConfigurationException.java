package com.birditch.escapefromcraftov.core.config;

import com.birditch.escapefromcraftov.api.CraftovException;

/// Thrown when configuration is invalid; validation aggregates every problem into a
/// single exception message so operators can fix all of them at once.
public final class ConfigurationException extends CraftovException {

  public ConfigurationException(String message) {
    super(message);
  }

  public ConfigurationException(String message, Throwable cause) {
    super(message, cause);
  }
}
