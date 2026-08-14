package com.birditch.escapefromcraftov.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.core.database.entity.PlayerItemEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HibernatePlayerInventoryServiceTest {

  @Test
  void sameCraftovPhysicalItemCanTransferFromOriginalOwnerToExtractor() {
    UUID originalOwner = UUID.randomUUID();
    UUID extractor = UUID.randomUUID();
    UUID instanceId = UUID.randomUUID();
    PlayerItemEntity source = row(originalOwner, instanceId,
        InventoryLocation.CRAFTOV, "game-1");

    List<PlayerItemEntity> removals = HibernatePlayerInventoryService.settlementRowsToRemove(
        extractor, "game-1", carried(instanceId, "emerald"), List.of(source));

    assertEquals(List.of(source), removals);
  }

  @Test
  void safeAssetCannotBeStolenByForgedCarriedSnapshot() {
    UUID instanceId = UUID.randomUUID();
    PlayerItemEntity safe = row(UUID.randomUUID(), instanceId,
        InventoryLocation.WAREHOUSE, null);

    IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
        HibernatePlayerInventoryService.settlementRowsToRemove(UUID.randomUUID(), "game-1",
            carried(instanceId, "emerald"), List.of(safe)));

    assertTrue(failure.getMessage().contains("安全仓库"));
  }

  @Test
  void itemReservedByAnotherCraftovCannotTransfer() {
    UUID instanceId = UUID.randomUUID();
    PlayerItemEntity otherCraftov = row(UUID.randomUUID(), instanceId,
        InventoryLocation.CRAFTOV, "game-2");

    assertThrows(IllegalStateException.class, () ->
        HibernatePlayerInventoryService.settlementRowsToRemove(UUID.randomUUID(), "game-1",
            carried(instanceId, "emerald"), List.of(otherCraftov)));
  }

  @Test
  void existingPhysicalIdentityCannotChangeRegisteredItemDefinition() {
    UUID instanceId = UUID.randomUUID();
    PlayerItemEntity source = row(UUID.randomUUID(), instanceId,
        InventoryLocation.CRAFTOV, "game-1");

    assertThrows(IllegalStateException.class, () ->
        HibernatePlayerInventoryService.settlementRowsToRemove(UUID.randomUUID(), "game-1",
            carried(instanceId, "ancient_debris"), List.of(source)));
  }

  @Test
  void duplicatePhysicalIdentityInOneSnapshotIsRejectedBeforePersistence() {
    UUID instanceId = UUID.randomUUID();
    ItemInstance first = new ItemInstance(instanceId, ItemId.of("emerald"), 1);
    ItemInstance duplicate = new ItemInstance(instanceId, ItemId.of("emerald"), 1);

    assertThrows(IllegalArgumentException.class, () ->
        HibernatePlayerInventoryService.carriedInstances(List.of(first, duplicate)));
  }

  private static Map<UUID, ItemInstance> carried(UUID instanceId, String itemId) {
    return Map.of(instanceId, new ItemInstance(instanceId, ItemId.of(itemId), 1));
  }

  private static PlayerItemEntity row(UUID owner, UUID instanceId,
      InventoryLocation location, String craftovId) {
    return new PlayerItemEntity(UUID.randomUUID(), owner, instanceId, "emerald", 1,
        location, craftovId, null, Instant.EPOCH);
  }
}
