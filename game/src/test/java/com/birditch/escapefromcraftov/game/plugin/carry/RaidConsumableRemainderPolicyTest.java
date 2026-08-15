package com.birditch.escapefromcraftov.game.plugin.carry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class RaidConsumableRemainderPolicyTest {

  private final ItemInstance potion = new ItemInstance(
      UUID.randomUUID(), ItemId.of("healing_potion"), 1);

  @Test
  void oneUnmarkedGlassBottleClosesTheFinalTrackedPotionIdentity() {
    assertTrue(RaidConsumableRemainderPolicy.accepted(
        potion, true, Material.GLASS_BOTTLE, 1));
  }

  @Test
  void markedOrNonExactReplacementRemainsRejected() {
    assertFalse(RaidConsumableRemainderPolicy.accepted(
        potion, false, Material.GLASS_BOTTLE, 1));
    assertFalse(RaidConsumableRemainderPolicy.accepted(
        potion, true, Material.DIAMOND, 1));
    assertFalse(RaidConsumableRemainderPolicy.accepted(
        potion, true, Material.GLASS_BOTTLE, 2));
    assertFalse(RaidConsumableRemainderPolicy.accepted(new ItemInstance(
        UUID.randomUUID(), ItemId.of("healing_potion"), 2),
        true, Material.GLASS_BOTTLE, 1));
    assertFalse(RaidConsumableRemainderPolicy.accepted(new ItemInstance(
        UUID.randomUUID(), ItemId.of("golden_apple"), 1),
        true, Material.GLASS_BOTTLE, 1));
  }
}
