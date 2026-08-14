package com.birditch.escapefromcraftov.lobby.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.LegacyRaidTierResolver;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventorySnapshot;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.item.DefaultItems;
import com.birditch.escapefromcraftov.item.SimpleItemRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActionTierPreflightTest {

  private final SimpleItemRegistry items = new SimpleItemRegistry();

  @BeforeEach
  void setUp() {
    DefaultItems.registerAll(items);
  }

  @Test
  void checksEveryPartyMemberAndReportsTheFirstInsufficientMember() {
    RaidActionProfile tier = tier(2_000, 500);
    var ready = member("ready", inventory("iron_sword", 2), 500);
    var shortLoadout = member("short", inventory("iron_sword", 1), 10_000);

    ActionTierPreflight.Failure failure = ActionTierPreflight
        .firstFailure(tier, List.of(ready, shortLoadout), items).orElseThrow();

    assertEquals("short", failure.playerName());
    assertEquals(ActionTierPreflight.Kind.LOADOUT_VALUE, failure.kind());
    assertEquals(1_200, failure.actual());
  }

  @Test
  void checksEntryFeeAfterLoadoutValue() {
    ActionTierPreflight.Failure failure = ActionTierPreflight
        .firstFailure(tier(1_000, 900),
            List.of(member("poor", inventory("iron_sword", 1), 899)), items)
        .orElseThrow();

    assertEquals(ActionTierPreflight.Kind.ENTRY_FEE, failure.kind());
  }

  @Test
  void unknownLoadoutItemsFailClosedEvenWhenTierMinimumIsZero() {
    ActionTierPreflight.Failure failure = ActionTierPreflight
        .firstFailure(tier(0, 0),
            List.of(member("unknown", inventory("future_item", 1), 0)), items)
        .orElseThrow();

    assertEquals(ActionTierPreflight.Kind.UNKNOWN_ITEM, failure.kind());
  }

  @Test
  void allMembersPassingProducesNoFailure() {
    assertTrue(ActionTierPreflight.firstFailure(tier(1_200, 100), List.of(
        member("one", inventory("iron_sword", 1), 100),
        member("two", inventory("iron_sword", 2), 1_000)), items).isEmpty());
  }

  private static RaidActionProfile tier(long minimum, long fee) {
    RaidActionProfile base = LegacyRaidTierResolver.defaultProfiles().getFirst();
    return new RaidActionProfile(base.id(), base.displayName(), minimum, 1, 1, fee,
        base.legacyAliases());
  }

  private static ActionTierPreflight.Member member(String name,
      PlayerInventorySnapshot inventory, long balance) {
    return new ActionTierPreflight.Member(inventory.playerId(), name, inventory, balance);
  }

  private static PlayerInventorySnapshot inventory(String itemId, int quantity) {
    UUID playerId = UUID.randomUUID();
    StoredItem item = new StoredItem(UUID.randomUUID(), UUID.randomUUID(), ItemId.of(itemId),
        quantity, InventoryLocation.LOADOUT, null, null, null);
    return new PlayerInventorySnapshot(playerId, List.of(item));
  }
}
