package com.birditch.raidcore.api.session;

import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.common.check.Preconditions;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// Runtime state of a connected player. Main-thread confined; profile state
/// starts at [ProfileLoadState#LOADING].
public final class PlayerSession {

    private final UUID playerId;
    private final String playerName;
    private final Instant createdAt;
    private ProfileLoadState profileState = ProfileLoadState.LOADING;
    private @Nullable RaidGameId currentGameId;

    public PlayerSession(UUID playerId, String playerName, Instant createdAt) {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(playerName, "playerName");
        Preconditions.checkArgument(!playerName.isBlank(), "playerName must not be blank");
        Preconditions.checkNotNull(createdAt, "createdAt");
        this.playerId = playerId;
        this.playerName = playerName;
        this.createdAt = createdAt;
    }

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public ProfileLoadState profileState() {
        return profileState;
    }

    public Optional<RaidGameId> currentGame() {
        return Optional.ofNullable(currentGameId);
    }

    public void markProfileReady() {
        profileState = ProfileLoadState.READY;
    }

    public void markProfileFailed() {
        profileState = ProfileLoadState.FAILED;
    }

    /// @throws IllegalStateException if the player is already in a game
    public void enterGame(RaidGameId gameId) {
        Preconditions.checkNotNull(gameId, "gameId");
        if (currentGameId != null) {
            throw new IllegalStateException("Player is already in a game");
        }
        currentGameId = gameId;
    }

    public void leaveGame() {
        currentGameId = null;
    }
}
