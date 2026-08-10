package com.birditch.raidcore.api.profile;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/// Caching facade over [ProfileRepository]. Cached access ([#find], [#require])
/// is main-thread only; futures complete off-thread.
public interface ProfileService {

    /// Loads (or creates) the profile and caches it; renames when `playerName` changed.
    CompletableFuture<PlayerProfile> load(UUID playerId, String playerName);

    Optional<PlayerProfile> find(UUID playerId);

    /// @throws ProfileException if the profile is not loaded
    PlayerProfile require(UUID playerId);

    CompletableFuture<Void> save(UUID playerId);

    /// Saves then evicts the cached profile.
    CompletableFuture<Void> unload(UUID playerId);

    /// Evicts without persisting. Used when a wider player-data load failed and
    /// no partially initialized/default state may be written back.
    void discard(UUID playerId);

    CompletableFuture<Void> saveAll();

    int cachedCount();
}
