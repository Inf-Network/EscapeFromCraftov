package com.birditch.escapefromcraftov.game.plugin.carry;

import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import org.bukkit.Material;

/// Whitelist for identity-free vanilla container items produced after a tracked
/// Craftov consumable finishes. Every non-whitelisted replacement remains
/// fail-closed instead of becoming cargo or settlement input.
final class RaidConsumableRemainderPolicy {

  private RaidConsumableRemainderPolicy() {
  }

  static boolean accepted(ItemInstance expected, boolean unmarked, Material material, int amount) {
    Preconditions.checkNotNull(expected, "expected");
    Preconditions.checkNotNull(material, "material");
    if (!unmarked || expected.quantity() != 1 || amount != 1) return false;
    return expected.itemId().value().equals("healing_potion")
        && material == Material.GLASS_BOTTLE;
  }
}
