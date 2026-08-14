package com.birditch.escapefromcraftov.api.inventory;

/// Persistence location of an owned item stack.
public enum InventoryLocation {
  WAREHOUSE,
  LOADOUT,
  CRAFTOV,
  /// Reserved by an active player-market listing. It is not available to loadouts.
  MARKET
}
