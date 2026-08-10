package com.birditch.raidcore.api.settings;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/// Loads, caches and persists per-player settings.
public interface PlayerSettingsService {

    CompletableFuture<Void> load(UUID playerId);

    /// Reads from the cache; returns the key's default while the player is not
    /// loaded.
    boolean get(UUID playerId, SettingKey key);

    /// Persists asynchronously and updates the cache only after commit.
    CompletableFuture<Void> set(UUID playerId, SettingKey key, boolean value);

    void unload(UUID playerId);
}
