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
import java.util.Map;
import java.util.random.RandomGenerator;

/** Compiles AND-composed map rules into fresh, per-raid runtime conditions. */
public final class ExtractionConditionCompiler {

  private final RandomGenerator random;
  private final CarriedInventoryLookup inventories;
  private final EventCompletionLookup events;

  public ExtractionConditionCompiler(RandomGenerator random, CarriedInventoryLookup inventories) {
    this(random, inventories, EventCompletionLookup.DENY_ALL);
  }

  public ExtractionConditionCompiler(RandomGenerator random, CarriedInventoryLookup inventories,
      EventCompletionLookup events) {
    this.random = Preconditions.checkNotNull(random, "random");
    this.inventories = Preconditions.checkNotNull(inventories, "inventories");
    this.events = Preconditions.checkNotNull(events, "events");
  }

  /**
   * Adds the schema-v1 condition without weakening it. Paid authorization is
   * deliberately last, so every reversible live condition is rechecked before
   * the irreversible database withdrawal.
   */
  public List<ExtractionCondition> compile(ExtractionDefinition definition,
      List<ExtractionCondition> legacyConditions) {
    return compile(definition, legacyConditions, Map.of());
  }

  /** Event display names must come from the same immutable map snapshot as the rule. */
  public List<ExtractionCondition> compile(ExtractionDefinition definition,
      List<ExtractionCondition> legacyConditions, Map<String, String> eventDisplayNames) {
    Preconditions.checkNotNull(definition, "definition");
    legacyConditions = List.copyOf(Preconditions.checkNotNull(legacyConditions,
        "legacyConditions"));
    eventDisplayNames = Map.copyOf(Preconditions.checkNotNull(eventDisplayNames,
        "eventDisplayNames"));
    List<ExtractionCondition> result = new ArrayList<>();
    if (definition.extractionType() != ManualExtractionType.PAID) {
      result.addAll(legacyConditions);
    }
    for (ExtractionRuleDefinition rule : definition.conditions()) {
      if (!(rule instanceof ExtractionRuleDefinition.Capacity)) {
        result.add(compile(rule, eventDisplayNames));
      }
    }
    // Reserve finite seats only after every stateless begin check has passed.
    for (ExtractionRuleDefinition rule : definition.conditions()) {
      if (rule instanceof ExtractionRuleDefinition.Capacity) {
        result.add(compile(rule, eventDisplayNames));
      }
    }
    if (definition.extractionType() == ManualExtractionType.PAID) {
      result.addAll(legacyConditions);
    }
    return List.copyOf(result);
  }

  private ExtractionCondition compile(
      ExtractionRuleDefinition rule, Map<String, String> eventDisplayNames) {
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
      case ExtractionRuleDefinition.EventCompleted value ->
          new EventCompletedExtractionCondition(value.eventId(),
              eventDisplayNames.getOrDefault(value.eventId(), value.eventId()), events);
    };
  }
}
