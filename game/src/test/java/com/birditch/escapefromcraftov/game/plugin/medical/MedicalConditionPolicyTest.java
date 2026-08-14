package com.birditch.escapefromcraftov.game.plugin.medical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MedicalConditionPolicyTest {

  @Test
  void realDamageCreatesDeterministicSourceAwareTrauma() {
    assertEquals(Set.of(), MedicalConditionPolicy.conditions(0, 20,
        MedicalConditionPolicy.TraumaSource.MELEE_OR_PROJECTILE));
    assertEquals(Set.of(MedicalCondition.PAIN), MedicalConditionPolicy.conditions(3, 20,
        MedicalConditionPolicy.TraumaSource.ENVIRONMENT));
    assertEquals(Set.of(MedicalCondition.PAIN, MedicalCondition.BLEEDING),
        MedicalConditionPolicy.conditions(5, 20,
            MedicalConditionPolicy.TraumaSource.MELEE_OR_PROJECTILE));
    assertEquals(Set.of(MedicalCondition.PAIN, MedicalCondition.BLEEDING,
            MedicalCondition.FRACTURE),
        MedicalConditionPolicy.conditions(8, 20,
            MedicalConditionPolicy.TraumaSource.EXPLOSION));
  }

  @Test
  void fireCanHurtAndCausePainWithoutInventingBleedingOrFractures() {
    assertEquals(Set.of(MedicalCondition.PAIN), MedicalConditionPolicy.conditions(10, 20,
        MedicalConditionPolicy.TraumaSource.FIRE));
  }

  @Test
  void treatmentOnlyClearsItsSupportedConditions() {
    MedicalConditionState state = new MedicalConditionState();
    state.inflict(Set.of(MedicalCondition.BLEEDING, MedicalCondition.FRACTURE,
        MedicalCondition.PAIN));

    assertTrue(state.treat(MedicalTreatment.BANDAGE));
    assertFalse(state.has(MedicalCondition.BLEEDING));
    assertTrue(state.has(MedicalCondition.FRACTURE));
    assertTrue(state.has(MedicalCondition.PAIN));

    assertTrue(state.treat(MedicalTreatment.MEDICAL_KIT));
    assertTrue(state.isEmpty());
  }

  @Test
  void medicineProgressIsBoundedAndItemAliasesAreStable() {
    assertEquals(0, MedicalConditionPolicy.progress(100, 50, 100));
    assertEquals(0.5F, MedicalConditionPolicy.progress(100, 150, 100));
    assertEquals(1, MedicalConditionPolicy.progress(100, 300, 100));
    assertEquals(MedicalTreatment.MEDICAL_KIT,
        MedicalTreatment.fromItemId("healing_potion").orElseThrow());
    assertEquals(MedicalTreatment.PAINKILLER,
        MedicalTreatment.fromItemId("golden_apple").orElseThrow());
    assertEquals(0.4, MedicalTreatment.MEDICAL_KIT.healingFraction());
  }
}
