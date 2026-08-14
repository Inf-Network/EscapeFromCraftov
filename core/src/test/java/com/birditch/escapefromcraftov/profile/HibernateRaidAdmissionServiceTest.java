package com.birditch.escapefromcraftov.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidAdmissionRejection;
import com.birditch.escapefromcraftov.api.item.ItemCategory;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemRarity;
import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import com.birditch.escapefromcraftov.core.database.entity.PlayerItemEntity;
import com.birditch.escapefromcraftov.item.SimpleItemRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HibernateRaidAdmissionServiceTest {

  @Test
  void recomputesCompleteStackValueAndRejectsUnknownItems() {
    SimpleItemRegistry items = new SimpleItemRegistry();
    items.register(ItemDefinition.builder(ItemId.of("known"), "Known",
        ItemCategory.VALUABLE, ItemRarity.COMMON).basePrice(Money.of(1_250)).build());
    items.register(ItemDefinition.builder(ItemId.of("overflow"), "Overflow",
        ItemCategory.VALUABLE, ItemRarity.COMMON)
        .basePrice(Money.of(Long.MAX_VALUE)).build());
    PlayerItemEntity known = row("known", 4);

    var valuation = HibernateRaidAdmissionService.loadoutValue(items, List.of(known));
    assertTrue(valuation.valid());
    assertEquals(5_000, valuation.value());

    var unknown = HibernateRaidAdmissionService.loadoutValue(
        items, List.of(known, row("unknown", 1)));
    assertEquals(RaidAdmissionRejection.UNKNOWN_LOADOUT_ITEM, unknown.rejection());
    var overflow = HibernateRaidAdmissionService.loadoutValue(
        items, List.of(row("overflow", 2)));
    assertEquals(RaidAdmissionRejection.LOADOUT_VALUE_OVERFLOW, overflow.rejection());
  }

  @Test
  void deterministicAuditIdentityIsBoundToGamePlayerAndFrozenProfile() {
    UUID playerId = UUID.randomUUID();
    CraftovGameId gameId = CraftovGameId.of("raid-1");
    RaidActionProfile normal = profile("normal", 1_000, 500);
    RaidActionProfile confidential = profile("confidential", 10_000, 2_000);

    assertEquals(HibernateRaidAdmissionService.operationId(playerId, gameId),
        HibernateRaidAdmissionService.operationId(playerId, gameId));
    assertNotEquals(HibernateRaidAdmissionService.operationId(playerId, gameId),
        HibernateRaidAdmissionService.operationId(playerId, CraftovGameId.of("raid-2")));

    String normalSignature = HibernateRaidAdmissionService.actionProfileSignature(
        playerId, gameId, normal);
    assertNotEquals(normalSignature, HibernateRaidAdmissionService.actionProfileSignature(
        playerId, gameId, confidential));
    String loadoutSignature = HibernateRaidAdmissionService.loadoutSignature(
        List.of(row("known", 1)));
    var payload = HibernateRaidAdmissionService.parseAuditDescription(
        HibernateRaidAdmissionService.auditDescription(
            gameId, normalSignature, 12_500, loadoutSignature));
    assertEquals(gameId, payload.gameId());
    assertEquals(normalSignature, payload.profileSignature());
    assertEquals(12_500, payload.loadoutValue());
    assertEquals(loadoutSignature, payload.loadoutSignature());
  }

  private static PlayerItemEntity row(String itemId, int quantity) {
    return new PlayerItemEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        itemId, quantity, InventoryLocation.LOADOUT, null, null, Instant.EPOCH);
  }

  private static RaidActionProfile profile(String id, long minimumValue, long entryFee) {
    return new RaidActionProfile(RaidTierId.of(id), id, minimumValue,
        1.0, 1.0, entryFee, List.of());
  }
}
