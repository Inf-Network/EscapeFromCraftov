package com.birditch.escapefromcraftov.api.profile;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.time.Instant;
import java.util.UUID;

/// Mutable persistent state of a player.
///
/// Threading: mutation is MAIN-THREAD only; repositories persist copies off-thread.
public final class PlayerProfile {

    private final UUID playerId;
    private final Instant createdAt;
    private String name;
    private Money balance;
    private int level;
    private long experience;

    public PlayerProfile(UUID playerId, String name, Money balance, int level, Instant createdAt) {
        this(playerId, name, balance, level, 0L, createdAt);
    }

    public PlayerProfile(UUID playerId, String name, Money balance, int level,
                         long experience, Instant createdAt) {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(name, "name");
        Preconditions.checkArgument(!name.isBlank(), "name must not be blank");
        Preconditions.checkNotNull(balance, "balance");
        Preconditions.checkArgument(level >= 1, "level must be at least 1");
        Preconditions.checkArgument(experience >= 0, "experience must not be negative");
        Preconditions.checkNotNull(createdAt, "createdAt");
        this.playerId = playerId;
        this.name = name;
        this.balance = balance;
        this.level = level;
        this.experience = experience;
        this.createdAt = createdAt;
    }

    /// New profile with zero balance and level 1, created at `now`.
    public static PlayerProfile createDefault(UUID playerId, String name, Instant now) {
        return new PlayerProfile(playerId, name, Money.ZERO, 1, now);
    }

    public UUID playerId() {
        return playerId;
    }

    public String name() {
        return name;
    }

    public Money balance() {
        return balance;
    }

    public int level() {
        return level;
    }

    public long experience() {
        return experience;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public void rename(String name) {
        Preconditions.checkNotNull(name, "name");
        Preconditions.checkArgument(!name.isBlank(), "name must not be blank");
        this.name = name;
    }

    public void setBalance(Money balance) {
        Preconditions.checkNotNull(balance, "balance");
        this.balance = balance;
    }

    public void setLevel(int level) {
        Preconditions.checkArgument(level >= 1, "level must be at least 1");
        this.level = level;
    }

    public void setProgression(long experience, int level) {
        Preconditions.checkArgument(experience >= 0, "experience must not be negative");
        Preconditions.checkArgument(level >= 1, "level must be at least 1");
        this.experience = experience;
        this.level = level;
    }
}
