package com.birditch.escapefromcraftov.game.plugin.loot;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

/** Player-local identification progress over one immutable shared-container slot plan. */
final class LootRevealProgress {

  private final List<Integer> slots;
  private final Set<Integer> revealed = new HashSet<>();
  private int cursor;

  LootRevealProgress(List<Integer> slots) {
    this.slots = List.copyOf(Preconditions.checkNotNull(slots, "slots"));
    Preconditions.checkArgument(this.slots.stream().allMatch(slot -> slot >= 0),
        "slots must not contain negative values");
    Preconditions.checkArgument(new HashSet<>(this.slots).size() == this.slots.size(),
        "slots must be unique");
  }

  boolean isRevealed(int slot) {
    return revealed.contains(slot);
  }

  /**
   * Returns the next still-present slot. Slots taken by another player before
   * identification are consumed from the plan and never become ghost items.
   */
  int nextPresentSlot(IntPredicate present) {
    Preconditions.checkNotNull(present, "present");
    while (cursor < slots.size()) {
      int slot = slots.get(cursor);
      if (present.test(slot)) return slot;
      revealed.add(slot);
      cursor++;
    }
    return -1;
  }

  void reveal(int slot) {
    Preconditions.checkArgument(cursor < slots.size() && slots.get(cursor) == slot,
        "only the next planned slot can be revealed");
    revealed.add(slot);
    cursor++;
  }

  void rollbackReveal(int slot) {
    Preconditions.checkArgument(cursor > 0 && slots.get(cursor - 1) == slot,
        "only the most recently revealed slot can be rolled back");
    cursor--;
    revealed.remove(slot);
  }

  int completedCount() {
    return cursor;
  }

  int totalCount() {
    return slots.size();
  }
}
