package com.birditch.escapefromcraftov.game.plugin.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.item.ItemRarity;
import com.birditch.escapefromcraftov.api.loot.LootContainerType;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LootRevealProgressTest {

  @Test
  void revealsInStableOrderAndSkipsItemsAlreadyTakenByATeammate() {
    LootRevealProgress progress = new LootRevealProgress(java.util.List.of(2, 8, 11));
    Set<Integer> present = new HashSet<>(Set.of(2, 8, 11));

    assertEquals(2, progress.nextPresentSlot(present::contains));
    progress.reveal(2);
    present.remove(8);

    assertEquals(11, progress.nextPresentSlot(present::contains));
    assertTrue(progress.isRevealed(2));
    assertTrue(progress.isRevealed(8));
    assertFalse(progress.isRevealed(11));

    progress.reveal(11);
    assertEquals(-1, progress.nextPresentSlot(present::contains));
    assertEquals(3, progress.completedCount());
  }

  @Test
  void timingGetsSlowerForRarerItemsHigherTiersAndHarderContainers() {
    long common = LootRevealPolicy.delayMillis(
        LootContainerType.CHEST, 1, ItemRarity.COMMON);
    long legendary = LootRevealPolicy.delayMillis(
        LootContainerType.CHEST, 1, ItemRarity.LEGENDARY);
    long tierFour = LootRevealPolicy.delayMillis(
        LootContainerType.CHEST, 4, ItemRarity.COMMON);
    long vault = LootRevealPolicy.delayMillis(
        LootContainerType.VAULT, 1, ItemRarity.COMMON);

    assertTrue(legendary > common);
    assertTrue(tierFour > common);
    assertTrue(vault > common);
  }

  @Test
  void progressObjectCanResumeWithoutRevealingUnknownSlots() {
    LootRevealProgress persisted = new LootRevealProgress(java.util.List.of(1, 4));
    persisted.reveal(persisted.nextPresentSlot(_ -> true));

    // Closing a view discards only its live task; the same per-container
    // progress object is reused when the player opens that container again.
    assertTrue(persisted.isRevealed(1));
    assertFalse(persisted.isRevealed(4));
    assertEquals(4, persisted.nextPresentSlot(_ -> true));
  }

  @Test
  void failedMenuRefreshCanRollBackTheLatestReveal() {
    LootRevealProgress progress = new LootRevealProgress(java.util.List.of(3, 7));
    int slot = progress.nextPresentSlot(_ -> true);
    progress.reveal(slot);

    progress.rollbackReveal(slot);

    assertFalse(progress.isRevealed(3));
    assertEquals(3, progress.nextPresentSlot(_ -> true));
  }
}
