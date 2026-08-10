package com.birditch.raidcore.api.network;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.UUID;

/// Redis key and channel names shared by all servers on the network.
public final class NetworkKeys {

    /// Matches every key produced by [#serverKey(ServerId)].
    public static final String SERVER_KEY_PATTERN = "raidcore:servers:*";

    public static final String PROVISION_CHANNEL = "raidcore:provision";

    private NetworkKeys() {
    }

    public static String serverKey(ServerId serverId) {
        Preconditions.checkNotNull(serverId, "serverId");
        return "raidcore:servers:" + serverId.value();
    }

    public static String pendingKey(UUID playerId) {
        Preconditions.checkNotNull(playerId, "playerId");
        return "raidcore:pending:" + playerId;
    }

}
