package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/// A capacity-bounded team within a craftov game. Main-thread confined.
public final class CraftovTeam {

    private final int teamId;
    private final int capacity;
    private final Set<UUID> members = new LinkedHashSet<>();

    public CraftovTeam(int teamId, int capacity) {
        Preconditions.checkArgument(teamId >= 1, "teamId must be at least 1");
        Preconditions.checkArgument(capacity >= 1, "capacity must be at least 1");
        this.teamId = teamId;
        this.capacity = capacity;
    }

    public int teamId() {
        return teamId;
    }

    public int capacity() {
        return capacity;
    }

    public boolean isFull() {
        return members.size() >= capacity;
    }

    /// Returns `false` when the team is full or the player is already a member.
    public boolean addMember(UUID playerId) {
        Preconditions.checkNotNull(playerId, "playerId");
        if (isFull()) {
            return false;
        }
        return members.add(playerId);
    }

    public boolean removeMember(UUID playerId) {
        return members.remove(playerId);
    }

    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    public int size() {
        return members.size();
    }
}
