package com.birditch.escapefromcraftov.game.plugin.medical;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Medical consumables understood by the raid condition system. */
public enum MedicalTreatment {
  BANDAGE("绷带", Duration.ofMillis(2_500), EnumSet.of(MedicalCondition.BLEEDING), 0),
  PAINKILLER("止痛药", Duration.ofSeconds(2), EnumSet.of(MedicalCondition.PAIN), 0),
  MEDICAL_KIT("医疗包", Duration.ofSeconds(4), EnumSet.allOf(MedicalCondition.class), 0.4);

  private final String displayName;
  private final Duration useDuration;
  private final Set<MedicalCondition> curedConditions;
  private final double healingFraction;

  MedicalTreatment(String displayName, Duration useDuration,
      Set<MedicalCondition> curedConditions, double healingFraction) {
    this.displayName = displayName;
    this.useDuration = useDuration;
    this.curedConditions = Set.copyOf(curedConditions);
    this.healingFraction = healingFraction;
  }

  public String displayName() {
    return displayName;
  }

  public Duration useDuration() {
    return useDuration;
  }

  public Set<MedicalCondition> curedConditions() {
    return curedConditions;
  }

  /** Fraction of maximum health restored in addition to curing conditions. */
  public double healingFraction() {
    return healingFraction;
  }

  /**
   * Maps durable item ids without coupling policy tests to Bukkit. Existing
   * healing potions act as full medical kits; aliases leave room for authored
   * bandage and painkiller definitions without changing the listener.
   */
  public static Optional<MedicalTreatment> fromItemId(String itemId) {
    if (itemId == null) return Optional.empty();
    return switch (itemId.toLowerCase(Locale.ROOT)) {
      case "bandage", "medical_bandage" -> Optional.of(BANDAGE);
      case "painkiller", "golden_apple" -> Optional.of(PAINKILLER);
      case "medkit", "medical_kit", "healing_potion" -> Optional.of(MEDICAL_KIT);
      default -> Optional.empty();
    };
  }
}
