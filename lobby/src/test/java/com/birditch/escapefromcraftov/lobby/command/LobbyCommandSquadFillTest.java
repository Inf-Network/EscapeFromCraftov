package com.birditch.escapefromcraftov.lobby.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.LegacyRaidTierResolver;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.matchmaking.SquadFillMode;
import com.birditch.escapefromcraftov.lobby.menu.RaidPreparationController;
import com.birditch.escapefromcraftov.lobby.party.PartyView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LobbyCommandSquadFillTest {

  private static final CraftovMapId MAP = CraftovMapId.of("test_flat");

  @Test
  void preparationDefaultsToRecommendedAutoFill() {
    var selection = new RaidPreparationController.Selection(
        MAP, RaidMode.NORMAL, RaidDifficulty.NORMAL);

    assertEquals(SquadFillMode.AUTO_FILL, selection.fillMode());
  }

  @Test
  void explicitUiSelectionSurvivesTheCanonicalTierQueueRequest() {
    UUID playerId = UUID.randomUUID();
    RaidActionProfile tier = LegacyRaidTierResolver.defaultProfiles().getFirst();
    var selection = new RaidPreparationController.Selection(
        MAP, RaidMode.CONFIDENTIAL, RaidDifficulty.HARD, tier.id(), SquadFillMode.NO_FILL);

    RaidQueueRequest request = RaidQueueRequest.create(
        playerId, null, selection.mapId(), selection.mode(), selection.difficulty(), tier,
        selection.fillMode());

    assertEquals(List.of(playerId), request.members());
    assertEquals(playerId, request.teamKey());
    assertSame(tier, request.tier());
    assertEquals(SquadFillMode.NO_FILL, request.fillMode());
  }

  @Test
  void authoredPartyForcesNoFillBeforeTierPreflight() {
    UUID leader = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    UUID partyId = UUID.randomUUID();
    PartyView party = new PartyView(partyId, leader, List.of(leader, member));

    RaidQueueRequest request = RaidQueueRequest.create(
        leader, party, MAP, RaidMode.NORMAL, RaidDifficulty.NORMAL, null,
        SquadFillMode.AUTO_FILL);

    assertEquals(List.of(leader, member), request.members());
    assertEquals(partyId, request.teamKey());
    assertEquals(SquadFillMode.NO_FILL, request.fillMode());
  }
}
