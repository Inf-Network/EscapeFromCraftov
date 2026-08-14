package com.birditch.escapefromcraftov.game.plugin.secure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecureContainerStateTest {

  @Test
  void threeSlotsAreIsolatedAndSettlementSnapshotIsRetryStable() {
    SecureContainerState state = new SecureContainerState();
    ItemInstance first = item("emerald");
    ItemInstance second = item("echo_shard");
    ItemInstance third = item("ancient_debris");

    assertEquals(0, state.store(first));
    assertEquals(1, state.store(second));
    assertEquals(2, state.store(third));
    assertEquals(-1, state.store(item("arrow")));

    List<ItemInstance> snapshot = state.beginSettlement();
    assertSame(snapshot, state.beginSettlement(), "a retry must reuse the exact frozen snapshot");
    assertEquals(List.of(first, second, third), snapshot);
    assertThrows(IllegalStateException.class, () -> state.take(0));
  }

  @Test
  void failedAuthorizationUnsealsTheSameContents() {
    SecureContainerState state = new SecureContainerState();
    ItemInstance item = item("emerald");
    state.store(item);
    state.beginSettlement();

    state.cancelSettlement();

    assertFalse(state.sealed());
    assertEquals(item, state.take(0));
    assertTrue(state.contents().isEmpty());
  }

  @Test
  void conditionalTakeDoesNotDeleteAReplacedSource() {
    SecureContainerState state = new SecureContainerState();
    ItemInstance stored = item("emerald");
    state.store(stored);

    assertFalse(state.takeIfSame(0, item("emerald")));
    assertEquals(stored, state.item(0).orElseThrow());
    assertTrue(state.takeIfSame(0, stored));
    assertTrue(state.contents().isEmpty());
  }

  private static ItemInstance item(String id) {
    return new ItemInstance(UUID.randomUUID(), ItemId.of(id), 1, "payload:" + id);
  }
}
