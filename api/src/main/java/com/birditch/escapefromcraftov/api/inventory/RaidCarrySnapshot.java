package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable, settlement-ready view of one player's physical raid inventory.
 * Equipment includes armor, hands, quick-use items and the rig/backpack objects;
 * cargo contains normalized durable instances from pocket and unlocked container
 * cells, while {@code occupiedCargoSlots} preserves their physical cell usage.
 */
public record RaidCarrySnapshot(
    RaidCarryCapacity capacity,
    List<ItemInstance> equipmentItems,
    List<ItemInstance> cargoItems,
    int occupiedCargoSlots,
    long totalWeightGrams,
    double movementMultiplier) {

  public RaidCarrySnapshot {
    Preconditions.checkNotNull(capacity, "capacity");
    equipmentItems = List.copyOf(Preconditions.checkNotNull(equipmentItems, "equipmentItems"));
    cargoItems = List.copyOf(Preconditions.checkNotNull(cargoItems, "cargoItems"));
    Preconditions.checkArgument(occupiedCargoSlots >= cargoItems.size()
            && occupiedCargoSlots <= capacity.cargoSlots(),
        "occupied cargo slots must cover normalized items and fit unlocked slots");
    Preconditions.checkArgument(totalWeightGrams >= 0
            && totalWeightGrams <= capacity.maximumWeightGrams(),
        "totalWeightGrams exceeds capacity");
    Preconditions.checkArgument(movementMultiplier > 0.0 && movementMultiplier <= 1.0,
        "movementMultiplier must be in (0, 1]");
    Set<UUID> identities = new HashSet<>();
    for (ItemInstance item : equipmentItems) {
      Preconditions.checkArgument(identities.add(item.instanceId()),
          "duplicate physical item instance");
    }
    for (ItemInstance item : cargoItems) {
      Preconditions.checkArgument(identities.add(item.instanceId()),
          "duplicate physical item instance");
    }
  }

  /** Compatibility constructor for callers whose instances each occupy one cell. */
  public RaidCarrySnapshot(RaidCarryCapacity capacity,
      List<ItemInstance> equipmentItems, List<ItemInstance> cargoItems,
      long totalWeightGrams, double movementMultiplier) {
    this(capacity, equipmentItems, cargoItems, cargoItems.size(),
        totalWeightGrams, movementMultiplier);
  }

  public int usedCargoSlots() {
    return occupiedCargoSlots;
  }

  public int availableCargoSlots() {
    return capacity.cargoSlots() - usedCargoSlots();
  }

  public double weightRatio() {
    return (double) totalWeightGrams / capacity.maximumWeightGrams();
  }

  public List<ItemInstance> settlementItems() {
    List<ItemInstance> result = new ArrayList<>(equipmentItems.size() + cargoItems.size());
    result.addAll(equipmentItems);
    result.addAll(cargoItems);
    return List.copyOf(result);
  }
}
