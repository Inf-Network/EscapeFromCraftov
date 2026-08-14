package com.birditch.escapefromcraftov.game.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.combat.CombatService;
import com.birditch.escapefromcraftov.api.combat.PlayerCombatState;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RaidInventoryMutationGateTest {

  @Test
  void onlyAliveNonPendingNonSettlingPlayersMayMutateRaidInventory() {
    UUID player = UUID.randomUUID();
    StubCombat combat = new StubCombat();
    Set<UUID> pending = new java.util.HashSet<>();
    Set<UUID> settling = new java.util.HashSet<>();
    RaidInventoryMutationGate gate = new RaidInventoryMutationGate(
        combat, pending::contains, settling::contains);

    assertTrue(gate.allows(player));
    combat.setState(player, PlayerCombatState.DOWNED);
    assertFalse(gate.allows(player));
    combat.setState(player, PlayerCombatState.DEAD);
    assertFalse(gate.allows(player));
    combat.setState(player, PlayerCombatState.ALIVE);
    pending.add(player);
    assertFalse(gate.allows(player));
    pending.clear();
    settling.add(player);
    assertFalse(gate.allows(player));
  }

  private static final class StubCombat implements CombatService {
    private final Map<UUID, PlayerCombatState> states = new HashMap<>();

    @Override
    public PlayerCombatState stateOf(UUID playerId) {
      return states.getOrDefault(playerId, PlayerCombatState.ALIVE);
    }

    @Override
    public void setState(UUID playerId, PlayerCombatState state) {
      states.put(playerId, state);
    }

    @Override
    public void reset(UUID playerId) {
      states.remove(playerId);
    }
  }
}
