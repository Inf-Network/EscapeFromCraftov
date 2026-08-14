package com.birditch.escapefromcraftov.extraction;

import com.birditch.escapefromcraftov.api.extraction.ExtractionCondition;
import com.birditch.escapefromcraftov.api.extraction.ExtractionContext;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.random.RandomGenerator;

/** A random availability roll frozen for one point in one raid runtime. */
public final class RandomOpenExtractionCondition implements ExtractionCondition {

  private final double chance;
  private final boolean open;

  public RandomOpenExtractionCondition(double chance, RandomGenerator random) {
    Preconditions.checkArgument(Double.isFinite(chance) && chance >= 0 && chance <= 1,
        "chance must be finite and between 0 and 1");
    this.chance = chance;
    this.open = Preconditions.checkNotNull(random, "random").nextDouble() < chance;
  }

  public double chance() {
    return chance;
  }

  public boolean open() {
    return open;
  }

  @Override
  public String describe() {
    return open ? "本局随机撤离点已开放" : "该随机撤离点本局未开放";
  }

  @Override
  public boolean isSatisfied(ExtractionContext context) {
    Preconditions.checkNotNull(context, "context");
    return open;
  }
}
