package com.birditch.escapefromcraftov.game.plugin.instance;

import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovResult;
import com.birditch.escapefromcraftov.api.game.CraftovTeam;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/// Minimal configurable [CraftovGame] double for game-plugin tests: mutable
/// state and player count; no players, teams, extraction points, start time
/// or result. Public so the sibling test packages (`network`) can share it.
public final class FakeCraftovGame implements CraftovGame {

  private final CraftovGameId id;
  private final CraftovMapId mapId;
  private CraftovGameState state = CraftovGameState.WAITING;
  private int playerCount;

  public FakeCraftovGame(CraftovGameId id, CraftovMapId mapId) {
    this.id = Preconditions.checkNotNull(id, "id");
    this.mapId = Preconditions.checkNotNull(mapId, "mapId");
  }

  public void setState(CraftovGameState state) {
    this.state = Preconditions.checkNotNull(state, "state");
  }

  public void setPlayerCount(int playerCount) {
    Preconditions.checkArgument(playerCount >= 0, "playerCount must not be negative");
    this.playerCount = playerCount;
  }

  @Override
  public CraftovGameId id() {
    return id;
  }

  @Override
  public CraftovMapId mapId() {
    return mapId;
  }

  @Override
  public CraftovGameState state() {
    return state;
  }

  @Override
  public Collection<CraftovPlayer> players() {
    return List.of();
  }

  @Override
  public Collection<CraftovTeam> teams() {
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
  public Optional<CraftovResult> result() {
    return Optional.empty();
  }

  @Override
  public int playerCount() {
    return playerCount;
  }
}
