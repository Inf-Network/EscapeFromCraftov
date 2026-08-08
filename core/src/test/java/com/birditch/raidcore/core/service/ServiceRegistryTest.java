package com.birditch.raidcore.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ServiceRegistryTest {

  private interface Alpha {}

  private interface Beta {}

  private ServiceRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new ServiceRegistry();
  }

  @Test
  void requireReturnsTheRegisteredInstance() {
    Alpha alpha = new Alpha() {};
    registry.register(Alpha.class, alpha);
    assertSame(alpha, registry.require(Alpha.class));
  }

  @Test
  void registeringTheSameTypeTwiceThrowsDuplicateServiceException() {
    registry.register(Alpha.class, new Alpha() {});
    Alpha again = new Alpha() {};
    DuplicateServiceException e = assertThrows(DuplicateServiceException.class,
        () -> registry.register(Alpha.class, again));
    assertTrue(e.getMessage().contains(Alpha.class.getName()),
        "message should name the type: " + e.getMessage());
  }

  @Test
  void requireUnknownTypeThrowsServiceNotFoundExceptionNamingTheType() {
    ServiceNotFoundException e =
        assertThrows(ServiceNotFoundException.class, () -> registry.require(Beta.class));
    assertTrue(e.getMessage().contains(Beta.class.getName()),
        "message should name the type: " + e.getMessage());
  }

  @Nested
  @DisplayName("find")
  class Find {

    @Test
    void returnsTheInstanceWhenRegistered() {
      Alpha alpha = new Alpha() {};
      registry.register(Alpha.class, alpha);
      assertEquals(Optional.of(alpha), registry.find(Alpha.class));
    }

    @Test
    void returnsEmptyWhenAbsent() {
      assertEquals(Optional.empty(), registry.find(Alpha.class));
    }
  }

  @Test
  void registeredTypesPreserveRegistrationOrder() {
    registry.register(Beta.class, new Beta() {});
    registry.register(Alpha.class, new Alpha() {});
    assertEquals(List.of(Beta.class, Alpha.class), List.copyOf(registry.registeredTypes()));
  }

  @Test
  void registeredTypesViewIsUnmodifiable() {
    registry.register(Alpha.class, new Alpha() {});
    Set<Class<?>> types = registry.registeredTypes();
    assertThrows(UnsupportedOperationException.class, () -> types.remove(Alpha.class));
  }

  @Test
  void clearRemovesEverything() {
    registry.register(Alpha.class, new Alpha() {});
    registry.register(Beta.class, new Beta() {});
    assertEquals(2, registry.size());
    registry.clear();
    assertEquals(0, registry.size());
    assertEquals(Optional.empty(), registry.find(Alpha.class));
  }

  @Test
  void rejectsNullTypeOrImplementation() {
    Alpha alpha = new Alpha() {};
    assertThrows(NullPointerException.class, () -> registry.register(null, alpha));
    assertThrows(NullPointerException.class, () -> registry.register(Alpha.class, null));
  }
}
