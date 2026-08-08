package com.birditch.raidcore.extraction;

import com.birditch.raidcore.api.extraction.ExtractionPoint;
import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.game.RaidPlayer;
import com.birditch.raidcore.api.game.RaidResult;
import com.birditch.raidcore.api.game.RaidTeam;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/// Minimal configurable [RaidGame] double for extraction tests: mutable state,
/// player list and extraction points; no teams, start time or result.
final class FakeRaidGame implements RaidGame {

    private final RaidGameId id;
    private final RaidMapId mapId;
    private final Instant createdAt;
    private final List<RaidPlayer> players = new ArrayList<>();
    private final List<ExtractionPoint> extractionPoints = new ArrayList<>();
    private RaidGameState state = RaidGameState.RUNNING;

    FakeRaidGame(RaidGameId id, RaidMapId mapId, Instant createdAt) {
        this.id = id;
        this.mapId = mapId;
        this.createdAt = createdAt;
    }

    void setState(RaidGameState state) {
        this.state = state;
    }

    void addPlayer(RaidPlayer player) {
        players.add(player);
    }

    void addExtractionPoint(ExtractionPoint point) {
        extractionPoints.add(point);
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
        return Collections.unmodifiableList(players);
    }

    @Override
    public Collection<RaidTeam> teams() {
        return List.of();
    }

    @Override
    public List<ExtractionPoint> extractionPoints() {
        return Collections.unmodifiableList(extractionPoints);
    }

    @Override
    public Instant createdAt() {
        return createdAt;
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
        return players.size();
    }
}
