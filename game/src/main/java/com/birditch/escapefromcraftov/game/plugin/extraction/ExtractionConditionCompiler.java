package com.birditch.escapefromcraftov.game.plugin.extraction;

import com.birditch.escapefromcraftov.api.extraction.ExtractionCondition;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.extraction.CapacityExtractionCondition;
import com.birditch.escapefromcraftov.extraction.RandomOpenExtractionCondition;
import com.birditch.escapefromcraftov.extraction.SwitchActivatedExtractionCondition;
import com.birditch.escapefromcraftov.map.ExtractionDefinition;
import com.birditch.escapefromcraftov.map.ExtractionRuleDefinition;
import com.birditch.escapefromcraftov.map.ManualExtractionType;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** Compiles AND-composed map rules into fresh, per-raid runtime conditions. */
public final class ExtractionConditionCompiler {

  private final RandomGenerator random;
  private final CarriedInventoryLookup inventories;

  public ExtractionConditionCompiler(RandomGenerator random, CarriedInventoryLookup inventories) {
    this.random = Preconditions.checkNotNull(random, "random");
    this.inventories = Preconditions.checkNotNull(inventories, "inventories");
  }

  /**
   * Adds the schema-v1 condition without weakening it. Paid authorization is
   * deliberately last, so every reversible live condition is rechecked before
   * the irreversible database withdrawal.
   */
  public List<ExtractionCondition> compile(ExtractionDefinition definition,
      List<ExtractionCondition> legacyConditions) {
    Preconditions.checkNotNull(definition, "definition");
    legacyConditions = List.copyOf(Preconditions.checkNotNull(legacyConditions,
        "legacyConditions"));
    List<ExtractionCondition> result = new ArrayList<>();
    if (definition.extractionType() != ManualExtractionType.PAID) {
      result.addAll(legacyConditions);
    }
    for (ExtractionRuleDefinition rule : definition.conditions()) {
      if (!(rule instanceof ExtractionRuleDefinition.Capacity)) {
        result.add(compile(rule));
      }
    }
    // Reserve finite seats only after every stateless begin check has passed.
    for (ExtractionRuleDefinition rule : definition.conditions()) {
      if (rule instanceof ExtractionRuleDefinition.Capacity) {
        result.add(compile(rule));
      }
    }
    if (definition.extractionType() == ManualExtractionType.PAID) {
      result.addAll(legacyConditions);
    }
    return List.copyOf(result);
  }

  private ExtractionCondition compile(ExtractionRuleDefinition rule) {
    return switch (rule) {
      case ExtractionRuleDefinition.SwitchActivated value ->
          new SwitchActivatedExtractionCondition(value.switchId(), value.position());
      case ExtractionRuleDefinition.RandomOpen value ->
          new RandomOpenExtractionCondition(value.chance(), random);
      case ExtractionRuleDefinition.NoBackpack _ ->
          new NoBackpackExtractionCondition(inventories);
      case ExtractionRuleDefinition.MaxWeight value ->
          new MaximumWeightExtractionCondition(value.maxWeightGrams(), inventories);
      case ExtractionRuleDefinition.Capacity value ->
          new CapacityExtractionCondition(value.slots());
    };
  }
}
