package com.birditch.escapefromcraftov.game.plugin.carry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RaidEquipmentAuthorityTest {

  private static final UUID ID = UUID.randomUUID();
  private static final ItemId TYPE = ItemId.of("medkit");
  private static final ItemInstance BASELINE = new ItemInstance(ID, TYPE, 3, "old-nbt");

  @Test
  void identityTypeAndQuantityCannotBeForgedOrIncreased() {
    assertEquals(RaidEquipmentAuthority.Outcome.REJECTED,
        RaidEquipmentAuthority.reconcile(BASELINE,
            new ItemInstance(UUID.randomUUID(), TYPE, 3), true));
    assertEquals(RaidEquipmentAuthority.Outcome.REJECTED,
        RaidEquipmentAuthority.reconcile(BASELINE,
            new ItemInstance(ID, ItemId.of("ammo"), 3), true));
    assertEquals(RaidEquipmentAuthority.Outcome.REJECTED,
        RaidEquipmentAuthority.reconcile(BASELINE,
            new ItemInstance(ID, TYPE, 4), true));
  }

  @Test
  void onlyConsumablesMayDecreaseOrDisappearAndPayloadMayChangeAtEqualQuantity() {
    ItemInstance decreased = new ItemInstance(ID, TYPE, 2, "changed-nbt");
    assertEquals(RaidEquipmentAuthority.Outcome.DECREASED,
        RaidEquipmentAuthority.reconcile(BASELINE, decreased, true));
    assertEquals(RaidEquipmentAuthority.Outcome.CONSUMED,
        RaidEquipmentAuthority.reconcile(BASELINE, null, true));
    assertEquals(RaidEquipmentAuthority.Outcome.REJECTED,
        RaidEquipmentAuthority.reconcile(BASELINE, decreased, false));
    assertEquals(RaidEquipmentAuthority.Outcome.REJECTED,
        RaidEquipmentAuthority.reconcile(BASELINE, null, false));
    assertEquals(RaidEquipmentAuthority.Outcome.UNCHANGED,
        RaidEquipmentAuthority.reconcile(BASELINE,
            new ItemInstance(ID, TYPE, 3, "new-durability-nbt"), false));
  }
}
