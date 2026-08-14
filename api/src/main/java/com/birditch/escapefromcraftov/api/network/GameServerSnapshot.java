package com.birditch.escapefromcraftov.api.network;

import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;

/// Heartbeat snapshot a game server publishes to the instance directory;
/// `instances` is stored as an immutable copy.
public record GameServerSnapshot(ServerId serverId, String proxyName, long updatedAtMillis,
                                 int maxInstances, List<InstanceSnapshot> instances) {

    public GameServerSnapshot {
        Preconditions.checkNotNull(serverId, "serverId");
        Preconditions.checkNotNull(proxyName, "proxyName");
        Preconditions.checkArgument(!proxyName.isBlank(), "proxyName must not be blank");
        Preconditions.checkNotNull(instances, "instances");
        Preconditions.checkArgument(updatedAtMillis >= 0,
                "updatedAtMillis must not be negative but was: " + updatedAtMillis);
        Preconditions.checkArgument(maxInstances >= 1,
                "maxInstances must be at least 1 but was: " + maxInstances);
        instances = List.copyOf(instances);
    }

    /// Whether any advertised instance is still joinable: `WAITING`, world
    /// ready and below its player cap.
    public boolean hasCapacity() {
        return instances.stream().anyMatch(instance -> instance.state() == CraftovGameState.WAITING
                && instance.worldReady()
                && instance.playerCount() < instance.maxPlayers());
    }
}
