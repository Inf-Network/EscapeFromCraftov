package com.birditch.escapefromcraftov.game.plugin.extraction;

import com.birditch.escapefromcraftov.api.extraction.ExtractionCondition;
import com.birditch.escapefromcraftov.api.extraction.ExtractionContext;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/** Requires the player to physically discard every BACKPACK-category item. */
public final class NoBackpackExtractionCondition implements ExtractionCondition {

  private final CarriedInventoryLookup inventories;

  public NoBackpackExtractionCondition(CarriedInventoryLookup inventories) {
    this.inventories = Preconditions.checkNotNull(inventories, "inventories");
  }

  @Override
  public String describe() {
    return "必须先丢弃背包";
  }

  @Override
  public boolean isSatisfied(ExtractionContext context) {
    Preconditions.checkNotNull(context, "context");
    return inventories.find(context.playerId())
        .map(state -> !state.hasBackpack()).orElse(false);
  }
}
