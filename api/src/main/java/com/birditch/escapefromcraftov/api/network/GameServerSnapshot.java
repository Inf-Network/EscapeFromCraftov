package com.birditch.escapefromcraftov.api.network;

import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Heartbeat snapshot a game server publishes to the instance directory;
/// `instances` is stored as an immutable copy.
public record GameServerSnapshot(ServerId serverId, String proxyName, long updatedAtMillis,
                                 int maxInstances, List<InstanceSnapshot> instances,
                                 Set<String> capabilities,
                                 List<MapActionTierCatalog> actionTierCatalog) {

    /// Source/wire compatibility for snapshots created before capability discovery.
    public GameServerSnapshot(ServerId serverId, String proxyName, long updatedAtMillis,
                              int maxInstances, List<InstanceSnapshot> instances) {
        this(serverId, proxyName, updatedAtMillis, maxInstances, instances, Set.of(), List.of());
    }

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
        // Gson supplies null for fields absent from an old Redis payload.
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        actionTierCatalog = actionTierCatalog == null ? List.of() : List.copyOf(actionTierCatalog);
        HashSet<CraftovMapId> mapIds = new HashSet<>();
        for (MapActionTierCatalog catalog : actionTierCatalog) {
            Preconditions.checkArgument(mapIds.add(catalog.mapId()),
                    "actionTierCatalog must not contain duplicate map: " + catalog.mapId().value());
        }
    }

    public boolean supports(String capability) {
        return capabilities.contains(Preconditions.checkNotNull(capability, "capability"));
    }

    /// Whether any advertised instance is still joinable: `WAITING`, world
    /// ready and below its player cap.
    public boolean hasCapacity() {
        return instances.stream().anyMatch(instance -> instance.state() == CraftovGameState.WAITING
                && instance.worldReady()
                && instance.playerCount() < instance.maxPlayers());
    }
}
