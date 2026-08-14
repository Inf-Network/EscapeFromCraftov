package com.birditch.escapefromcraftov.game.plugin.carry;

import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import org.jspecify.annotations.Nullable;

/** Pure reconciliation policy for one tracked physical equipment stack. */
final class RaidEquipmentAuthority {

  enum Outcome {
    UNCHANGED,
    DECREASED,
    CONSUMED,
    REJECTED
  }

  private RaidEquipmentAuthority() {}

  static Outcome reconcile(ItemInstance expected, @Nullable ItemInstance actual,
      boolean consumable) {
    ItemInstance baseline = Preconditions.checkNotNull(expected, "expected");
    if (actual == null) return consumable ? Outcome.CONSUMED : Outcome.REJECTED;
    if (!baseline.instanceId().equals(actual.instanceId())
        || !baseline.itemId().equals(actual.itemId())
        || actual.quantity() > baseline.quantity()) return Outcome.REJECTED;
    if (actual.quantity() < baseline.quantity()) {
      return consumable ? Outcome.DECREASED : Outcome.REJECTED;
    }
    // Payload is deliberately not frozen: legitimate durability/custom-NBT changes are allowed
    // while durable identity, definition and quantity remain authoritative.
    return Outcome.UNCHANGED;
  }
}
