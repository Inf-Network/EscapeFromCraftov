package com.birditch.escapefromcraftov.game.plugin.deathcrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeathCrateStateTest {

  @Test
  void enforcesFiftyFourStackBoundaryAndCopiesCapturedItems() {
    List<TestItem> maximum = new ArrayList<>();
    for (int index = 0; index < DeathCrateState.CAPACITY; index++) {
      maximum.add(new TestItem(index, 1));
    }

    DeathCrateState<TestItem> state = new DeathCrateState<>(maximum, TestItem::copy);
    assertEquals(DeathCrateState.CAPACITY, state.itemCount());
    assertEquals(2, state.pageCount(45));
    assertNotSame(maximum.getFirst(), state.item(0).orElseThrow());

    maximum.add(new TestItem(55, 1));
    assertThrows(IllegalArgumentException.class,
        () -> new DeathCrateState<>(maximum, TestItem::copy));
  }

  @Test
  void takeAndRollbackKeepTheExactSlotWithoutDuplicatingContents() {
    TestItem first = new TestItem(1, 7);
    TestItem last = new TestItem(46, 2);
    List<TestItem> captured = new ArrayList<>();
    captured.add(first);
    for (int slot = 1; slot < 45; slot++) captured.add(new TestItem(slot + 1, 1));
    captured.add(last);
    DeathCrateState<TestItem> state = new DeathCrateState<>(captured, TestItem::copy);

    TestItem taken = state.take(45).orElseThrow();
    assertEquals(last, taken);
    assertEquals(45, state.itemCount());
    assertEquals(1, state.pageCount(45));
    assertTrue(state.item(45).isEmpty());

    state.restore(45, taken);
    assertEquals(46, state.itemCount());
    assertEquals(2, state.pageCount(45));
    assertEquals(last, state.item(45).orElseThrow());
    assertThrows(IllegalStateException.class, () -> state.restore(45, taken));

    for (int slot = 0; slot < 46; slot++) state.take(slot);
    assertTrue(state.isEmpty());
    assertFalse(state.snapshot().iterator().hasNext());
  }

  private record TestItem(int id, int amount) {

    private TestItem copy() {
      return new TestItem(id, amount);
    }
  }
}
