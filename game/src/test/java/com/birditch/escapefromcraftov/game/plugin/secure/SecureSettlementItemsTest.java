package com.birditch.escapefromcraftov.game.plugin.secure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecureSettlementItemsTest {

  @Test
  void extractionMergesOrdinaryAndProtectedItemsWithoutChangingIdentityOrPayload() {
    ItemInstance carried = item(UUID.randomUUID(), "emerald", "ordinary-meta");
    ItemInstance protectedItem = item(UUID.randomUUID(), "echo_shard", "secure-meta");

    List<ItemInstance> merged = SecureSettlementItems.merge(
        List.of(carried), List.of(protectedItem));

    assertEquals(List.of(carried, protectedItem), merged);
    assertEquals(protectedItem.instanceId(), merged.get(1).instanceId());
    assertEquals("secure-meta", merged.get(1).opaquePayload());
  }

  @Test
  void extractionRejectsOnePhysicalInstanceAppearingInBothInventories() {
    UUID instanceId = UUID.randomUUID();

    assertThrows(IllegalStateException.class, () -> SecureSettlementItems.merge(
        List.of(item(instanceId, "emerald", "ordinary")),
        List.of(item(instanceId, "emerald", "secure"))));
  }

  private static ItemInstance item(UUID id, String itemId, String payload) {
    return new ItemInstance(id, ItemId.of(itemId), 1, payload);
  }
}
