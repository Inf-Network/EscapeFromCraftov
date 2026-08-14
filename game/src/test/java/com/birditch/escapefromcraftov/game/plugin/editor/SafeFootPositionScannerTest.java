package com.birditch.escapefromcraftov.game.plugin.editor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.map.BlockPosition;
import com.birditch.escapefromcraftov.map.CuboidBounds;
import java.util.List;
import org.junit.jupiter.api.Test;

class SafeFootPositionScannerTest {

  @Test
  void groundBlockOnlySelectionDoesNotCountAsPlayerFootHeight() {
    CuboidBounds selectedGrassBlocks = new CuboidBounds(-4, -61, -4, 4, -61, 4);

    boolean safe = SafeFootPositionScanner.hasSafePosition(selectedGrassBlocks, -63, 318, 512,
        position -> position.y() == -60);

    assertFalse(safe);
  }

  @Test
  void regionCoveringPlayerFeetCanFindSafePosition() {
    CuboidBounds extraction = new CuboidBounds(-4, -60, -4, 4, -58, 4);

    boolean safe = SafeFootPositionScanner.hasSafePosition(extraction, -63, 318, 512,
        position -> position.equals(new BlockPosition(0, -60, 0)));

    assertTrue(safe);
  }

  @Test
  void hugeRegionUsesBoundedDeterministicSamples() {
    CuboidBounds huge = new CuboidBounds(Integer.MIN_VALUE, -64, Integer.MIN_VALUE,
        Integer.MAX_VALUE, 319, Integer.MAX_VALUE);

    List<BlockPosition> first = SafeFootPositionScanner.candidates(huge, -63, 318, 512);
    List<BlockPosition> second = SafeFootPositionScanner.candidates(huge, -63, 318, 512);

    assertTrue(first.size() <= 512);
    assertFalse(first.isEmpty());
    assertTrue(first.stream().allMatch(huge::contains));
    assertTrue(first.stream().allMatch(position -> position.y() >= -63 && position.y() <= 318));
    assertTrue(first.equals(second));
  }
}
