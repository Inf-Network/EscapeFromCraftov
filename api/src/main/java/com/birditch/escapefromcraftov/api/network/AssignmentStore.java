package com.birditch.escapefromcraftov.api.network;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/// Stores pending match assignments until the player arrives on the target
/// game server.
public interface AssignmentStore {

    CompletableFuture<Void> storePending(MatchAssignment assignment, Duration ttl);

    /// Removes a pending assignment when a queue group is cancelled while its
    /// Redis writes are in flight. Legacy stores may rely on TTL expiry.
    default CompletableFuture<Void> removePending(UUID playerId) {
        return CompletableFuture.completedFuture(null);
    }

    /// Atomically fetches and removes the pending assignment (GETDEL) so an
    /// assignment is consumed at most once.
    CompletableFuture<Optional<MatchAssignment>> consumePending(UUID playerId);
}
