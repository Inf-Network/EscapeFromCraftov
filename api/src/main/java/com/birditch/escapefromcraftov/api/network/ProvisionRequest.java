package com.birditch.escapefromcraftov.api.network;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/// Structured Redis pub/sub request asking one game server to prepare capacity
/// for a specific map.
public record ProvisionRequest(ServerId serverId, CraftovMapId mapId,
                               RaidMode mode, RaidDifficulty difficulty, int requiredSlots) {

  public ProvisionRequest(ServerId serverId, CraftovMapId mapId) {
    this(serverId, mapId, RaidMode.NORMAL, RaidDifficulty.NORMAL, 1);
  }

  public ProvisionRequest(ServerId serverId, CraftovMapId mapId,
      RaidMode mode, RaidDifficulty difficulty) {
    this(serverId, mapId, mode, difficulty, 1);
  }

  public ProvisionRequest {
    Preconditions.checkNotNull(serverId, "serverId");
    Preconditions.checkNotNull(mapId, "mapId");
    if (mode == null) mode = RaidMode.NORMAL;
    if (difficulty == null) difficulty = RaidDifficulty.NORMAL;
    // Missing primitive fields in old JSON decode as zero.
    if (requiredSlots == 0) requiredSlots = 1;
    Preconditions.checkArgument(requiredSlots >= 1 && requiredSlots <= 3,
        "requiredSlots must be between 1 and 3");
  }
}
