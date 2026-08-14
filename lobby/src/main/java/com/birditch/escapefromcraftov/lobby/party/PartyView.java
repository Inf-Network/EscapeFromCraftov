package com.birditch.escapefromcraftov.lobby.party;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;
import java.util.UUID;

/// Immutable view of one lobby party. `partyId` is also the raid team key.
public record PartyView(UUID partyId, UUID leaderId, List<UUID> members) {

  public PartyView {
    Preconditions.checkNotNull(partyId, "partyId");
    Preconditions.checkNotNull(leaderId, "leaderId");
    Preconditions.checkNotNull(members, "members");
    members = List.copyOf(members);
    Preconditions.checkArgument(!members.isEmpty(), "party must have at least one member");
    Preconditions.checkArgument(members.size() <= PartyService.MAX_MEMBERS,
        "party cannot exceed " + PartyService.MAX_MEMBERS + " members");
    Preconditions.checkArgument(members.contains(leaderId), "leader must be a party member");
  }

  public boolean isLeader(UUID playerId) {
    return leaderId.equals(playerId);
  }
}
