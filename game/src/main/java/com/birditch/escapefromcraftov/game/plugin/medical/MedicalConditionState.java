package com.birditch.escapefromcraftov.game.plugin.medical;

import java.util.EnumSet;
import java.util.Set;

/** Small mutable aggregate containing one player's current-raid injuries. */
final class MedicalConditionState {

  private final EnumSet<MedicalCondition> conditions =
      EnumSet.noneOf(MedicalCondition.class);

  boolean inflict(Set<MedicalCondition> additions) {
    int before = conditions.size();
    conditions.addAll(additions);
    return conditions.size() != before;
  }

  boolean treat(MedicalTreatment treatment) {
    return conditions.removeAll(treatment.curedConditions());
  }

  boolean has(MedicalCondition condition) {
    return conditions.contains(condition);
  }

  boolean isEmpty() {
    return conditions.isEmpty();
  }

  Set<MedicalCondition> snapshot() {
    return Set.copyOf(conditions);
  }
}
