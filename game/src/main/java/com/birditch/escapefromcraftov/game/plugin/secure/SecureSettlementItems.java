package com.birditch.escapefromcraftov.game.plugin.secure;

import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Builds the single physical-item snapshot committed by extraction settlement. */
public final class SecureSettlementItems {

  private SecureSettlementItems() {}

  public static List<ItemInstance> merge(
      List<ItemInstance> carried, List<ItemInstance> protectedItems) {
    Preconditions.checkNotNull(carried, "carried");
    Preconditions.checkNotNull(protectedItems, "protectedItems");
    List<ItemInstance> merged = new ArrayList<>(carried.size() + protectedItems.size());
    Set<UUID> identities = new HashSet<>();
    for (ItemInstance item : carried) add(merged, identities, item);
    for (ItemInstance item : protectedItems) add(merged, identities, item);
    return List.copyOf(merged);
  }

  private static void add(List<ItemInstance> result, Set<UUID> identities, ItemInstance item) {
    Preconditions.checkNotNull(item, "item");
    if (!identities.add(item.instanceId())) {
      throw new IllegalStateException("普通背包与安全箱包含重复物品实例: " + item.instanceId());
    }
    result.add(item);
  }
}
