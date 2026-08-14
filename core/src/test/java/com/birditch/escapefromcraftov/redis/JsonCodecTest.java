package com.birditch.escapefromcraftov.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.CraftovException;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.matchmaking.MatchTicket;
import com.birditch.escapefromcraftov.api.network.GameServerSnapshot;
import com.birditch.escapefromcraftov.api.network.InstanceSnapshot;
import com.birditch.escapefromcraftov.api.network.MatchAssignment;
import com.birditch.escapefromcraftov.api.network.ProvisionRequest;
import com.birditch.escapefromcraftov.api.network.ServerId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonCodecTest {

  private final JsonCodec codec = new JsonCodec();

  private static GameServerSnapshot sampleSnapshot() {
    return new GameServerSnapshot(ServerId.of("game-01"), "craftov-01", 1_723_105_530_000L, 4,
        List.of(
            new InstanceSnapshot(CraftovGameId.of("a1b2c3"), CraftovMapId.of("debug_zone"),
                CraftovGameState.WAITING, 2, 12, true),
            new InstanceSnapshot(CraftovGameId.of("d4e5f6"), CraftovMapId.of("debug_zone"),
                CraftovGameState.RUNNING, 12, 12, false)));
  }

  @Test
  void roundTripsGameServerSnapshotIncludingNestedInstances() {
    GameServerSnapshot snapshot = sampleSnapshot();
    GameServerSnapshot decoded =
        codec.fromJson(codec.toJson(snapshot), GameServerSnapshot.class);
    assertEquals(snapshot, decoded);
  }

  @Test
  void roundTripsSnapshotWithoutInstances() {
    GameServerSnapshot snapshot =
        new GameServerSnapshot(ServerId.of("game-02"), "craftov-02", 42L, 1, List.of());
    assertEquals(snapshot, codec.fromJson(codec.toJson(snapshot), GameServerSnapshot.class));
  }

  @Test
  void roundTripsMatchAssignment() {
    UUID teamKey = UUID.fromString("00000000-0000-0000-0000-000000000099");
    MatchAssignment assignment = new MatchAssignment(
        UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), ServerId.of("game-01"),
        "craftov-01", CraftovGameId.of("a1b2c3"), CraftovMapId.of("debug_zone"), teamKey,
        RaidMode.CONFIDENTIAL, RaidDifficulty.HARD);
    assertEquals(assignment, codec.fromJson(codec.toJson(assignment), MatchAssignment.class));
  }

  @Test
  void oldMatchAssignmentWithoutTeamKeyDefaultsToSoloPlayerKey() {
    UUID playerId = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
    String legacyJson = """
        {"playerId":"6ba7b810-9dad-11d1-80b4-00c04fd430c8",\
        "serverId":{"value":"game-01"},"proxyName":"craftov-01",\
        "gameId":{"value":"a1b2c3"},"mapId":{"value":"debug_zone"}}
        """;

    MatchAssignment decoded = codec.fromJson(legacyJson, MatchAssignment.class);

    assertEquals(playerId, decoded.teamKey());
    assertEquals(RaidMode.NORMAL, decoded.mode());
    assertEquals(RaidDifficulty.NORMAL, decoded.difficulty());
  }

  @Test
  void ticketAndProvisionRequestRoundTripRuleset() {
    MatchTicket ticket = new MatchTicket(UUID.randomUUID(), CraftovMapId.of("debug_zone"),
        Instant.ofEpochSecond(12), UUID.randomUUID(),
        RaidMode.CONFIDENTIAL, RaidDifficulty.EASY);
    ProvisionRequest request = new ProvisionRequest(
        ServerId.of("game-01"), CraftovMapId.of("debug_zone"),
        RaidMode.CONFIDENTIAL, RaidDifficulty.EASY);

    assertEquals(ticket, codec.fromJson(codec.toJson(ticket), MatchTicket.class));
    assertEquals(request, codec.fromJson(codec.toJson(request), ProvisionRequest.class));
  }

  @Test
  void oldSnapshotAndTicketWithoutRulesetDefaultToNormal() {
    String legacyTicket = """
        {"playerId":"6ba7b810-9dad-11d1-80b4-00c04fd430c8",\
        "mapId":{"value":"debug_zone"},"queuedAt":0,\
        "teamKey":"00000000-0000-0000-0000-000000000099"}
        """;
    String legacyInstance = """
        {"gameId":{"value":"a1b2c3"},"mapId":{"value":"debug_zone"},\
        "state":"WAITING","playerCount":0,"maxPlayers":12,"worldReady":true}
        """;

    MatchTicket ticket = codec.fromJson(legacyTicket, MatchTicket.class);
    InstanceSnapshot instance = codec.fromJson(legacyInstance, InstanceSnapshot.class);

    assertEquals(RaidMode.NORMAL, ticket.mode());
    assertEquals(RaidDifficulty.NORMAL, ticket.difficulty());
    assertEquals(RaidMode.NORMAL, instance.mode());
    assertEquals(RaidDifficulty.NORMAL, instance.difficulty());
  }

  @Nested
  @DisplayName("Instant adapter")
  class InstantAdapter {

    @Test
    void writesInstantAsEpochMillisNumber() {
      assertEquals("1723105530000", codec.toJson(Instant.ofEpochMilli(1_723_105_530_000L)));
    }

    @Test
    void readsEpochMillisNumberAsInstant() {
      assertEquals(Instant.ofEpochMilli(42L), codec.fromJson("42", Instant.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 1_723_105_530_000L})
    void roundTripsInstantInsideRecord(long epochMillis) {
      Timestamped value = new Timestamped(Instant.ofEpochMilli(epochMillis), "payload");
      assertEquals(value, codec.fromJson(codec.toJson(value), Timestamped.class));
    }
  }

  @Nested
  @DisplayName("parse failures")
  class ParseFailures {

    @Test
    void malformedJsonThrowsCraftovExceptionNamingTheTargetType() {
      CraftovException e = assertThrows(CraftovException.class,
          () -> codec.fromJson("{not valid json", GameServerSnapshot.class));
      assertTrue(e.getMessage().contains("GameServerSnapshot"),
          "message should name the target type");
      assertNotNull(e.getCause(), "underlying parser failure should be preserved");
    }

    @Test
    void nullLiteralThrowsCraftovException() {
      assertThrows(CraftovException.class,
          () -> codec.fromJson("null", MatchAssignment.class));
    }

    @Test
    void payloadViolatingRecordInvariantsThrowsCraftovException() {
      CraftovException e = assertThrows(CraftovException.class,
          () -> codec.fromJson("{\"value\":\"NOT VALID!\"}", ServerId.class));
      assertTrue(e.getMessage().contains("ServerId"), "message should name the target type");
    }
  }

  private record Timestamped(Instant at, String label) {}
}
