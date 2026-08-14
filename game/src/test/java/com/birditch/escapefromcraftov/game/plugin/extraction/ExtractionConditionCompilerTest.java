package com.birditch.escapefromcraftov.game.plugin.extraction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.extraction.ExtractionCondition;
import com.birditch.escapefromcraftov.api.extraction.ExtractionContext;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.extraction.CapacityExtractionCondition;
import com.birditch.escapefromcraftov.extraction.RandomOpenExtractionCondition;
import com.birditch.escapefromcraftov.extraction.SwitchActivatedExtractionCondition;
import com.birditch.escapefromcraftov.map.BlockPosition;
import com.birditch.escapefromcraftov.map.CuboidBounds;
import com.birditch.escapefromcraftov.map.ExtractionDefinition;
import com.birditch.escapefromcraftov.map.ExtractionRuleDefinition;
import com.birditch.escapefromcraftov.map.ManualExtractionType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExtractionConditionCompilerTest {

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final CraftovGameId GAME = CraftovGameId.of("condition-test");
  private static final ExtractionContext CONTEXT = new ExtractionContext(PLAYER, GAME);

  @Test
  void compilesEveryComposableRuleAndKeepsIrreversiblePaidAuthorizationLast() {
    AtomicReference<CarriedInventoryState> inventory =
        new AtomicReference<>(new CarriedInventoryState(20_000, false));
    ExtractionCondition paid = condition(true);
    ExtractionDefinition definition = new ExtractionDefinition("elevator", "Elevator",
        new CuboidBounds(0, 60, 0, 5, 65, 5), ManualExtractionType.PAID, 10, "500", true,
        List.of(new ExtractionRuleDefinition.SwitchActivated("power", new BlockPosition(9, 64, 9)),
            new ExtractionRuleDefinition.RandomOpen(1),
            new ExtractionRuleDefinition.NoBackpack(),
            new ExtractionRuleDefinition.MaxWeight(30_000),
            new ExtractionRuleDefinition.Capacity(1)));

    List<ExtractionCondition> compiled = new ExtractionConditionCompiler(
        new java.util.Random(1), id -> id.equals(PLAYER)
            ? Optional.of(inventory.get()) : Optional.empty()).compile(definition, List.of(paid));

    assertTrue(compiled.get(0) instanceof SwitchActivatedExtractionCondition);
    assertTrue(compiled.get(1) instanceof RandomOpenExtractionCondition random && random.open());
    assertTrue(compiled.get(2) instanceof NoBackpackExtractionCondition);
    assertTrue(compiled.get(3) instanceof MaximumWeightExtractionCondition);
    assertTrue(compiled.get(4) instanceof CapacityExtractionCondition);
    assertSame(paid, compiled.get(5));
  }

  @Test
  void carriedInventoryRulesRecheckTheLiveStateAfterAdmission() {
    AtomicReference<CarriedInventoryState> inventory =
        new AtomicReference<>(new CarriedInventoryState(20_000, false));
    CarriedInventoryLookup lookup = _ -> Optional.of(inventory.get());
    MaximumWeightExtractionCondition weight =
        new MaximumWeightExtractionCondition(30_000, lookup);
    NoBackpackExtractionCondition backpack = new NoBackpackExtractionCondition(lookup);
    var weightPermit = weight.acquire(CONTEXT).orElseThrow();
    var backpackPermit = backpack.acquire(CONTEXT).orElseThrow();

    inventory.set(new CarriedInventoryState(30_001, true));

    assertFalse(weightPermit.authorize().join());
    assertFalse(backpackPermit.authorize().join());
  }

  private static ExtractionCondition condition(boolean allowed) {
    return new ExtractionCondition() {
      @Override public String describe() { return "legacy"; }
      @Override public boolean isSatisfied(ExtractionContext context) { return allowed; }
    };
  }
}
