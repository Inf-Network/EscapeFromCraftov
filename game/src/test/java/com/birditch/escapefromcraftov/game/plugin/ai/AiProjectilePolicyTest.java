package com.birditch.escapefromcraftov.game.plugin.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AiProjectilePolicyTest {

  @Test
  void pdcOwnershipAndDamageScalingDoNotDependOnALiveShooterOrCompound() {
    AiProjectilePolicy.Ownership ownership = AiProjectilePolicy.ownership(
        "raid-17", "west_guards", 1.75).orElseThrow();
    assertEquals("raid-17", ownership.gameId());
    assertEquals("west_guards", ownership.zoneId());

    AiProjectilePolicy.ScaledDamage first = AiProjectilePolicy.scale(
        8.0, null, ownership.damageMultiplier()).orElseThrow();
    assertEquals(8.0, first.baseDamage());
    assertEquals(14.0, first.damage());

    // A second dispatch sees the stored original base, even if the event is
    // already carrying the first dispatch's scaled value.
    AiProjectilePolicy.ScaledDamage repeated = AiProjectilePolicy.scale(
        first.damage(), first.baseDamage(), ownership.damageMultiplier()).orElseThrow();
    assertEquals(14.0, repeated.damage());
  }

  @Test
  void projectileAndTargetMustBothRemainInsideTheOwningZoneLeash() {
    assertTrue(AiProjectilePolicy.eligible(new AiProjectilePolicy.HitFacts(
        true, true, true, true, true)));
    assertFalse(AiProjectilePolicy.eligible(new AiProjectilePolicy.HitFacts(
        true, true, true, false, true)));
    assertFalse(AiProjectilePolicy.eligible(new AiProjectilePolicy.HitFacts(
        true, true, true, true, false)));
    assertFalse(AiProjectilePolicy.eligible(new AiProjectilePolicy.HitFacts(
        false, true, true, true, true)));
  }

  @Test
  void malformedOwnershipAndNonFiniteDamageFailClosed() {
    assertTrue(AiProjectilePolicy.ownership(null, "zone", 1.0).isEmpty());
    assertTrue(AiProjectilePolicy.ownership("game", "", 1.0).isEmpty());
    assertTrue(AiProjectilePolicy.ownership("game", "zone", Double.NaN).isEmpty());
    assertTrue(AiProjectilePolicy.scale(Double.POSITIVE_INFINITY, null, 1.0).isEmpty());
    assertTrue(AiProjectilePolicy.scale(4.0, null, 0).isEmpty());
  }
}
