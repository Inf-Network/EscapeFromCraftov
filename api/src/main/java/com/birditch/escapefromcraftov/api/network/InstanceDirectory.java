package com.birditch.escapefromcraftov.api.network;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/// Shared directory of game servers. Snapshots expire after `ttl` — publishers
/// must re-publish on a heartbeat to stay listed.
public interface InstanceDirectory {

    CompletableFuture<Void> publish(GameServerSnapshot snapshot, Duration ttl);

    /// All servers whose snapshot has not yet expired.
    CompletableFuture<List<GameServerSnapshot>> activeServers();
}
