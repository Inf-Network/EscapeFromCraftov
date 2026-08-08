package com.birditch.raidcore.api.loot;

import com.birditch.raidcore.api.game.RaidGameId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// Optional context for a loot roll; both components may be absent.
public record LootRollContext(@Nullable UUID playerId, @Nullable RaidGameId gameId) {

    public static LootRollContext empty() {
        return new LootRollContext(null, null);
    }

    public static LootRollContext forPlayer(UUID playerId) {
        return new LootRollContext(playerId, null);
    }
}
