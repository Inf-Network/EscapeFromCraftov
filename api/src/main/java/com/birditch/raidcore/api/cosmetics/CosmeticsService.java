package com.birditch.raidcore.api.cosmetics;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/// Catalog lookup plus per-player equipped-cosmetic state.
public interface CosmeticsService {

    List<Cosmetic> catalog();

    CompletableFuture<Void> load(UUID playerId);

    /// Reads from the cache; empty while the player is not loaded or has
    /// nothing equipped.
    Optional<Cosmetic> equipped(UUID playerId);

    /// Fails the future with [IllegalArgumentException] when `id` is not in
    /// the catalog.
    CompletableFuture<Void> equip(UUID playerId, CosmeticId id);

    CompletableFuture<Void> unequip(UUID playerId);

    void unload(UUID playerId);
}
