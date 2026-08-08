package com.birditch.raidcore.game;

import com.birditch.raidcore.api.RaidException;
import com.birditch.raidcore.api.extraction.ExtractionPoint;
import com.birditch.raidcore.api.game.GameStateException;
import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.game.RaidPlayer;
import com.birditch.raidcore.api.game.RaidResult;
import com.birditch.raidcore.api.game.RaidTeam;
import com.birditch.raidcore.api.scheduler.ScheduledTask;
import com.birditch.raidcore.common.check.Preconditions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// Mutable state of a single raid game, starting in [RaidGameState#CREATED].
///
/// NOT thread-safe — every access is main-thread confined.
public final class RaidGameInstance implements RaidGame {

    private final RaidGameId id;
    private final RaidMapId mapId;
    private final Instant createdAt;
    private final List<ExtractionPoint> extractionPoints;
    private final Map<UUID, RaidPlayer> players = new LinkedHashMap<>();
    private final List<RaidTeam> teams = new ArrayList<>();
    private final Set<UUID> extractedPlayerIds = new LinkedHashSet<>();
    private RaidGameState state = RaidGameState.CREATED;
    private @Nullable Instant startedAt;
    private @Nullable RaidResult result;
    private @Nullable ScheduledTask endTask;

    public RaidGameInstance(RaidGameId id, RaidMapId mapId, Instant createdAt,
                            List<ExtractionPoint> extractionPoints) {
        this.id = Preconditions.checkNotNull(id, "id");
        this.mapId = Preconditions.checkNotNull(mapId, "mapId");
        this.createdAt = Preconditions.checkNotNull(createdAt, "createdAt");
        Preconditions.checkNotNull(extractionPoints, "extractionPoints");
        this.extractionPoints = List.copyOf(extractionPoints);
    }

    /// Moves the game to `next`; entering [RaidGameState#RUNNING] records `now`
    /// as the start time.
    ///
    /// @throws GameStateException if the transition is illegal
    public void transitionTo(RaidGameState next, Instant now) {
        Preconditions.checkNotNull(next, "next");
        Preconditions.checkNotNull(now, "now");
        if (!state.canTransitionTo(next)) {
            throw new GameStateException(id, state, next);
        }
        state = next;
        if (next == RaidGameState.RUNNING) {
            startedAt = now;
        }
    }

    /// @throws RaidException unless the game is [RaidGameState#WAITING]
    /// @throws IllegalArgumentException if the player is already in this game
    public void addPlayer(RaidPlayer player) {
        Preconditions.checkNotNull(player, "player");
        if (state != RaidGameState.WAITING) {
            throw new RaidException(
                    "Players can only be added while WAITING, but game " + id.value()
                            + " is " + state);
        }
        if (players.containsKey(player.playerId())) {
            throw new IllegalArgumentException(
                    "Player already in game " + id.value() + ": " + player.playerId());
        }
        players.put(player.playerId(), player);
    }

    /// Removes the player from the game and any team membership.
    public Optional<RaidPlayer> removePlayer(UUID playerId) {
        Preconditions.checkNotNull(playerId, "playerId");
        RaidPlayer removed = players.remove(playerId);
        if (removed == null) {
            return Optional.empty();
        }
        teams.forEach(team -> team.removeMember(playerId));
        return Optional.of(removed);
    }

    public Optional<RaidPlayer> player(UUID playerId) {
        Preconditions.checkNotNull(playerId, "playerId");
        return Optional.ofNullable(players.get(playerId));
    }

    /// Records the extraction; the record survives a later [#removePlayer] so
    /// the final [RaidResult] still lists the player as extracted.
    public void markExtracted(UUID playerId) {
        Preconditions.checkNotNull(playerId, "playerId");
        extractedPlayerIds.add(playerId);
        player(playerId).ifPresent(RaidPlayer::markExtracted);
    }

    public Set<UUID> extractedPlayerIds() {
        return Collections.unmodifiableSet(extractedPlayerIds);
    }

    /// Returns the first team with free capacity, creating a new team
    /// (ids assigned from 1 upwards) when every existing team is full.
    public RaidTeam teamWithSpaceOrCreate(int capacity) {
        Preconditions.checkArgument(capacity >= 1, "capacity must be at least 1");
        for (RaidTeam team : teams) {
            if (!team.isFull()) {
                return team;
            }
        }
        RaidTeam team = new RaidTeam(teams.size() + 1, capacity);
        teams.add(team);
        return team;
    }

    public void setResult(RaidResult result) {
        this.result = Preconditions.checkNotNull(result, "result");
    }

    public Optional<ScheduledTask> endTask() {
        return Optional.ofNullable(endTask);
    }

    public void setEndTask(ScheduledTask task) {
        endTask = Preconditions.checkNotNull(task, "task");
    }

    /// Cancels and clears the scheduled end task, if any.
    public void cancelEndTask() {
        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }
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
        return Collections.unmodifiableCollection(players.values());
    }

    @Override
    public Collection<RaidTeam> teams() {
        return Collections.unmodifiableList(teams);
    }

    @Override
    public List<ExtractionPoint> extractionPoints() {
        return extractionPoints;
    }

    @Override
    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    @Override
    public Optional<RaidResult> result() {
        return Optional.ofNullable(result);
    }

    @Override
    public int playerCount() {
        return players.size();
    }
}
