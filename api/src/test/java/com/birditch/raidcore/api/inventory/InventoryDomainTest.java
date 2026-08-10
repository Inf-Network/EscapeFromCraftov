package com.birditch.raidcore.api.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.item.ItemId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryDomainTest {
  private static final UUID PLAYER = UUID.randomUUID();
  private static final UUID STORAGE = UUID.randomUUID();

  @Test
  void raidRowsRequireRaidId() {
    assertThrows(IllegalArgumentException.class, () -> new StoredItem(STORAGE,
        ItemId.of("emerald"), 1, InventoryLocation.RAID, null));
  }

  @Test
  void snapshotFiltersByLocationWithoutExposingMutableList() {
    PlayerInventorySnapshot snapshot = new PlayerInventorySnapshot(PLAYER, List.of(
        new StoredItem(STORAGE, ItemId.of("emerald"), 2, InventoryLocation.WAREHOUSE, null)));

    assertEquals(1, snapshot.at(InventoryLocation.WAREHOUSE).size());
    assertEquals(0, snapshot.at(InventoryLocation.LOADOUT).size());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.items().clear());
  }

  @Test
  void raidIdIsAvailableOnlyForRaidRows() {
    StoredItem row = new StoredItem(STORAGE, ItemId.of("emerald"), 1,
        InventoryLocation.RAID, RaidGameId.of("raid-1"));
    assertEquals("raid-1", row.currentRaid().orElseThrow().value());
  }
}
