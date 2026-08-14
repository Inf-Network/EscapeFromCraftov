package com.birditch.escapefromcraftov.api.profile;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/// Async persistence boundary for profiles. Implementations run IO off the main
/// thread; callers must hop back to the main thread before touching domain state.
public interface ProfileRepository {

    CompletableFuture<Optional<PlayerProfile>> load(UUID playerId);

    CompletableFuture<Void> save(PlayerProfile profile);

    CompletableFuture<Void> delete(UUID playerId);
}
