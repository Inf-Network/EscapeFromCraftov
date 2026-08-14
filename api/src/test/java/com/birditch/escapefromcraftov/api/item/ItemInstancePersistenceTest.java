package com.birditch.escapefromcraftov.api.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ItemInstancePersistenceTest {

  @Test
  void stableIdentityAndOpaquePayloadSurviveDomainRoundTrip() {
    UUID instanceId = UUID.randomUUID();
    ItemInstance before = new ItemInstance(
        instanceId, ItemId.of("iron_sword"), 1, "adapter:v1:opaque-data");

    ItemInstance after = new ItemInstance(before.instanceId(), before.itemId(),
        before.quantity(), before.opaquePayload());

    assertEquals(instanceId, after.instanceId());
    assertEquals("adapter:v1:opaque-data", after.payload().orElseThrow());
  }

  @Test
  void compatibilityConstructorRepresentsDefinitionOnlyItem() {
    ItemInstance instance = new ItemInstance(
        UUID.randomUUID(), ItemId.of("emerald"), 4);

    assertTrue(instance.payload().isEmpty());
  }
}
