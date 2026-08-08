package com.birditch.raidcore.core.service;

import com.birditch.raidcore.api.RaidException;

/// Thrown by [ServiceRegistry#register] when a service type is registered twice; the
/// message includes the type name.
public final class DuplicateServiceException extends RaidException {

  public DuplicateServiceException(String message) {
    super(message);
  }
}
