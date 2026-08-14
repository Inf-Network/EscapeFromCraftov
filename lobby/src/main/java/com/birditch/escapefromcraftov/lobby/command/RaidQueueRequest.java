package com.birditch.escapefromcraftov.lobby.command;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.matchmaking.SquadFillMode;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.lobby.party.PartyView;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// Immutable hand-off from command/UI selection through tier preflight into matchmaking.
record RaidQueueRequest(List<UUID> members, UUID teamKey, CraftovMapId mapId,
                        RaidMode mode, RaidDifficulty difficulty,
                        @Nullable RaidActionProfile tier, SquadFillMode fillMode) {

  RaidQueueRequest {
    members = List.copyOf(Preconditions.checkNotNull(members, "members"));
    Preconditions.checkNotNull(teamKey, "teamKey");
    Preconditions.checkNotNull(mapId, "mapId");
    Preconditions.checkNotNull(mode, "mode");
    Preconditions.checkNotNull(difficulty, "difficulty");
    Preconditions.checkNotNull(fillMode, "fillMode");
  }

  static RaidQueueRequest create(UUID requesterId, @Nullable PartyView party,
      CraftovMapId mapId, RaidMode mode, RaidDifficulty difficulty,
      @Nullable RaidActionProfile tier, SquadFillMode requestedFillMode) {
    Preconditions.checkNotNull(requesterId, "requesterId");
    Preconditions.checkNotNull(requestedFillMode, "requestedFillMode");
    List<UUID> members = party == null ? List.of(requesterId) : party.members();
    UUID teamKey = party == null ? requesterId : party.partyId();
    SquadFillMode fillMode = party == null
        ? requestedFillMode : SquadFillMode.NO_FILL;
    return new RaidQueueRequest(members, teamKey, mapId, mode, difficulty, tier, fillMode);
  }
}
