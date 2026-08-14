package com.birditch.escapefromcraftov.core.service;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// Type-keyed registry of service implementations, preserving registration order.
///
/// Not thread-safe: registration happens during bootstrap and every access is
/// main-thread confined.
public final class ServiceRegistry {

  private final Map<Class<?>, Object> services = new LinkedHashMap<>();

  /// Registers `implementation` under `type`.
  ///
  /// @throws DuplicateServiceException if `type` is already registered
  public <T> void register(Class<T> type, T implementation) {
    Preconditions.checkNotNull(type, "type");
    Preconditions.checkNotNull(implementation, "implementation");
    if (services.containsKey(type)) {
      throw new DuplicateServiceException("Service already registered: " + type.getName());
    }
    services.put(type, implementation);
  }

  /// Returns the implementation registered under `type`.
  ///
  /// @throws ServiceNotFoundException if `type` is not registered
  public <T> T require(Class<T> type) {
    Preconditions.checkNotNull(type, "type");
    Object implementation = services.get(type);
    if (implementation == null) {
      throw new ServiceNotFoundException("Service not found: " + type.getName());
    }
    return type.cast(implementation);
  }

  public <T> Optional<T> find(Class<T> type) {
    Preconditions.checkNotNull(type, "type");
    return Optional.ofNullable(services.get(type)).map(type::cast);
  }

  /// Returns an unmodifiable view of the registered types in registration order.
  public Set<Class<?>> registeredTypes() {
    return Collections.unmodifiableSet(services.keySet());
  }

  public int size() {
    return services.size();
  }

  public void clear() {
    services.clear();
  }
}
