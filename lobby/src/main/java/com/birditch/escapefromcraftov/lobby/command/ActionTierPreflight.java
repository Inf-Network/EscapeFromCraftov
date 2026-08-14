package com.birditch.escapefromcraftov.lobby.command;

import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventorySnapshot;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Fast, non-authoritative Lobby check before a complete party enters a tier pool. */
final class ActionTierPreflight {

  private ActionTierPreflight() {}

  static Optional<Failure> firstFailure(RaidActionProfile tier, List<Member> members,
      ItemRegistry items) {
    Preconditions.checkNotNull(tier, "tier");
    Preconditions.checkNotNull(members, "members");
    Preconditions.checkNotNull(items, "items");
    for (Member member : members) {
      boolean unknownItem = member.inventory().at(InventoryLocation.LOADOUT).stream()
          .anyMatch(stored -> items.find(stored.itemId()).isEmpty());
      if (unknownItem) {
        return Optional.of(new Failure(member.playerId(), member.playerName(),
            Kind.UNKNOWN_ITEM, 0, 0));
      }
      long loadoutValue = loadoutValue(member.inventory(), items);
      if (loadoutValue < tier.minimumLoadoutValue()) {
        return Optional.of(new Failure(member.playerId(), member.playerName(),
            Kind.LOADOUT_VALUE, loadoutValue, tier.minimumLoadoutValue()));
      }
      if (member.balance() < tier.entryFee()) {
        return Optional.of(new Failure(member.playerId(), member.playerName(),
            Kind.ENTRY_FEE, member.balance(), tier.entryFee()));
      }
    }
    return Optional.empty();
  }

  static long loadoutValue(PlayerInventorySnapshot inventory, ItemRegistry items) {
    long total = 0;
    for (var stored : inventory.at(InventoryLocation.LOADOUT)) {
      ItemDefinition definition = items.require(stored.itemId());
      total = Math.addExact(total,
          Math.multiplyExact(definition.basePrice().units(), stored.quantity()));
    }
    return total;
  }

  record Member(UUID playerId, String playerName, PlayerInventorySnapshot inventory,
                long balance) {
    Member {
      Preconditions.checkNotNull(playerId, "playerId");
      Preconditions.checkNotNull(playerName, "playerName");
      Preconditions.checkNotNull(inventory, "inventory");
      Preconditions.checkArgument(inventory.playerId().equals(playerId),
          "inventory player must match preflight member");
      Preconditions.checkArgument(balance >= 0, "balance must not be negative");
    }
  }

  record Failure(UUID playerId, String playerName, Kind kind, long actual, long required) {}

  enum Kind { UNKNOWN_ITEM, LOADOUT_VALUE, ENTRY_FEE }
}
