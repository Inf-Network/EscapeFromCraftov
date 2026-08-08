package com.birditch.raidcore.profile;

import com.birditch.raidcore.api.profile.PlayerProfile;
import com.birditch.raidcore.api.profile.ProfileException;
import com.birditch.raidcore.api.profile.ProfileRepository;
import com.birditch.raidcore.api.profile.ProfileService;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.time.TimeSource;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Caching [ProfileService] backed by a [ProfileRepository].
///
/// The cache map is concurrent so async load callbacks may publish into it, but
/// the cached [PlayerProfile] instances themselves are mutated on the main
/// thread only.
public final class DefaultProfileService implements ProfileService {

  private final ProfileRepository repository;
  private final TimeSource timeSource;
  private final Logger logger;
  private final Map<UUID, PlayerProfile> cache = new ConcurrentHashMap<>();

  public DefaultProfileService(ProfileRepository repository, TimeSource timeSource, Logger logger) {
    this.repository = Preconditions.checkNotNull(repository, "repository");
    this.timeSource = Preconditions.checkNotNull(timeSource, "timeSource");
    this.logger = Preconditions.checkNotNull(logger, "logger");
  }

  @Override
  public CompletableFuture<PlayerProfile> load(UUID playerId, String playerName) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(playerName, "playerName");
    return repository.load(playerId).thenApply(loaded -> cacheLoaded(playerId, playerName, loaded));
  }

  @Override
  public Optional<PlayerProfile> find(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return Optional.ofNullable(cache.get(playerId));
  }

  @Override
  public PlayerProfile require(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    PlayerProfile profile = cache.get(playerId);
    if (profile == null) {
      throw new ProfileException("Profile not loaded: " + playerId);
    }
    return profile;
  }

  @Override
  public CompletableFuture<Void> save(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    PlayerProfile profile = cache.get(playerId);
    if (profile == null) {
      return CompletableFuture.completedFuture(null);
    }
    return repository.save(profile);
  }

  @Override
  public CompletableFuture<Void> unload(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    PlayerProfile profile = cache.get(playerId);
    if (profile == null) {
      return CompletableFuture.completedFuture(null);
    }
    return repository
        .save(profile)
        .whenComplete(
            (_, error) -> {
              if (error != null) {
                logger.log(Level.SEVERE, "Failed to save profile " + playerId + " on unload", error);
              }
              cache.remove(playerId);
            });
  }

  @Override
  public CompletableFuture<Void> saveAll() {
    CompletableFuture<?>[] saves =
        cache.values().stream().map(repository::save).toArray(CompletableFuture[]::new);
    return CompletableFuture.allOf(saves);
  }

  @Override
  public int cachedCount() {
    return cache.size();
  }

  private PlayerProfile cacheLoaded(UUID playerId, String playerName, Optional<PlayerProfile> loaded) {
    PlayerProfile profile =
        loaded.orElseGet(() -> PlayerProfile.createDefault(playerId, playerName, timeSource.now()));
    if (!profile.name().equals(playerName)) {
      profile.rename(playerName);
    }
    cache.put(playerId, profile);
    return profile;
  }
}
