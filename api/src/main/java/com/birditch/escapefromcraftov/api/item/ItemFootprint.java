package com.birditch.escapefromcraftov.api.item;

import com.birditch.escapefromcraftov.api.inventory.GridRotation;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/**
 * Server-configured rectangular cargo footprint for one physical item stack.
 * Width and height are logical grid cells, not Minecraft inventory slots.
 */
public record ItemFootprint(int width, int height, boolean rotatable) {

  public static final ItemFootprint SINGLE_CELL = new ItemFootprint(1, 1, false);

  public ItemFootprint {
    Preconditions.checkArgument(width >= 1 && width <= 9,
        "width must be between 1 and 9");
    Preconditions.checkArgument(height >= 1 && height <= 6,
        "height must be between 1 and 6");
    Preconditions.checkArgument(Math.multiplyExact(width, height) <= 54,
        "footprint must not exceed 54 cells");
  }

  public static ItemFootprint of(int width, int height) {
    return new ItemFootprint(width, height, false);
  }

  public static ItemFootprint rotatable(int width, int height) {
    return new ItemFootprint(width, height, true);
  }

  public int area() {
    return Math.multiplyExact(width, height);
  }

  public boolean supports(GridRotation rotation) {
    Preconditions.checkNotNull(rotation, "rotation");
    return rotation == GridRotation.DEFAULT || rotatable;
  }

  public int width(GridRotation rotation) {
    requireSupported(rotation);
    return rotation == GridRotation.CLOCKWISE_90 ? height : width;
  }

  public int height(GridRotation rotation) {
    requireSupported(rotation);
    return rotation == GridRotation.CLOCKWISE_90 ? width : height;
  }

  private void requireSupported(GridRotation rotation) {
    Preconditions.checkArgument(supports(rotation), "footprint does not support rotation");
  }
}
