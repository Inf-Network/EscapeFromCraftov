package com.birditch.escapefromcraftov.api.loot;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// Optional context for a loot roll; both components may be absent.
public record LootRollContext(@Nullable UUID playerId, @Nullable CraftovGameId gameId) {

    public static LootRollContext empty() {
        return new LootRollContext(null, null);
    }

    public static LootRollContext forPlayer(UUID playerId) {
        return new LootRollContext(playerId, null);
    }
}
