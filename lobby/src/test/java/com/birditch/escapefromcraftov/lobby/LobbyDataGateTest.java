package com.birditch.escapefromcraftov.lobby;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LobbyDataGateTest {

  @Test
  void partyPreflightAndProxyTransferContinuouslyLockRaidMutations() {
    LobbyDataGate gate = new LobbyDataGate();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();

    assertTrue(gate.beginRaidPreparation(List.of(first, second)));
    assertFalse(gate.beginRaidPreparation(List.of(second)));
    assertTrue(gate.isRaidMutationLocked(first));
    assertFalse(gate.isMatchTransferLocked(first));
    gate.finishRaidPreparation(List.of(first, second));
    assertFalse(gate.isRaidMutationLocked(first));

    long firstGeneration = gate.beginMatchTransfer(List.of(first, second));
    assertTrue(gate.isRaidMutationLocked(second));
    assertTrue(gate.isMatchTransferLocked(second));
    gate.clear(second);
    assertFalse(gate.isRaidMutationLocked(second));
    assertTrue(gate.isRaidMutationLocked(first));

    long secondGeneration = gate.beginMatchTransfer(List.of(first));
    gate.finishMatchTransfer(List.of(first, second), firstGeneration);
    assertTrue(gate.isRaidMutationLocked(first));
    gate.finishMatchTransfer(List.of(first), secondGeneration);
    assertFalse(gate.isRaidMutationLocked(first));
  }
}
