package com.birditch.raidcore.core.config;

import com.birditch.raidcore.api.RaidException;

/// Thrown when configuration is invalid; validation aggregates every problem into a
/// single exception message so operators can fix all of them at once.
public final class ConfigurationException extends RaidException {

  public ConfigurationException(String message) {
    super(message);
  }
}
