package com.birditch.escapefromcraftov.api.combat;

import java.util.UUID;

/// Tracks per-player combat state. Untracked players are [PlayerCombatState#ALIVE].
public interface CombatService {

    PlayerCombatState stateOf(UUID playerId);

    void setState(UUID playerId, PlayerCombatState state);

    void reset(UUID playerId);
}
