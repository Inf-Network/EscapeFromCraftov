package com.birditch.escapefromcraftov.api.combat;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import java.util.UUID;

/// Down/revive flow layered on top of [CombatService].
public interface DownedService {

    boolean isDowned(UUID playerId);

    /// @throws IllegalStateException if the player is dead
    void down(UUID playerId, CraftovGameId gameId);

    /// @throws IllegalStateException if the player is not downed
    void revive(UUID playerId, UUID rescuerId);
}
