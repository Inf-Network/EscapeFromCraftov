package com.birditch.escapefromcraftov.game.plugin.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovLootZone;
import com.birditch.escapefromcraftov.api.loot.LootContainerType;
import com.birditch.escapefromcraftov.api.loot.LootTableId;
import java.util.List;
import org.junit.jupiter.api.Test;

final class LootZoneResolverTest {

  private static final CraftovGameId GAME = CraftovGameId.of("raid-1");

  @Test
  void createsStableContainerInsideEnabledDensityOneZone() {
    CraftovLootZone zone = zone(1);
    var first = LootZoneResolver.resolve(GAME, List.of(zone), 3, 64, 4,
        LootContainerType.BARREL).orElseThrow();
    var second = LootZoneResolver.resolve(GAME, List.of(zone), 3, 64, 4,
        LootContainerType.BARREL).orElseThrow();

    assertEquals(first, second);
    assertEquals("zone:warehouse:3:64:4", first.id());
    assertEquals(LootTableId.of("craftov_tier_2"), first.lootTableId());
  }

  @Test
  void fractionalDensityDecisionIsDeterministicAndRejectsOutsideBlocks() {
    CraftovLootZone zone = zone(0.42);
    boolean first = LootZoneResolver.active(GAME, zone, 5, 64, 5);
    assertEquals(first, LootZoneResolver.active(GAME, zone, 5, 64, 5));
    assertFalse(LootZoneResolver.contains(List.of(zone), 30, 64, 30));
    assertTrue(LootZoneResolver.contains(List.of(zone), 0, 60, 0));
  }

  private static CraftovLootZone zone(double density) {
    return new CraftovLootZone("warehouse", LootTableId.of("craftov_tier_2"), 2, density,
        0.5, 0, 60, 0, 10, 70, 10);
  }
}
