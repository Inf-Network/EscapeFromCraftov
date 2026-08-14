package com.birditch.escapefromcraftov.game.plugin.carry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.item.ItemId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RaidDropCommitPolicyTest {

  private static final UUID PLAYER = UUID.randomUUID();
  private static final UUID ENTITY = UUID.randomUUID();
  private static final UUID INSTANCE = UUID.randomUUID();
  private static final ItemId ITEM = ItemId.of("ammo");

  @Test
  void exactPartialAndFullSnapshotsCommitOnlyWhenTheEventSurvives() {
    var partial = stamp(1, 11, 12, 7);
    assertTrue(RaidDropCommitPolicy.mayCommit(false, partial, partial));
    assertFalse(RaidDropCommitPolicy.mayCommit(true, partial, partial));

    var full = stamp(12, 0, 12, 8);
    assertTrue(RaidDropCommitPolicy.mayCommit(false, full, full));
  }

  @Test
  void changedSlotEntityIdentityAmountOrLayoutVersionRejectsWithoutACommit() {
    var frozen = stamp(1, 11, 12, 7);
    assertFalse(RaidDropCommitPolicy.mayCommit(false, frozen,
        new RaidDropCommitPolicy.DropStamp(PLAYER, 1, ENTITY, INSTANCE, ITEM, 1, 11, 12, 7)));
    assertFalse(RaidDropCommitPolicy.mayCommit(false, frozen,
        new RaidDropCommitPolicy.DropStamp(PLAYER, 0, UUID.randomUUID(), INSTANCE, ITEM,
            1, 11, 12, 7)));
    assertFalse(RaidDropCommitPolicy.mayCommit(false, frozen,
        new RaidDropCommitPolicy.DropStamp(PLAYER, 0, ENTITY, UUID.randomUUID(), ITEM,
            1, 11, 12, 7)));
    assertFalse(RaidDropCommitPolicy.mayCommit(false, frozen, stamp(2, 10, 12, 7)));
    assertFalse(RaidDropCommitPolicy.mayCommit(false, frozen, stamp(1, 11, 12, 8)));
  }

  private static RaidDropCommitPolicy.DropStamp stamp(
      int dropped, int remaining, int original, long version) {
    return new RaidDropCommitPolicy.DropStamp(
        PLAYER, 0, ENTITY, INSTANCE, ITEM, dropped, remaining, original, version);
  }
}
