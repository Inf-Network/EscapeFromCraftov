package com.birditch.raidcore.profile;

import com.birditch.raidcore.api.profile.PlayerProfile;
import com.birditch.raidcore.api.profile.ProfileRepository;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/// In-memory [ProfileRepository] for development and tests.
///
/// Stores deep copies in both directions so the mutable [PlayerProfile] instances
/// held by callers never alias stored state. All returned futures are already
/// completed.
public final class InMemoryProfileRepository implements ProfileRepository {

  private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();

  @Override
  public CompletableFuture<Optional<PlayerProfile>> load(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return CompletableFuture.completedFuture(
        Optional.ofNullable(profiles.get(playerId)).map(InMemoryProfileRepository::copyOf));
  }

  @Override
  public CompletableFuture<Void> save(PlayerProfile profile) {
    Preconditions.checkNotNull(profile, "profile");
    profiles.put(profile.playerId(), copyOf(profile));
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Void> delete(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    profiles.remove(playerId);
    return CompletableFuture.completedFuture(null);
  }

  private static PlayerProfile copyOf(PlayerProfile profile) {
    return new PlayerProfile(
        profile.playerId(), profile.name(), profile.balance(), profile.level(),
        profile.experience(), profile.createdAt());
  }
}
