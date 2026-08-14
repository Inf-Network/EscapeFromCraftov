package com.birditch.escapefromcraftov.api.network;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

/// Redis key and channel names shared by all servers on the network.
public final class NetworkKeys {

    /// Matches every key produced by [#serverKey(ServerId)].
    public static final String SERVER_KEY_PATTERN = "escapefromcraftov:servers:*";

    public static final String PROVISION_CHANNEL = "escapefromcraftov:provision";

    private NetworkKeys() {
    }

    public static String serverKey(ServerId serverId) {
        Preconditions.checkNotNull(serverId, "serverId");
        return "escapefromcraftov:servers:" + serverId.value();
    }

    public static String pendingKey(UUID playerId) {
        Preconditions.checkNotNull(playerId, "playerId");
        return "escapefromcraftov:pending:" + playerId;
    }

}
