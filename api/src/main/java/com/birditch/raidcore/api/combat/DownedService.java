package com.birditch.raidcore.api.combat;

import com.birditch.raidcore.api.game.RaidGameId;
import java.util.UUID;

/// Down/revive flow layered on top of [CombatService].
public interface DownedService {

    boolean isDowned(UUID playerId);

    /// @throws IllegalStateException if the player is dead
    void down(UUID playerId, RaidGameId gameId);

    /// @throws IllegalStateException if the player is not downed
    void revive(UUID playerId, UUID rescuerId);
}
