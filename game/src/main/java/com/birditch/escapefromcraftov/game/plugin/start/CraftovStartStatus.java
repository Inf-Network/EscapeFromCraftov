package com.birditch.escapefromcraftov.game.plugin.start;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import java.util.OptionalInt;

/// Read-only view of an automatic room countdown for HUD renderers.
public interface CraftovStartStatus {

  OptionalInt remainingSeconds(CraftovGameId gameId);
}
