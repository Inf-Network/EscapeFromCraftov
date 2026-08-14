package com.birditch.escapefromcraftov.core.database.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerItemEntityTest {

  @Test
  void legacyRowFallsBackToStorageIdentityAndHasNoPayload() {
    UUID storageId = UUID.randomUUID();
    PlayerItemEntity legacy = new PlayerItemEntity(storageId, UUID.randomUUID(),
        "emerald", 3, InventoryLocation.WAREHOUSE, null, Instant.EPOCH);

    assertNull(legacy.instanceId());
    assertEquals(storageId, legacy.instanceIdOrStorageId());
    assertNull(legacy.itemData());
  }

  @Test
  void newRowKeepsInstanceIdentitySeparateFromStorageIdentity() {
    UUID storageId = UUID.randomUUID();
    UUID instanceId = UUID.randomUUID();
    PlayerItemEntity current = new PlayerItemEntity(storageId, UUID.randomUUID(), instanceId,
        "iron_sword", 1, InventoryLocation.WAREHOUSE, null, "paper-itemstack:1:data",
        Instant.EPOCH);

    assertEquals(storageId, current.storageId());
    assertEquals(instanceId, current.instanceIdOrStorageId());
    assertEquals("paper-itemstack:1:data", current.itemData());
  }
}
