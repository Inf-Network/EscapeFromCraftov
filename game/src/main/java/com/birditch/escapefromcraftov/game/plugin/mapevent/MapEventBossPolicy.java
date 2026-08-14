package com.birditch.escapefromcraftov.game.plugin.mapevent;

import com.birditch.escapefromcraftov.api.game.CraftovMapEvent;
import com.birditch.escapefromcraftov.api.game.CraftovMapEventType;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;

/** Independent runtime validation for untrusted/deserialized boss multipliers. */
public final class MapEventBossPolicy {

  private MapEventBossPolicy() {}

  public static boolean valid(@Nullable CraftovMapEvent event) {
    return event != null && event.enabled()
        && event.type() == CraftovMapEventType.HIGH_VALUE_TARGET
        && finiteInRange(event.healthMultiplier(), 0, 20)
        && finiteInRange(event.damageMultiplier(), 0, 8);
  }

  public static boolean matchesPdc(CraftovMapEvent event, @Nullable String eventId,
      Double healthMultiplier, Double damageMultiplier) {
    return valid(event) && event.id().equals(eventId)
        && healthMultiplier != null && damageMultiplier != null
        && Double.compare(event.healthMultiplier(), healthMultiplier) == 0
        && Double.compare(event.damageMultiplier(), damageMultiplier) == 0;
  }

  /** Applies the event damage channel once and rejects overflow/tampered values. */
  public static OptionalDouble scaleDamage(double baseDamage, @Nullable CraftovMapEvent event) {
    if (!valid(event) || !Double.isFinite(baseDamage) || baseDamage < 0) {
      return OptionalDouble.empty();
    }
    double scaled = baseDamage * event.damageMultiplier();
    return Double.isFinite(scaled) ? OptionalDouble.of(scaled) : OptionalDouble.empty();
  }

  public static OptionalDouble projectileMultiplier(double ordinaryMultiplier,
      @Nullable CraftovMapEvent event) {
    if (!valid(event) || !Double.isFinite(ordinaryMultiplier) || ordinaryMultiplier <= 0) {
      return OptionalDouble.empty();
    }
    double combined = ordinaryMultiplier * event.damageMultiplier();
    return Double.isFinite(combined) ? OptionalDouble.of(combined) : OptionalDouble.empty();
  }

  private static boolean finiteInRange(double value, double exclusiveMinimum,
      double inclusiveMaximum) {
    return Double.isFinite(value) && value > exclusiveMinimum && value <= inclusiveMaximum;
  }
}
