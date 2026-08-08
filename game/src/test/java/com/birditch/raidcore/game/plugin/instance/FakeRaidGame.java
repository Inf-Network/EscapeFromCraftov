package com.birditch.raidcore.game.plugin.instance;

import com.birditch.raidcore.api.extraction.ExtractionPoint;
import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.game.RaidPlayer;
import com.birditch.raidcore.api.game.RaidResult;
import com.birditch.raidcore.api.game.RaidTeam;
import com.birditch.raidcore.common.check.Preconditions;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/// Minimal configurable [RaidGame] double for game-plugin tests: mutable
/// state and player count; no players, teams, extraction points, start time
/// or result. Public so the sibling test packages (`network`) can share it.
public final class FakeRaidGame implements RaidGame {

  private final RaidGameId id;
  private final RaidMapId mapId;
  private RaidGameState state = RaidGameState.WAITING;
  private int playerCount;

  public FakeRaidGame(RaidGameId id, RaidMapId mapId) {
    this.id = Preconditions.checkNotNull(id, "id");
    this.mapId = Preconditions.checkNotNull(mapId, "mapId");
  }

  public void setState(RaidGameState state) {
    this.state = Preconditions.checkNotNull(state, "state");
  }

  public void setPlayerCount(int playerCount) {
    Preconditions.checkArgument(playerCount >= 0, "playerCount must not be negative");
    this.playerCount = playerCount;
  }

  @Override
  public RaidGameId id() {
    return id;
  }

  @Override
  public RaidMapId mapId() {
    return mapId;
  }

  @Override
  public RaidGameState state() {
    return state;
  }

  @Override
  public Collection<RaidPlayer> players() {
    return List.of();
  }

  @Override
  public Collection<RaidTeam> teams() {
    return List.of();
  }

  @Override
  public List<ExtractionPoint> extractionPoints() {
    return List.of();
  }

  @Override
  public Instant createdAt() {
    return Instant.EPOCH;
  }

  @Override
  public Optional<Instant> startedAt() {
    return Optional.empty();
  }

  @Override
  public Optional<RaidResult> result() {
    return Optional.empty();
  }

  @Override
  public int playerCount() {
    return playerCount;
  }
}
