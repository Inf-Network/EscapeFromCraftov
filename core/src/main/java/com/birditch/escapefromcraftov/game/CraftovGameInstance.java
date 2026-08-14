package com.birditch.escapefromcraftov.game;

import com.birditch.escapefromcraftov.api.CraftovException;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.game.GameStateException;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovExtractionArea;
import com.birditch.escapefromcraftov.api.game.CraftovAiZone;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.CraftovLootPoint;
import com.birditch.escapefromcraftov.api.game.CraftovLootZone;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovPoi;
import com.birditch.escapefromcraftov.api.game.CraftovResult;
import com.birditch.escapefromcraftov.api.game.CraftovTeam;
import com.birditch.escapefromcraftov.api.game.CraftovSpawnLocation;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.common.check.Preconditions;
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

/// Mutable state of a single craftov game, starting in [CraftovGameState#CREATED].
///
/// NOT thread-safe — every access is main-thread confined.
public final class CraftovGameInstance implements CraftovGame {

    private final CraftovGameId id;
    private final CraftovMapId mapId;
    private final Instant createdAt;
    private final long mapRevision;
    private final List<CraftovSpawnLocation> spawnLocations;
    private final List<CraftovLootPoint> lootPoints;
    private final List<CraftovLootZone> lootZones;
    private final List<ExtractionPoint> extractionPoints;
    private final List<CraftovExtractionArea> extractionAreas;
    private final List<CraftovAiZone> aiZones;
    private final List<CraftovPoi> pois;
    private final Map<UUID, CraftovPlayer> players = new LinkedHashMap<>();
    private final List<CraftovTeam> teams = new ArrayList<>();
    private final Map<UUID, CraftovTeam> teamsByKey = new LinkedHashMap<>();
    private final Set<UUID> extractedPlayerIds = new LinkedHashSet<>();
    private CraftovGameState state = CraftovGameState.CREATED;
    private @Nullable Instant startedAt;
    private @Nullable CraftovResult result;
    private @Nullable ScheduledTask endTask;

    public CraftovGameInstance(CraftovGameId id, CraftovMapId mapId, Instant createdAt,
                            List<ExtractionPoint> extractionPoints) {
        this(id, mapId, createdAt,
                new CraftovMapRuntime(0, List.of(), List.of(), extractionPoints, List.of()));
    }

    public CraftovGameInstance(CraftovGameId id, CraftovMapId mapId, Instant createdAt,
                            CraftovMapRuntime runtime) {
        this.id = Preconditions.checkNotNull(id, "id");
        this.mapId = Preconditions.checkNotNull(mapId, "mapId");
        this.createdAt = Preconditions.checkNotNull(createdAt, "createdAt");
        Preconditions.checkNotNull(runtime, "runtime");
        this.mapRevision = runtime.revision();
        this.spawnLocations = runtime.spawns();
        this.lootPoints = runtime.lootPoints();
        this.lootZones = runtime.lootZones();
        this.extractionPoints = runtime.extractionPoints();
        this.extractionAreas = runtime.extractionAreas();
        this.aiZones = runtime.aiZones();
        this.pois = runtime.pois();
    }

    /// Moves the game to `next`; entering [CraftovGameState#RUNNING] records `now`
    /// as the start time.
    ///
    /// @throws GameStateException if the transition is illegal
    public void transitionTo(CraftovGameState next, Instant now) {
        Preconditions.checkNotNull(next, "next");
        Preconditions.checkNotNull(now, "now");
        if (!state.canTransitionTo(next)) {
            throw new GameStateException(id, state, next);
        }
        state = next;
        if (next == CraftovGameState.RUNNING) {
            startedAt = now;
        }
    }

    /// @throws CraftovException unless the game is [CraftovGameState#WAITING]
    /// @throws IllegalArgumentException if the player is already in this game
    public void addPlayer(CraftovPlayer player) {
        Preconditions.checkNotNull(player, "player");
        if (state != CraftovGameState.WAITING) {
            throw new CraftovException(
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
    public Optional<CraftovPlayer> removePlayer(UUID playerId) {
        Preconditions.checkNotNull(playerId, "playerId");
        CraftovPlayer removed = players.remove(playerId);
        if (removed == null) {
            return Optional.empty();
        }
        teams.forEach(team -> team.removeMember(playerId));
        teamsByKey.entrySet().removeIf(entry -> entry.getValue().size() == 0);
        return Optional.of(removed);
    }

    public Optional<CraftovPlayer> player(UUID playerId) {
        Preconditions.checkNotNull(playerId, "playerId");
        return Optional.ofNullable(players.get(playerId));
    }

    /// Records the extraction; the record survives a later [#removePlayer] so
    /// the final [CraftovResult] still lists the player as extracted.
    public void markExtracted(UUID playerId) {
        Preconditions.checkNotNull(playerId, "playerId");
        extractedPlayerIds.add(playerId);
        player(playerId).ifPresent(CraftovPlayer::markExtracted);
    }

    public Set<UUID> extractedPlayerIds() {
        return Collections.unmodifiableSet(extractedPlayerIds);
    }

    /// Returns the first team with free capacity, creating a new team
    /// (ids assigned from 1 upwards) when every existing team is full.
    public CraftovTeam teamWithSpaceOrCreate(int capacity) {
        Preconditions.checkArgument(capacity >= 1, "capacity must be at least 1");
        for (CraftovTeam team : teams) {
            if (!team.isFull()) {
                return team;
            }
        }
        CraftovTeam team = new CraftovTeam(teams.size() + 1, capacity);
        teams.add(team);
        return team;
    }

    /// Returns the team deliberately associated with [teamKey], or creates a
    /// new one. Unlike [#teamWithSpaceOrCreate], this never fills unrelated
    /// teams by arrival order: only tickets carrying the same key become allies.
    public CraftovTeam teamForKeyOrCreate(UUID teamKey, int capacity) {
        Preconditions.checkNotNull(teamKey, "teamKey");
        Preconditions.checkArgument(capacity >= 1, "capacity must be at least 1");
        CraftovTeam existing = teamsByKey.get(teamKey);
        if (existing != null) {
            return existing;
        }
        CraftovTeam created = new CraftovTeam(teams.size() + 1, capacity);
        teams.add(created);
        teamsByKey.put(teamKey, created);
        return created;
    }

    public void setResult(CraftovResult result) {
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
    public CraftovGameId id() {
        return id;
    }

    @Override
    public CraftovMapId mapId() {
        return mapId;
    }

    @Override
    public long mapRevision() {
        return mapRevision;
    }

    @Override
    public List<CraftovSpawnLocation> spawnLocations() {
        return spawnLocations;
    }

    @Override
    public List<CraftovExtractionArea> extractionAreas() {
        return extractionAreas;
    }

    @Override
    public List<CraftovLootPoint> lootPoints() {
        return lootPoints;
    }

    @Override
    public List<CraftovLootZone> lootZones() {
        return lootZones;
    }

    @Override
    public List<CraftovAiZone> aiZones() {
        return aiZones;
    }

    @Override
    public List<CraftovPoi> pois() {
        return pois;
    }

    @Override
    public CraftovGameState state() {
        return state;
    }

    @Override
    public Collection<CraftovPlayer> players() {
        return Collections.unmodifiableCollection(players.values());
    }

    @Override
    public Collection<CraftovTeam> teams() {
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
    public Optional<CraftovResult> result() {
        return Optional.ofNullable(result);
    }

    @Override
    public int playerCount() {
        return players.size();
    }
}
