package com.birditch.escapefromcraftov.map;

import com.birditch.escapefromcraftov.common.check.Preconditions;

/**
 * One composable extraction rule authored in a manual map.
 *
 * <p>Rules are AND-composed with the legacy {@link ManualExtractionType} rule.
 * Keeping the legacy fields lets existing NORMAL, ITEM_REQUIRED, PAID and TIMED
 * maps load unchanged while newer exits combine several Delta-style gates.</p>
 */
public sealed interface ExtractionRuleDefinition {

  RuleType type();

  /** Returns a spatially translated copy for editor clone/move operations. */
  default ExtractionRuleDefinition translate(int dx, int dy, int dz) {
    return this;
  }

  enum RuleType {
    SWITCH_ACTIVATED,
    RANDOM_OPEN,
    NO_BACKPACK,
    MAX_WEIGHT,
    CAPACITY
  }

  /** A lever at the authored source-world coordinate must be pulled once per raid. */
  record SwitchActivated(String switchId, BlockPosition position)
      implements ExtractionRuleDefinition {

    public SwitchActivated {
      MapIds.requireValid(switchId, "extraction switch id");
      Preconditions.checkNotNull(position, "position");
    }

    @Override public RuleType type() { return RuleType.SWITCH_ACTIVATED; }

    @Override
    public ExtractionRuleDefinition translate(int dx, int dy, int dz) {
      return new SwitchActivated(switchId, position.add(dx, dy, dz));
    }
  }

  /** The exit is rolled independently once when its raid runtime is created. */
  record RandomOpen(double chance) implements ExtractionRuleDefinition {

    public RandomOpen {
      Preconditions.checkArgument(Double.isFinite(chance) && chance >= 0 && chance <= 1,
          "chance must be finite and between 0 and 1");
    }

    @Override public RuleType type() { return RuleType.RANDOM_OPEN; }
  }

  /** The player may not carry an item catalogued as a backpack. */
  record NoBackpack() implements ExtractionRuleDefinition {
    @Override public RuleType type() { return RuleType.NO_BACKPACK; }
  }

  /** Total carried Craftov item weight must not exceed this many grams. */
  record MaxWeight(long maxWeightGrams) implements ExtractionRuleDefinition {

    public MaxWeight {
      Preconditions.checkArgument(maxWeightGrams >= 0, "maxWeightGrams must not be negative");
    }

    @Override public RuleType type() { return RuleType.MAX_WEIGHT; }
  }

  /** At most {@code slots} players can successfully use this exit in one raid. */
  record Capacity(int slots) implements ExtractionRuleDefinition {

    public Capacity {
      Preconditions.checkArgument(slots > 0, "slots must be positive");
    }

    @Override public RuleType type() { return RuleType.CAPACITY; }
  }
}
