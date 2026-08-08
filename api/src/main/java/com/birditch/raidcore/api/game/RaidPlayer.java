package com.birditch.raidcore.api.game;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.OptionalInt;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// A participant of a raid game. Main-thread confined.
public final class RaidPlayer {

    private final UUID playerId;
    private final String name;
    private @Nullable Integer teamId;
    private boolean extracted;

    public RaidPlayer(UUID playerId, String name) {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(name, "name");
        Preconditions.checkArgument(!name.isBlank(), "name must not be blank");
        this.playerId = playerId;
        this.name = name;
    }

    public UUID playerId() {
        return playerId;
    }

    public String name() {
        return name;
    }

    public OptionalInt teamId() {
        return teamId == null ? OptionalInt.empty() : OptionalInt.of(teamId);
    }

    public boolean extracted() {
        return extracted;
    }

    public void assignTeam(int teamId) {
        Preconditions.checkArgument(teamId >= 1, "teamId must be at least 1");
        this.teamId = teamId;
    }

    public void markExtracted() {
        extracted = true;
    }
}
