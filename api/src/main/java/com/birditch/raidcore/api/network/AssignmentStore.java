package com.birditch.raidcore.api.network;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/// Stores pending match assignments until the player arrives on the target
/// game server.
public interface AssignmentStore {

    CompletableFuture<Void> storePending(MatchAssignment assignment, Duration ttl);

    /// Atomically fetches and removes the pending assignment (GETDEL) so an
    /// assignment is consumed at most once.
    CompletableFuture<Optional<MatchAssignment>> consumePending(UUID playerId);
}
