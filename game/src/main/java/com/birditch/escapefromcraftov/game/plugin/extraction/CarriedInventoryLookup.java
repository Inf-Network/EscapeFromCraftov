package com.birditch.escapefromcraftov.game.plugin.extraction;

import java.util.Optional;
import java.util.UUID;

/** Supplies the player's current physical raid inventory, never a stale DB snapshot. */
@FunctionalInterface
public interface CarriedInventoryLookup {

  Optional<CarriedInventoryState> find(UUID playerId);
}
