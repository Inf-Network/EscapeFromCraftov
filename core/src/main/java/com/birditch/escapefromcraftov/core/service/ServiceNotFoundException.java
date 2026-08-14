package com.birditch.escapefromcraftov.core.service;

import com.birditch.escapefromcraftov.api.CraftovException;

/// Thrown by [ServiceRegistry#require] when no implementation is registered for the
/// requested type; the message includes the type name.
public final class ServiceNotFoundException extends CraftovException {

  public ServiceNotFoundException(String message) {
    super(message);
  }
}
