package com.birditch.escapefromcraftov.game.plugin.carry;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pure slot policy for the physical in-raid player inventory. */
public final class RaidCarryLayout {

  public static final int MAIN_HAND_SLOT = 0;
  public static final int QUICK_USE_1_SLOT = 1;
  public static final int QUICK_USE_2_SLOT = 2;
  public static final int FIRST_POCKET_SLOT = 3;
  public static final int LAST_POCKET_SLOT = 6;
  public static final int CHEST_RIG_SLOT = 7;
  public static final int BACKPACK_SLOT = 8;
  public static final int FIRST_CONTAINER_SLOT = 9;
  public static final int LAST_CONTAINER_SLOT = 35;

  private static final Set<Integer> RESERVED_EQUIPMENT = Set.of(
      MAIN_HAND_SLOT, QUICK_USE_1_SLOT, QUICK_USE_2_SLOT,
      CHEST_RIG_SLOT, BACKPACK_SLOT);

  private RaidCarryLayout() {}

  public static List<Integer> cargoSlots(int containerSlots) {
    checkContainerSlots(containerSlots);
    List<Integer> result = new ArrayList<>(4 + containerSlots);
    for (int slot = FIRST_POCKET_SLOT; slot <= LAST_POCKET_SLOT; slot++) {
      result.add(slot);
    }
    for (int slot = FIRST_CONTAINER_SLOT;
        slot < FIRST_CONTAINER_SLOT + containerSlots; slot++) {
      result.add(slot);
    }
    return List.copyOf(result);
  }

  public static List<Integer> lockedStorageSlots(int containerSlots) {
    checkContainerSlots(containerSlots);
    List<Integer> result = new ArrayList<>(27 - containerSlots);
    for (int slot = FIRST_CONTAINER_SLOT + containerSlots;
        slot <= LAST_CONTAINER_SLOT; slot++) {
      result.add(slot);
    }
    return List.copyOf(result);
  }

  public static boolean reservedEquipmentSlot(int slot) {
    return RESERVED_EQUIPMENT.contains(slot);
  }

  public static boolean cargoSlot(int slot, int containerSlots) {
    checkContainerSlots(containerSlots);
    return slot >= FIRST_POCKET_SLOT && slot <= LAST_POCKET_SLOT
        || slot >= FIRST_CONTAINER_SLOT && slot < FIRST_CONTAINER_SLOT + containerSlots;
  }

  public static boolean canShrink(int occupiedCargoCells, int containerSlots) {
    checkContainerSlots(containerSlots);
    Preconditions.checkArgument(occupiedCargoCells >= 0,
        "occupiedCargoCells must not be negative");
    return occupiedCargoCells <= 4 + containerSlots;
  }

  private static void checkContainerSlots(int containerSlots) {
    Preconditions.checkArgument(containerSlots >= 0 && containerSlots <= 27,
        "containerSlots must be between 0 and 27");
  }
}
