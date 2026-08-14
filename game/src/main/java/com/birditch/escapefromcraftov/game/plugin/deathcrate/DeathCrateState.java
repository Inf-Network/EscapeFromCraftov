package com.birditch.escapefromcraftov.game.plugin.deathcrate;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Main-thread-confined contents of one read-only, shared death crate. */
final class DeathCrateState<T> {

  static final int CAPACITY = 54;

  private final Object[] items = new Object[CAPACITY];
  private final UnaryOperator<T> copier;
  private int itemCount;

  DeathCrateState(List<T> captured, UnaryOperator<T> copier) {
    Preconditions.checkNotNull(captured, "captured");
    this.copier = Preconditions.checkNotNull(copier, "copier");
    Preconditions.checkArgument(!captured.isEmpty(), "captured must not be empty");
    Preconditions.checkArgument(captured.size() <= CAPACITY,
        "a death crate cannot contain more than " + CAPACITY + " stacks");
    for (int slot = 0; slot < captured.size(); slot++) {
      T item = Preconditions.checkNotNull(captured.get(slot), "captured item");
      items[slot] = copy(item);
    }
    itemCount = captured.size();
  }

  Optional<T> item(int slot) {
    checkSlot(slot);
    T item = value(slot);
    return item == null ? Optional.empty() : Optional.of(copy(item));
  }

  Optional<T> take(int slot) {
    checkSlot(slot);
    T item = value(slot);
    if (item == null) return Optional.empty();
    items[slot] = null;
    itemCount--;
    return Optional.of(copy(item));
  }

  void restore(int slot, T item) {
    checkSlot(slot);
    Preconditions.checkNotNull(item, "item");
    Preconditions.checkState(items[slot] == null, "death crate slot is occupied: " + slot);
    items[slot] = copy(item);
    itemCount++;
  }

  int itemCount() {
    return itemCount;
  }

  boolean isEmpty() {
    return itemCount == 0;
  }

  int pageCount(int pageSize) {
    Preconditions.checkArgument(pageSize >= 1, "pageSize must be positive");
    int highest = -1;
    for (int slot = items.length - 1; slot >= 0; slot--) {
      if (items[slot] != null) {
        highest = slot;
        break;
      }
    }
    return Math.max(1, highest / pageSize + 1);
  }

  List<T> snapshot() {
    List<T> result = new ArrayList<>(itemCount);
    for (int slot = 0; slot < items.length; slot++) {
      T item = value(slot);
      if (item != null) result.add(copy(item));
    }
    return List.copyOf(result);
  }

  private T copy(T item) {
    return Preconditions.checkNotNull(copier.apply(item), "copied item");
  }

  @SuppressWarnings("unchecked")
  private T value(int slot) {
    return (T) items[slot];
  }

  private static void checkSlot(int slot) {
    Preconditions.checkArgument(slot >= 0 && slot < CAPACITY,
        "slot must be from 0.." + (CAPACITY - 1));
  }
}
