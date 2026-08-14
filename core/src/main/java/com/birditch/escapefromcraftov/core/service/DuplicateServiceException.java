package com.birditch.escapefromcraftov.core.service;

import com.birditch.escapefromcraftov.api.CraftovException;

/// Thrown by [ServiceRegistry#register] when a service type is registered twice; the
/// message includes the type name.
public final class DuplicateServiceException extends CraftovException {

  public DuplicateServiceException(String message) {
    super(message);
  }
}
