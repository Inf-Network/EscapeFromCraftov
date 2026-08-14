package com.birditch.escapefromcraftov.lobby.party;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryPartyServiceTest {

  private final InMemoryPartyService parties = new InMemoryPartyService();
  private final UUID leader = UUID.randomUUID();
  private final UUID second = UUID.randomUUID();
  private final UUID third = UUID.randomUUID();

  @Test
  void createInviteAcceptProducesStableThreePersonTeamKey() {
    UUID partyId = parties.create(leader).value().orElseThrow().partyId();

    parties.invite(leader, second).orElseThrow(IllegalStateException::new);
    PartyView two = parties.accept(second).orElseThrow(IllegalStateException::new);
    parties.invite(leader, third).orElseThrow(IllegalStateException::new);
    PartyView three = parties.accept(third).orElseThrow(IllegalStateException::new);

    assertEquals(partyId, two.partyId());
    assertEquals(partyId, three.partyId());
    assertEquals(List.of(leader, second, third), three.members());
    assertEquals(three, parties.party(second).orElseThrow());
  }

  @Test
  void onlyLeaderCanInviteAndPartyNeverExceedsThree() {
    parties.create(leader);
    parties.invite(leader, second);
    parties.accept(second);

    assertEquals(InMemoryPartyService.LEADER_ONLY,
        parties.invite(second, third).error().orElseThrow());
    parties.invite(leader, third);
    parties.accept(third);
    assertEquals(InMemoryPartyService.PARTY_FULL,
        parties.invite(leader, UUID.randomUUID()).error().orElseThrow());
  }

  @Test
  void leaderLeavePromotesOldestRemainingMemberWithoutChangingPartyId() {
    UUID partyId = parties.create(leader).value().orElseThrow().partyId();
    parties.invite(leader, second);
    parties.accept(second);

    PartyView remaining = parties.leave(leader).value().orElseThrow().orElseThrow();

    assertEquals(partyId, remaining.partyId());
    assertEquals(second, remaining.leaderId());
    assertFalse(parties.party(leader).isPresent());
    assertTrue(parties.party(second).isPresent());
  }

  @Test
  void disconnectRemovesMembershipAndReceivedInvitation() {
    parties.create(leader);
    parties.invite(leader, second);
    parties.accept(second);
    parties.invite(leader, third);

    parties.disconnect(leader);
    parties.disconnect(third);

    assertEquals(second, parties.party(second).orElseThrow().leaderId());
    assertFalse(parties.party(leader).isPresent());
    assertFalse(parties.invitingParty(third).isPresent());
  }
}
