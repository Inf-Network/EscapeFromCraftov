package com.birditch.raidcore.game.plugin.start;

import com.birditch.raidcore.api.game.RaidGameId;
import java.util.OptionalInt;

/// Read-only view of an automatic room countdown for HUD renderers.
public interface RaidStartStatus {

  OptionalInt remainingSeconds(RaidGameId gameId);
}
