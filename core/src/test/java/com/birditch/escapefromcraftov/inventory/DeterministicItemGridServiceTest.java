package com.birditch.escapefromcraftov.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.inventory.GridItemPlacement;
import com.birditch.escapefromcraftov.api.inventory.GridPlacementFailure;
import com.birditch.escapefromcraftov.api.inventory.GridPosition;
import com.birditch.escapefromcraftov.api.inventory.GridRotation;
import com.birditch.escapefromcraftov.api.inventory.GridShape;
import com.birditch.escapefromcraftov.api.inventory.ItemGrid;
import com.birditch.escapefromcraftov.api.inventory.ItemGridLayout;
import com.birditch.escapefromcraftov.api.item.ItemCategory;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemFootprint;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.item.ItemRarity;
import com.birditch.escapefromcraftov.item.SimpleItemRegistry;
import com.google.gson.Gson;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeterministicItemGridServiceTest {

  private static final ItemId SMALL = ItemId.of("small");
  private static final ItemId LARGE = ItemId.of("large");
  private static final ItemId WIDE = ItemId.of("wide");

  private SimpleItemRegistry registry;
  private DeterministicItemGridService service;

  @BeforeEach
  void setUp() {
    registry = new SimpleItemRegistry();
    registry.register(definition(SMALL, ItemFootprint.SINGLE_CELL, 8));
    registry.register(definition(LARGE, ItemFootprint.rotatable(2, 3), 1));
    registry.register(definition(WIDE, ItemFootprint.of(2, 1), 1));
    service = new DeterministicItemGridService(registry);
  }

  @Test
  void multiCellItemHasOneAnchorAndDerivedIdentityFreeOccupancy() {
    ItemInstance item = item(LARGE);
    var result = service.place(ItemGrid.empty(4, 4), item,
        new GridPosition(1, 0), GridRotation.DEFAULT);

    assertTrue(result.accepted());
    assertEquals(1, result.grid().placements().size());
    assertSame(item, result.grid().placements().getFirst().item());
    assertEquals(Set.of(
        new GridPosition(1, 0), new GridPosition(2, 0),
        new GridPosition(1, 1), new GridPosition(2, 1),
        new GridPosition(1, 2), new GridPosition(2, 2)),
        service.occupiedCells(result.grid()));
    assertEquals(result.grid().placements().getFirst(),
        service.placementAt(result.grid(), new GridPosition(2, 2)).orElseThrow());
    assertTrue(service.placementAt(result.grid(), new GridPosition(0, 0)).isEmpty());
  }

  @Test
  void autoPlacementUsesRowMajorAnchorsAndRotatesAtTheSameAnchor() {
    var rotated = service.autoPlace(ItemGrid.empty(3, 2), item(LARGE));

    assertTrue(rotated.accepted());
    assertEquals(new GridPosition(0, 0),
        rotated.acceptedPlacement().orElseThrow().anchor());
    assertEquals(GridRotation.CLOCKWISE_90,
        rotated.acceptedPlacement().orElseThrow().rotation());

    ItemGrid grid = service.place(ItemGrid.empty(4, 2), item(SMALL),
        new GridPosition(0, 0), GridRotation.DEFAULT).grid();
    grid = service.place(grid, item(SMALL),
        new GridPosition(1, 0), GridRotation.DEFAULT).grid();
    var next = service.autoPlace(grid, item(SMALL));
    assertEquals(new GridPosition(2, 0), next.acceptedPlacement().orElseThrow().anchor());
  }

  @Test
  void placementFailuresDoNotMutateTheSuppliedGrid() {
    ItemGrid grid = service.place(ItemGrid.empty(4, 4), item(LARGE),
        new GridPosition(0, 0), GridRotation.DEFAULT).grid();

    var occupied = service.place(grid, item(SMALL),
        new GridPosition(1, 1), GridRotation.DEFAULT);
    var outside = service.place(grid, item(LARGE),
        new GridPosition(3, 3), GridRotation.DEFAULT);
    var forbiddenRotation = service.place(ItemGrid.empty(2, 2), item(SMALL),
        new GridPosition(0, 0), GridRotation.CLOCKWISE_90);

    assertFalse(occupied.accepted());
    assertEquals(GridPlacementFailure.OCCUPIED, occupied.rejection().orElseThrow());
    assertSame(grid, occupied.grid());
    assertEquals(GridPlacementFailure.OUT_OF_BOUNDS, outside.rejection().orElseThrow());
    assertSame(grid, outside.grid());
    assertEquals(GridPlacementFailure.ROTATION_NOT_ALLOWED,
        forbiddenRotation.rejection().orElseThrow());
  }

  @Test
  void moveIsAtomicAndKeepsTheOnlyPhysicalIdentity() {
    ItemInstance first = item(SMALL);
    ItemInstance second = item(SMALL);
    ItemGrid grid = service.place(ItemGrid.empty(3, 2), first,
        new GridPosition(0, 0), GridRotation.DEFAULT).grid();
    grid = service.place(grid, second, new GridPosition(1, 0), GridRotation.DEFAULT).grid();

    var rejected = service.move(grid, first.instanceId(),
        new GridPosition(1, 0), GridRotation.DEFAULT);
    assertFalse(rejected.accepted());
    assertEquals(GridPlacementFailure.OCCUPIED, rejected.rejection().orElseThrow());
    assertSame(grid, rejected.grid());

    var moved = service.move(grid, first.instanceId(),
        new GridPosition(2, 1), GridRotation.DEFAULT);
    assertTrue(moved.accepted());
    assertEquals(2, moved.grid().placements().size());
    assertEquals(first.instanceId(), moved.grid().placements().getFirst().item().instanceId());
    assertEquals(new GridPosition(2, 1), moved.grid().placements().getFirst().anchor());
    assertEquals(2, service.occupiedCells(moved.grid()).size());
  }

  @Test
  void detectsForgedOverlappingPersistedGridBeforeMutation() {
    ItemGrid corrupt = new ItemGrid(4, 4, List.of(
        new GridItemPlacement(item(LARGE), new GridPosition(0, 0), GridRotation.DEFAULT,
            ItemFootprint.rotatable(2, 3)),
        new GridItemPlacement(item(LARGE), new GridPosition(1, 1), GridRotation.DEFAULT,
            ItemFootprint.rotatable(2, 3))));

    assertThrows(IllegalArgumentException.class, () -> service.occupiedCells(corrupt));
    var rejected = service.autoPlace(corrupt, item(SMALL));
    assertEquals(GridPlacementFailure.INVALID_GRID, rejected.rejection().orElseThrow());
    assertSame(corrupt, rejected.grid());
  }

  @Test
  void rejectsDuplicateIdentityInvalidStackAndNoSpace() {
    ItemInstance existing = item(SMALL);
    ItemGrid grid = service.autoPlace(ItemGrid.empty(1, 1), existing).grid();

    var duplicate = service.place(grid, existing,
        new GridPosition(0, 0), GridRotation.DEFAULT);
    var invalidStack = service.place(ItemGrid.empty(2, 2),
        new ItemInstance(UUID.randomUUID(), SMALL, 9),
        new GridPosition(0, 0), GridRotation.DEFAULT);
    var full = service.autoPlace(grid, item(SMALL));

    assertEquals(GridPlacementFailure.DUPLICATE_INSTANCE,
        duplicate.rejection().orElseThrow());
    assertEquals(GridPlacementFailure.INVALID_PHYSICAL_STACK,
        invalidStack.rejection().orElseThrow());
    assertEquals(GridPlacementFailure.NO_SPACE, full.rejection().orElseThrow());
  }

  @Test
  void anchorOnlyGridRoundTripsThroughJsonAndCanBeRemoved() {
    ItemGrid grid = service.place(ItemGrid.empty(4, 4), item(LARGE),
        new GridPosition(1, 1), GridRotation.CLOCKWISE_90).grid();
    Gson gson = new Gson();

    ItemGrid restored = gson.fromJson(gson.toJson(grid), ItemGrid.class);

    assertEquals(grid, restored);
    assertEquals(1, restored.placements().size());
    assertEquals(6, service.occupiedCells(restored).size());
    var removed = service.remove(restored,
        restored.placements().getFirst().item().instanceId());
    assertTrue(removed.accepted());
    assertTrue(removed.grid().placements().isEmpty());
  }

  @Test
  void shapedGridRejectsBlockedCellsAndCompartmentCrossing() {
    GridShape masked = new GridShape(2, 1, List.of(
        new GridShape.Cell(new GridPosition(0, 0), "pocket")));
    GridShape divided = new GridShape(2, 1, List.of(
        new GridShape.Cell(new GridPosition(0, 0), "left"),
        new GridShape.Cell(new GridPosition(1, 0), "right")));

    var blocked = service.place(ItemGrid.empty(masked), item(WIDE),
        new GridPosition(0, 0), GridRotation.DEFAULT);
    var crossing = service.place(ItemGrid.empty(divided), item(WIDE),
        new GridPosition(0, 0), GridRotation.DEFAULT);

    assertEquals(GridPlacementFailure.BLOCKED_CELL, blocked.rejection().orElseThrow());
    assertEquals(GridPlacementFailure.COMPARTMENT_BOUNDARY,
        crossing.rejection().orElseThrow());
  }

  @Test
  void persistedFootprintFailsClosedAfterRegistryRetuningAndLegacyDefinitionJsonStillLoads() {
    ItemGrid placed = service.place(ItemGrid.empty(3, 1), item(WIDE),
        new GridPosition(0, 0), GridRotation.DEFAULT).grid();
    SimpleItemRegistry retuned = new SimpleItemRegistry();
    retuned.register(definition(WIDE, ItemFootprint.SINGLE_CELL, 1));

    assertThrows(IllegalArgumentException.class,
        () -> new DeterministicItemGridService(retuned).occupiedCells(placed));
    assertEquals(ItemFootprint.of(2, 1), placed.placements().getFirst().footprint());

    Gson gson = new Gson();
    var legacyJson = gson.toJsonTree(registry.require(WIDE)).getAsJsonObject();
    legacyJson.remove("footprint");
    ItemDefinition restored = gson.fromJson(legacyJson, ItemDefinition.class);
    assertEquals(ItemFootprint.SINGLE_CELL, restored.footprint());
  }

  @Test
  void persistedGridRequiresExplicitShapeAndFootprintSnapshots() {
    ItemGrid grid = service.place(ItemGrid.empty(3, 1), item(WIDE),
        new GridPosition(0, 0), GridRotation.DEFAULT).grid();
    Gson gson = new Gson();
    var missingShape = gson.toJsonTree(grid).getAsJsonObject();
    missingShape.remove("shape");
    var missingFootprint = gson.toJsonTree(grid).getAsJsonObject();
    missingFootprint.getAsJsonArray("placements").get(0).getAsJsonObject().remove("footprint");

    assertThrows(RuntimeException.class, () -> gson.fromJson(missingShape, ItemGrid.class));
    assertThrows(RuntimeException.class, () -> gson.fromJson(missingFootprint, ItemGrid.class));
  }

  @Test
  void layoutEnforcesGlobalIdentityAndCrossGridTransferIsAtomic() {
    ItemInstance cargo = item(WIDE);
    ItemGrid source = service.place(ItemGrid.empty(3, 1), cargo,
        new GridPosition(0, 0), GridRotation.DEFAULT).grid();
    ItemGrid target = service.place(ItemGrid.empty(3, 1), item(SMALL),
        new GridPosition(0, 0), GridRotation.DEFAULT).grid();
    ItemGridLayout layout = new ItemGridLayout(Map.of("pockets", source, "backpack", target));

    assertThrows(IllegalArgumentException.class,
        () -> new ItemGridLayout(Map.of("first", source, "duplicate", source)));
    var duplicatePlace = service.place(layout, "backpack", cargo,
        new GridPosition(1, 0), GridRotation.DEFAULT);
    var duplicateAutoPlace = service.autoPlace(layout, "backpack", cargo);
    assertEquals(GridPlacementFailure.DUPLICATE_INSTANCE,
        duplicatePlace.rejection().orElseThrow());
    assertEquals(GridPlacementFailure.DUPLICATE_INSTANCE,
        duplicateAutoPlace.rejection().orElseThrow());
    assertSame(layout, duplicatePlace.layout());
    assertSame(layout, duplicateAutoPlace.layout());

    var rejected = service.transfer(layout, "pockets", "backpack", cargo.instanceId(),
        new GridPosition(0, 0), GridRotation.DEFAULT);
    assertEquals(GridPlacementFailure.OCCUPIED, rejected.rejection().orElseThrow());
    assertSame(layout, rejected.layout());
    assertSame(source, rejected.layout().grids().get("pockets"));

    var transferred = service.transfer(layout, "pockets", "backpack", cargo.instanceId(),
        new GridPosition(1, 0), GridRotation.DEFAULT);
    assertTrue(transferred.accepted());
    assertTrue(transferred.layout().grids().get("pockets").placements().isEmpty());
    assertEquals(2, transferred.layout().grids().get("backpack").placements().size());
    assertEquals(ItemFootprint.of(2, 1), transferred.placement().footprint());

    var removed = service.remove(transferred.layout(), "backpack", cargo.instanceId());
    assertTrue(removed.accepted());
    assertEquals(1, removed.layout().grids().get("backpack").placements().size());
  }

  private static ItemDefinition definition(ItemId id, ItemFootprint footprint, int stackSize) {
    return ItemDefinition.builder(id, id.value(), ItemCategory.MATERIAL, ItemRarity.COMMON)
        .stackSize(stackSize)
        .footprint(footprint)
        .build();
  }

  private static ItemInstance item(ItemId id) {
    return new ItemInstance(UUID.randomUUID(), id, 1);
  }
}
