package com.birditch.escapefromcraftov.inventory;

import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.inventory.LoadoutSlot;
import com.birditch.escapefromcraftov.api.inventory.RaidCarryAdmission;
import com.birditch.escapefromcraftov.api.inventory.RaidCarryCapacity;
import com.birditch.escapefromcraftov.api.inventory.RaidCarryFailure;
import com.birditch.escapefromcraftov.api.inventory.RaidCarryRules;
import com.birditch.escapefromcraftov.api.inventory.RaidCarryService;
import com.birditch.escapefromcraftov.api.inventory.RaidCarrySnapshot;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Default registry-backed authority for physical raid cargo and carried weight. */
public final class DefaultRaidCarryService implements RaidCarryService {

  private final ItemRegistry items;
  private final RaidCarryRules rules;

  public DefaultRaidCarryService(ItemRegistry items) {
    this(items, RaidCarryRules.defaults());
  }

  public DefaultRaidCarryService(ItemRegistry items, RaidCarryRules rules) {
    this.items = Preconditions.checkNotNull(items, "items");
    this.rules = Preconditions.checkNotNull(rules, "rules");
  }

  @Override
  public RaidCarryCapacity capacityFor(List<StoredItem> claimedLoadout) {
    List<StoredItem> loadout = List.copyOf(
        Preconditions.checkNotNull(claimedLoadout, "claimedLoadout"));
    for (StoredItem stored : loadout) {
      Preconditions.checkArgument(stored.location() == InventoryLocation.LOADOUT
              || stored.location() == InventoryLocation.CRAFTOV,
          "carry capacity only accepts LOADOUT or claimed CRAFTOV rows");
    }

    // A historical loadout did not carry named positions. Keep its full vanilla
    // cargo footprint so adding container metadata never destroys old loadouts.
    if (loadout.stream().anyMatch(stored -> stored.loadoutSlot() == null)) {
      return capacity(rules.maximumContainerSlots(), rules.legacyMaximumWeightGrams(), true);
    }

    Set<LoadoutSlot> occupied = EnumSet.noneOf(LoadoutSlot.class);
    int containerSlots = 0;
    for (StoredItem stored : loadout) {
      LoadoutSlot slot = stored.loadoutSlot();
      Preconditions.checkArgument(slot != null && occupied.add(slot),
          "named loadout contains a duplicate slot");
      ItemDefinition definition = items.require(stored.itemId());
      Preconditions.checkArgument(slot.accepts(definition.category()),
          "named loadout item category does not match its slot");
      containerSlots = Math.addExact(containerSlots, definition.containerSlots());
    }
    Preconditions.checkArgument(containerSlots <= rules.maximumContainerSlots(),
        "named loadout unlocks more container slots than the server permits");
    long maximumWeight = Math.addExact(rules.baseMaximumWeightGrams(),
        Math.multiplyExact(rules.weightPerContainerSlotGrams(), containerSlots));
    return capacity(containerSlots, maximumWeight, false);
  }

  @Override
  public RaidCarrySnapshot snapshot(RaidCarryCapacity capacity,
      List<ItemInstance> equipmentItems, List<ItemInstance> cargoItems) {
    List<ItemInstance> cargo = Preconditions.checkNotNull(cargoItems, "cargoItems");
    return snapshot(capacity, equipmentItems, cargo, cargo.size());
  }

  @Override
  public RaidCarrySnapshot snapshot(RaidCarryCapacity capacity,
      List<ItemInstance> equipmentItems, List<ItemInstance> cargoItems,
      int occupiedCargoSlots) {
    RaidCarryCapacity checkedCapacity = Preconditions.checkNotNull(capacity, "capacity");
    List<ItemInstance> equipment = List.copyOf(
        Preconditions.checkNotNull(equipmentItems, "equipmentItems"));
    List<ItemInstance> cargo = List.copyOf(
        Preconditions.checkNotNull(cargoItems, "cargoItems"));
    Preconditions.checkArgument(occupiedCargoSlots >= cargo.size()
            && occupiedCargoSlots <= checkedCapacity.cargoSlots(),
        "physical cargo stacks exceed unlocked slots");
    long weight = totalWeight(equipment, cargo);
    Preconditions.checkArgument(weight <= checkedCapacity.maximumWeightGrams(),
        "carried weight exceeds the raid maximum");
    return new RaidCarrySnapshot(checkedCapacity, equipment, cargo, occupiedCargoSlots, weight,
        movementMultiplier(checkedCapacity, weight));
  }

  @Override
  public RaidCarryAdmission admit(RaidCarrySnapshot current, List<ItemInstance> additions) {
    RaidCarrySnapshot supplied = Preconditions.checkNotNull(current, "current");
    List<ItemInstance> added = List.copyOf(Preconditions.checkNotNull(additions, "additions"));
    // Rebuild instead of trusting caller-supplied derived weight/movement fields.
    RaidCarrySnapshot authoritative = snapshot(supplied.capacity(),
        supplied.equipmentItems(), supplied.cargoItems(), supplied.occupiedCargoSlots());

    Set<UUID> identities = new HashSet<>();
    authoritative.settlementItems().forEach(item -> identities.add(item.instanceId()));
    long addedWeight = 0L;
    for (ItemInstance addition : added) {
      if (!identities.add(addition.instanceId())) {
        return rejected(authoritative, RaidCarryFailure.DUPLICATE_INSTANCE);
      }
      ItemDefinition definition = items.find(addition.itemId()).orElse(null);
      if (definition == null) {
        return rejected(authoritative, RaidCarryFailure.UNKNOWN_ITEM);
      }
      if (addition.quantity() > definition.stackSize()) {
        return rejected(authoritative, RaidCarryFailure.INVALID_PHYSICAL_STACK);
      }
      try {
        addedWeight = Math.addExact(addedWeight,
            Math.multiplyExact((long) definition.weightGrams(), addition.quantity()));
      } catch (ArithmeticException overflow) {
        return rejected(authoritative, RaidCarryFailure.WEIGHT_CAPACITY);
      }
    }
    if (authoritative.usedCargoSlots() + added.size() > authoritative.capacity().cargoSlots()) {
      return rejected(authoritative, RaidCarryFailure.SLOT_CAPACITY);
    }
    long resultingWeight;
    try {
      resultingWeight = Math.addExact(authoritative.totalWeightGrams(), addedWeight);
    } catch (ArithmeticException overflow) {
      return rejected(authoritative, RaidCarryFailure.WEIGHT_CAPACITY);
    }
    if (resultingWeight > authoritative.capacity().maximumWeightGrams()) {
      return rejected(authoritative, RaidCarryFailure.WEIGHT_CAPACITY);
    }
    List<ItemInstance> cargo = new ArrayList<>(authoritative.cargoItems());
    cargo.addAll(added);
    return new RaidCarryAdmission(true,
        snapshot(authoritative.capacity(), authoritative.equipmentItems(), cargo,
            authoritative.usedCargoSlots() + added.size()), null);
  }

  private RaidCarryCapacity capacity(int containerSlots, long maximumWeight,
      boolean legacyCompatibility) {
    long comfortableWeight = Math.min(maximumWeight - 1L,
        (long) Math.floor(maximumWeight * rules.comfortableWeightFraction()));
    return new RaidCarryCapacity(rules.pocketSlots(), containerSlots,
        comfortableWeight, maximumWeight, legacyCompatibility);
  }

  private long totalWeight(List<ItemInstance> equipment, List<ItemInstance> cargo) {
    long total = 0L;
    for (ItemInstance instance : concat(equipment, cargo)) {
      ItemDefinition definition = items.require(instance.itemId());
      Preconditions.checkArgument(instance.quantity() <= definition.stackSize(),
          "physical stack exceeds the registered stack size");
      try {
        total = Math.addExact(total,
            Math.multiplyExact((long) definition.weightGrams(), instance.quantity()));
      } catch (ArithmeticException overflow) {
        throw new IllegalArgumentException("carried weight exceeds the supported range", overflow);
      }
    }
    return total;
  }

  private double movementMultiplier(RaidCarryCapacity capacity, long weight) {
    if (weight <= capacity.comfortableWeightGrams()) return 1.0;
    double overloaded = (double) (weight - capacity.comfortableWeightGrams())
        / (capacity.maximumWeightGrams() - capacity.comfortableWeightGrams());
    return Math.max(rules.minimumMovementMultiplier(),
        1.0 - overloaded * (1.0 - rules.minimumMovementMultiplier()));
  }

  private static List<ItemInstance> concat(List<ItemInstance> first,
      List<ItemInstance> second) {
    List<ItemInstance> result = new ArrayList<>(first.size() + second.size());
    result.addAll(first);
    result.addAll(second);
    return result;
  }

  private static RaidCarryAdmission rejected(RaidCarrySnapshot current,
      RaidCarryFailure failure) {
    return new RaidCarryAdmission(false, current, failure);
  }
}
