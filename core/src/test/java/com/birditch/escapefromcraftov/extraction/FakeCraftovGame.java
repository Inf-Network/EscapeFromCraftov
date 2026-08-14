package com.birditch.escapefromcraftov.extraction;

import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovResult;
import com.birditch.escapefromcraftov.api.game.CraftovTeam;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/// Minimal configurable [CraftovGame] double for extraction tests: mutable state,
/// player list and extraction points; no teams, start time or result.
final class FakeCraftovGame implements CraftovGame {

    private final CraftovGameId id;
    private final CraftovMapId mapId;
    private final Instant createdAt;
    private final List<CraftovPlayer> players = new ArrayList<>();
    private final List<ExtractionPoint> extractionPoints = new ArrayList<>();
    private CraftovGameState state = CraftovGameState.RUNNING;

    FakeCraftovGame(CraftovGameId id, CraftovMapId mapId, Instant createdAt) {
        this.id = id;
        this.mapId = mapId;
        this.createdAt = createdAt;
    }

    void setState(CraftovGameState state) {
        this.state = state;
    }

    void addPlayer(CraftovPlayer player) {
        players.add(player);
    }

    void addExtractionPoint(ExtractionPoint point) {
        extractionPoints.add(point);
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
        return Collections.unmodifiableList(players);
    }

    @Override
    public Collection<CraftovTeam> teams() {
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
    public Optional<CraftovResult> result() {
        return Optional.empty();
    }

    @Override
    public int playerCount() {
        return players.size();
    }
}
