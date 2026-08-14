package com.birditch.escapefromcraftov.lobby.party;

import com.birditch.escapefromcraftov.common.result.Result;
import java.util.Optional;
import java.util.UUID;

/// Main-thread lobby party registry.
///
/// The current implementation is intentionally process-local: it is atomic on
/// one Lobby server, but parties do not survive a lobby restart and are not
/// visible to a second Lobby replica. The API keeps that storage boundary out
/// of commands so a Redis implementation can replace it later.
public interface PartyService {

  int MAX_MEMBERS = 3;

  Result<PartyView> create(UUID leaderId);

  Result<PartyView> invite(UUID inviterId, UUID invitedId);

  Result<PartyView> accept(UUID invitedId);

  Result<Optional<PartyView>> leave(UUID memberId);

  Optional<PartyView> party(UUID memberId);

  Optional<PartyView> partyById(UUID partyId);

  Optional<UUID> invitingParty(UUID invitedId);

  /// Removes live membership and any received invitation when a player leaves
  /// this Lobby process.
  void disconnect(UUID playerId);
}
