package com.birditch.raidcore.core.service;

import com.birditch.raidcore.api.RaidException;

/// Thrown by [ServiceRegistry#require] when no implementation is registered for the
/// requested type; the message includes the type name.
public final class ServiceNotFoundException extends RaidException {

  public ServiceNotFoundException(String message) {
    super(message);
  }
}
