package com.birditch.escapefromcraftov.api.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.item.ItemFootprint;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ItemGridDomainTest {

  @Test
  void footprintDimensionsFollowSupportedRotation() {
    ItemFootprint footprint = ItemFootprint.rotatable(2, 3);

    assertEquals(6, footprint.area());
    assertEquals(2, footprint.width(GridRotation.DEFAULT));
    assertEquals(3, footprint.height(GridRotation.DEFAULT));
    assertEquals(3, footprint.width(GridRotation.CLOCKWISE_90));
    assertEquals(2, footprint.height(GridRotation.CLOCKWISE_90));
    assertThrows(IllegalArgumentException.class,
        () -> ItemFootprint.of(1, 2).width(GridRotation.CLOCKWISE_90));
    assertThrows(IllegalArgumentException.class, () -> ItemFootprint.of(10, 1));
    assertThrows(IllegalArgumentException.class, () -> ItemFootprint.of(1, 7));
    assertThrows(IllegalArgumentException.class, () -> ItemGrid.empty(9, 7));
  }

  @Test
  void gridPersistsOneAnchorPerPhysicalIdentity() {
    UUID identity = UUID.randomUUID();
    GridItemPlacement anchor = new GridItemPlacement(
        new ItemInstance(identity, ItemId.of("large_item"), 1),
        new GridPosition(1, 2), GridRotation.DEFAULT);

    ItemGrid grid = new ItemGrid(5, 5, List.of(anchor));

    assertEquals(1, grid.placements().size());
    assertEquals(anchor, grid.find(identity).orElseThrow());
    assertTrue(grid.find(UUID.randomUUID()).isEmpty());
  }

  @Test
  void gridRejectsDuplicatePhysicalIdentitiesAndAnchors() {
    UUID identity = UUID.randomUUID();
    GridItemPlacement first = placement(identity, 0, 0);

    assertThrows(IllegalArgumentException.class, () -> new ItemGrid(2, 2,
        List.of(first, placement(identity, 1, 1))));
    assertThrows(IllegalArgumentException.class, () -> new ItemGrid(2, 2,
        List.of(first, placement(UUID.randomUUID(), 0, 0))));
  }

  @Test
  void placementResultKeepsSuccessAndFailurePayloadsExclusive() {
    ItemGrid grid = ItemGrid.empty(1, 1);
    GridItemPlacement placement = placement(UUID.randomUUID(), 0, 0);

    assertThrows(IllegalArgumentException.class,
        () -> new GridPlacementResult(false, grid, placement,
            GridPlacementFailure.OCCUPIED));
    assertThrows(IllegalArgumentException.class,
        () -> new GridPlacementResult(true, grid, placement,
            GridPlacementFailure.OCCUPIED));
  }

  private static GridItemPlacement placement(UUID identity, int column, int row) {
    return new GridItemPlacement(new ItemInstance(identity, ItemId.of("item"), 1),
        new GridPosition(column, row), GridRotation.DEFAULT);
  }
}
