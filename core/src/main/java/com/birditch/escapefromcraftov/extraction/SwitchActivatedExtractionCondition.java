package com.birditch.escapefromcraftov.extraction;

import com.birditch.escapefromcraftov.api.extraction.ExtractionCondition;
import com.birditch.escapefromcraftov.api.extraction.ExtractionContext;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.map.BlockPosition;

/** One-way, per-raid lever gate shared by the runtime point and Bukkit listener. */
public final class SwitchActivatedExtractionCondition implements ExtractionCondition {

  private final String switchId;
  private final BlockPosition position;
  private boolean activated;

  public SwitchActivatedExtractionCondition(String switchId, BlockPosition position) {
    this.switchId = Preconditions.checkNotNull(switchId, "switchId");
    Preconditions.checkArgument(!switchId.isBlank(), "switchId must not be blank");
    this.position = Preconditions.checkNotNull(position, "position");
  }

  public String switchId() {
    return switchId;
  }

  public BlockPosition position() {
    return position;
  }

  public boolean activated() {
    return activated;
  }

  /** @return true only for the first activation in this raid runtime. */
  public boolean activate() {
    if (activated) return false;
    activated = true;
    return true;
  }

  @Override
  public String describe() {
    return activated ? "闸门已开启" : "需要先拉下闸门 " + switchId;
  }

  @Override
  public boolean isSatisfied(ExtractionContext context) {
    Preconditions.checkNotNull(context, "context");
    return activated;
  }
}
