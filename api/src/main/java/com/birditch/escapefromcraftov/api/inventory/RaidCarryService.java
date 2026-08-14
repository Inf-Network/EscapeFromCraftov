package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.api.item.ItemInstance;
import java.util.List;

/**
 * Bukkit-independent authority for raid cargo capacity, weight and movement load.
 * Capacity is measured in physical Bukkit cells. Durable instances may be normalized
 * across vanilla-split cells, so callers with physical inventory access should use the
 * overload that supplies {@code occupiedCargoSlots} explicitly.
 */
public interface RaidCarryService {

  RaidCarryCapacity capacityFor(List<StoredItem> claimedLoadout);

  /** Builds an authoritative snapshot, rejecting invalid/over-capacity input. */
  RaidCarrySnapshot snapshot(RaidCarryCapacity capacity,
      List<ItemInstance> equipmentItems, List<ItemInstance> cargoItems);

  /** Builds a snapshot with an authoritative physical-cell count. */
  default RaidCarrySnapshot snapshot(RaidCarryCapacity capacity,
      List<ItemInstance> equipmentItems, List<ItemInstance> cargoItems,
      int occupiedCargoSlots) {
    if (occupiedCargoSlots != cargoItems.size()) {
      throw new UnsupportedOperationException(
          "this carry authority does not support normalized multi-cell stacks");
    }
    return snapshot(capacity, equipmentItems, cargoItems);
  }

  /** Evaluates an atomic addition without mutating the current snapshot on rejection. */
  RaidCarryAdmission admit(RaidCarrySnapshot current, List<ItemInstance> additions);
}
