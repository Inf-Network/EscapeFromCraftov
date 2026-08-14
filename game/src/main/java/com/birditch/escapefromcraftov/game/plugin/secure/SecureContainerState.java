package com.birditch.escapefromcraftov.game.plugin.secure;

import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Main-thread state for one player's three-slot secure container in one craftov. */
final class SecureContainerState {

  static final int CAPACITY = 3;

  private final ItemInstance[] slots = new ItemInstance[CAPACITY];
  private @Nullable List<ItemInstance> settlementSnapshot;

  Optional<ItemInstance> item(int slot) {
    checkSlot(slot);
    return Optional.ofNullable(slots[slot]);
  }

  int store(ItemInstance item) {
    Preconditions.checkNotNull(item, "item");
    requireMutable();
    if (Arrays.stream(slots).filter(java.util.Objects::nonNull)
        .anyMatch(existing -> existing.instanceId().equals(item.instanceId()))) {
      throw new IllegalArgumentException("安全箱中已存在相同物品实例: " + item.instanceId());
    }
    for (int slot = 0; slot < slots.length; slot++) {
      if (slots[slot] != null) continue;
      slots[slot] = item;
      return slot;
    }
    return -1;
  }

  ItemInstance take(int slot) {
    checkSlot(slot);
    requireMutable();
    ItemInstance item = slots[slot];
    if (item == null) throw new IllegalStateException("安全箱槽位为空");
    slots[slot] = null;
    return item;
  }

  List<ItemInstance> contents() {
    List<ItemInstance> result = new ArrayList<>(CAPACITY);
    for (ItemInstance item : slots) if (item != null) result.add(item);
    return List.copyOf(result);
  }

  /** Freezes one immutable snapshot. Repeated settlement retries receive the same values. */
  List<ItemInstance> beginSettlement() {
    if (settlementSnapshot == null) settlementSnapshot = contents();
    return settlementSnapshot;
  }

  /** Used only when extraction authorization fails and the craftov continues. */
  void cancelSettlement() {
    settlementSnapshot = null;
  }

  boolean sealed() {
    return settlementSnapshot != null;
  }

  private void requireMutable() {
    if (sealed()) throw new IllegalStateException("安全箱正在结算");
  }

  private static void checkSlot(int slot) {
    Preconditions.checkArgument(slot >= 0 && slot < CAPACITY, "slot must be from 0..2");
  }
}
