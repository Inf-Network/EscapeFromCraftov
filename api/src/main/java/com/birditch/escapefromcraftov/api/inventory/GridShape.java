package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistable grid mask. Every listed cell is usable and carries a compartment
 * id; one item may not cross compartment boundaries.
 */
public record GridShape(int width, int height, List<Cell> cells) {

  public static final int MAX_WIDTH = 9;
  public static final int MAX_HEIGHT = 6;
  public static final int MAX_CELLS = 54;
  private static final String DEFAULT_COMPARTMENT = "main";

  public GridShape {
    Preconditions.checkArgument(width >= 1 && width <= MAX_WIDTH,
        "width must be between 1 and 9");
    Preconditions.checkArgument(height >= 1 && height <= MAX_HEIGHT,
        "height must be between 1 and 6");
    cells = List.copyOf(Preconditions.checkNotNull(cells, "cells"));
    Preconditions.checkArgument(!cells.isEmpty() && cells.size() <= MAX_CELLS,
        "shape must allow between 1 and 54 cells");
    Set<GridPosition> positions = new HashSet<>();
    for (Cell cell : cells) {
      Preconditions.checkNotNull(cell, "cell");
      Preconditions.checkArgument(cell.position().column() < width
              && cell.position().row() < height,
          "shape cell must be inside its bounding grid");
      Preconditions.checkArgument(positions.add(cell.position()),
          "shape contains a duplicate cell");
    }
  }

  public static GridShape rectangle(int width, int height) {
    Preconditions.checkArgument(width >= 1 && width <= MAX_WIDTH,
        "width must be between 1 and 9");
    Preconditions.checkArgument(height >= 1 && height <= MAX_HEIGHT,
        "height must be between 1 and 6");
    List<Cell> cells = new ArrayList<>(Math.multiplyExact(width, height));
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        cells.add(new Cell(new GridPosition(column, row), DEFAULT_COMPARTMENT));
      }
    }
    return new GridShape(width, height, cells);
  }

  public int allowedCellCount() {
    return cells.size();
  }

  public Set<GridPosition> allowedCells() {
    Set<GridPosition> result = new LinkedHashSet<>(cells.size());
    cells.forEach(cell -> result.add(cell.position()));
    return Set.copyOf(result);
  }

  public Optional<String> compartmentAt(GridPosition position) {
    Preconditions.checkNotNull(position, "position");
    return cells.stream()
        .filter(cell -> cell.position().equals(position))
        .map(Cell::compartment)
        .findFirst();
  }

  public record Cell(GridPosition position, String compartment) {

    public Cell {
      Preconditions.checkNotNull(position, "position");
      Preconditions.checkNotNull(compartment, "compartment");
      Preconditions.checkArgument(!compartment.isBlank(), "compartment must not be blank");
    }
  }
}
