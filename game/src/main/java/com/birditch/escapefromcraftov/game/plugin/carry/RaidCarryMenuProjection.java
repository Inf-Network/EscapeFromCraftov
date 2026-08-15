package com.birditch.escapefromcraftov.game.plugin.carry;

import com.birditch.escapefromcraftov.api.inventory.GridPosition;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Deterministic 9x6 projection of the server-owned carry regions. */
final class RaidCarryMenuProjection {

  static final int SIZE = 54;
  static final int SECURE_SLOT = 51;
  static final int SUMMARY_SLOT = 52;
  static final int CLOSE_SLOT = 53;
  static final int FIRST_EQUIPMENT_SLOT = 45;
  static final int LAST_EQUIPMENT_SLOT = 50;
  private static final int[] EQUIPMENT_PHYSICAL_SLOTS = {
      RaidCarryLayout.MAIN_HAND_SLOT, 40, 39, 38,
      RaidCarryLayout.CHEST_RIG_SLOT, RaidCarryLayout.BACKPACK_SLOT
  };
  private static final int WIDTH = 9;
  private static final int HEIGHT = 6;

  private RaidCarryMenuProjection() {}

  static Projection project(
      List<RaidCarryGridPlanner.Region> regions, int lockedCellCount) {
    List<RaidCarryGridPlanner.Region> checked = List.copyOf(
        Preconditions.checkNotNull(regions, "regions"));
    Preconditions.checkArgument(lockedCellCount >= 0, "lockedCellCount must not be negative");
    List<Box> boxes = boxes(checked, true);
    boolean[][] occupied = controls(true);
    Map<String, Origin> origins = new HashMap<>();
    boolean headerStrips = pack(boxes, 0, occupied, origins);
    if (!headerStrips) {
      boxes = boxes(checked, false);
      occupied = controls(true);
      origins.clear();
    }
    boolean packed = headerStrips || pack(boxes, 0, occupied, origins);
    if (!packed) return maskedProjection(checked, lockedCellCount);

    List<DisplayCell> result = new ArrayList<>(SIZE);
    for (int slot = 0; slot < SIZE; slot++) result.add(DisplayCell.decoration());
    result.set(SECURE_SLOT, DisplayCell.secure());
    result.set(SUMMARY_SLOT, DisplayCell.summary());
    result.set(CLOSE_SLOT, DisplayCell.close());
    for (int index = 0; index < EQUIPMENT_PHYSICAL_SLOTS.length; index++) {
      result.set(FIRST_EQUIPMENT_SLOT + index,
          DisplayCell.equipment(EQUIPMENT_PHYSICAL_SLOTS[index]));
    }
    boolean[] insideBox = new boolean[SIZE];
    for (Box box : boxes) {
      Origin origin = origins.get(box.region().id());
      for (int y = 0; y < box.height(); y++) {
        for (int x = 0; x < box.width(); x++) {
          insideBox[(origin.y() + y) * WIDTH + origin.x() + x] = true;
        }
      }
      if (headerStrips) {
        for (int x = 0; x < box.width(); x++) {
          int slot = origin.y() * WIDTH + origin.x() + x;
          result.set(slot, x == 0
              ? DisplayCell.header(box.region().id())
              : DisplayCell.border(box.region().id()));
        }
      }
      for (var shapeCell : box.region().shape().cells()) {
        GridPosition position = shapeCell.position();
        int slot = (origin.y() + (headerStrips ? 1 : 0) + position.row()) * WIDTH
            + origin.x() + position.column();
        result.set(slot, DisplayCell.region(box.region().id(), position));
      }
    }

    if (!headerStrips) {
      for (Box box : boxes) {
        Origin origin = origins.get(box.region().id());
        int slot = nearestDecoration(result, insideBox, origin);
        Preconditions.checkState(slot >= 0, "carry region header cannot fit the projection");
        result.set(slot, DisplayCell.header(box.region().id()));
      }
    }

    int remaining = lockedCellCount;
    for (int slot = SIZE - 1; slot >= 0 && remaining > 0; slot--) {
      if (insideBox[slot] || result.get(slot).kind() != Kind.DECORATION) continue;
      result.set(slot, DisplayCell.locked());
      remaining--;
    }
    // Locked physical slots are not grid targets and therefore need no one-to-one projection.
    // When an extreme 31-cell layout leaves insufficient decoration cells, retain the fixed
    // equipment strip and show as many explanatory lock markers as fit.
    return new Projection(result, true);
  }

  /**
   * Shape-preserving fallback for unusual capacities whose bounding rectangles cannot share the
   * five cargo rows. Only allowed mask cells reserve display slots, so another region can use a
   * shape's holes without flattening any region's two-dimensional item footprint.
   */
  private static Projection maskedProjection(
      List<RaidCarryGridPlanner.Region> regions, int lockedCellCount) {
    List<RaidCarryGridPlanner.Region> ordered = regions.stream()
        .sorted(Comparator
            .comparingInt((RaidCarryGridPlanner.Region region) ->
                region.shape().allowedCellCount()).reversed()
            .thenComparing(RaidCarryGridPlanner.Region::id))
        .toList();
    boolean[][] occupied = controls(true);
    Map<String, Origin> origins = new HashMap<>();
    Preconditions.checkState(packMasked(ordered, 0, occupied, origins),
        "carry region masks cannot fit the 9x6 TrMenu projection");

    List<DisplayCell> result = new ArrayList<>(SIZE);
    for (int slot = 0; slot < SIZE; slot++) result.add(DisplayCell.decoration());
    for (int index = 0; index < EQUIPMENT_PHYSICAL_SLOTS.length; index++) {
      result.set(FIRST_EQUIPMENT_SLOT + index,
          DisplayCell.equipment(EQUIPMENT_PHYSICAL_SLOTS[index]));
    }
    result.set(SECURE_SLOT, DisplayCell.secure());
    result.set(SUMMARY_SLOT, DisplayCell.summary());
    result.set(CLOSE_SLOT, DisplayCell.close());

    boolean[] insideRegion = new boolean[SIZE];
    for (RaidCarryGridPlanner.Region region : regions) {
      Origin origin = origins.get(region.id());
      for (var shapeCell : region.shape().cells()) {
        GridPosition position = shapeCell.position();
        int slot = (origin.y() + position.row()) * WIDTH + origin.x() + position.column();
        insideRegion[slot] = true;
        result.set(slot, DisplayCell.region(region.id(), position));
      }
    }
    for (RaidCarryGridPlanner.Region region : regions) {
      int slot = nearestDecoration(result, insideRegion, origins.get(region.id()));
      Preconditions.checkState(slot >= 0, "carry region header cannot fit the masked projection");
      result.set(slot, DisplayCell.header(region.id()));
    }
    int remaining = lockedCellCount;
    for (int slot = FIRST_EQUIPMENT_SLOT - 1; slot >= 0 && remaining > 0; slot--) {
      if (result.get(slot).kind() != Kind.DECORATION) continue;
      result.set(slot, DisplayCell.locked());
      remaining--;
    }
    Preconditions.checkState(remaining == 0, "locked cells exceed the masked projection");
    return new Projection(result, true);
  }

  private static boolean packMasked(List<RaidCarryGridPlanner.Region> regions, int index,
      boolean[][] occupied, Map<String, Origin> origins) {
    if (index == regions.size()) return true;
    RaidCarryGridPlanner.Region region = regions.get(index);
    for (int y = 0; y <= HEIGHT - region.shape().height(); y++) {
      for (int x = 0; x <= WIDTH - region.shape().width(); x++) {
        if (!freeMask(occupied, region, x, y)) continue;
        fillMask(occupied, region, x, y, true);
        origins.put(region.id(), new Origin(x, y));
        if (packMasked(regions, index + 1, occupied, origins)) return true;
        origins.remove(region.id());
        fillMask(occupied, region, x, y, false);
      }
    }
    return false;
  }

  private static boolean freeMask(boolean[][] occupied, RaidCarryGridPlanner.Region region,
      int x, int y) {
    for (var cell : region.shape().cells()) {
      if (occupied[y + cell.position().row()][x + cell.position().column()]) return false;
    }
    return true;
  }

  private static void fillMask(boolean[][] occupied, RaidCarryGridPlanner.Region region,
      int x, int y, boolean value) {
    for (var cell : region.shape().cells()) {
      occupied[y + cell.position().row()][x + cell.position().column()] = value;
    }
  }

  private static List<Box> boxes(List<RaidCarryGridPlanner.Region> regions,
      boolean headerStrip) {
    return regions.stream().map(region -> new Box(region, headerStrip))
        .sorted(Comparator.comparingInt(Box::area).reversed()
            .thenComparing(box -> box.region().id()))
        .toList();
  }

  private static boolean[][] controls(boolean equipmentStrip) {
    boolean[][] occupied = new boolean[HEIGHT][WIDTH];
    occupied[SECURE_SLOT / WIDTH][SECURE_SLOT % WIDTH] = true;
    occupied[SUMMARY_SLOT / WIDTH][SUMMARY_SLOT % WIDTH] = true;
    occupied[CLOSE_SLOT / WIDTH][CLOSE_SLOT % WIDTH] = true;
    if (equipmentStrip) {
      for (int slot = FIRST_EQUIPMENT_SLOT; slot <= LAST_EQUIPMENT_SLOT; slot++) {
        occupied[slot / WIDTH][slot % WIDTH] = true;
      }
    }
    return occupied;
  }

  private static int nearestDecoration(List<DisplayCell> cells, boolean[] insideBox,
      Origin origin) {
    int bestSlot = -1;
    int bestDistance = Integer.MAX_VALUE;
    for (int slot = 0; slot < SIZE; slot++) {
      if (insideBox[slot] || cells.get(slot).kind() != Kind.DECORATION) continue;
      int x = slot % WIDTH;
      int y = slot / WIDTH;
      int distance = Math.abs(x - origin.x()) + Math.abs(y - origin.y());
      if (distance < bestDistance) {
        bestSlot = slot;
        bestDistance = distance;
      }
    }
    return bestSlot;
  }

  private static boolean pack(List<Box> boxes, int index, boolean[][] occupied,
      Map<String, Origin> origins) {
    if (index == boxes.size()) return true;
    Box box = boxes.get(index);
    for (int y = 0; y <= HEIGHT - box.height(); y++) {
      for (int x = 0; x <= WIDTH - box.width(); x++) {
        if (!free(occupied, x, y, box.width(), box.height())) continue;
        fill(occupied, x, y, box.width(), box.height(), true);
        origins.put(box.region().id(), new Origin(x, y));
        if (pack(boxes, index + 1, occupied, origins)) return true;
        origins.remove(box.region().id());
        fill(occupied, x, y, box.width(), box.height(), false);
      }
    }
    return false;
  }

  private static boolean free(boolean[][] occupied, int x, int y, int width, int height) {
    for (int row = y; row < y + height; row++) {
      for (int column = x; column < x + width; column++) {
        if (occupied[row][column]) return false;
      }
    }
    return true;
  }

  private static void fill(boolean[][] occupied, int x, int y, int width, int height,
      boolean value) {
    for (int row = y; row < y + height; row++) {
      for (int column = x; column < x + width; column++) occupied[row][column] = value;
    }
  }

  enum Kind {
    REGION,
    HEADER,
    BORDER,
    LOCKED,
    EQUIPMENT,
    SECURE,
    SUMMARY,
    CLOSE,
    DECORATION
  }

  record Projection(List<DisplayCell> cells, boolean equipmentStrip) {

    Projection {
      cells = List.copyOf(Preconditions.checkNotNull(cells, "cells"));
      Preconditions.checkArgument(cells.size() == SIZE, "projection must contain 54 cells");
    }

    DisplayCell cell(int displayIndex) {
      Preconditions.checkArgument(displayIndex >= 0 && displayIndex < SIZE,
          "displayIndex must be between 0 and 53");
      return cells.get(displayIndex);
    }
  }

  record DisplayCell(
      Kind kind,
      @Nullable String gridId,
      @Nullable GridPosition position,
      int equipmentSlot) {

    DisplayCell {
      Preconditions.checkNotNull(kind, "kind");
      boolean region = kind == Kind.REGION;
      boolean regionDecoration = kind == Kind.HEADER || kind == Kind.BORDER;
      boolean equipment = kind == Kind.EQUIPMENT;
      Preconditions.checkArgument(region
              ? gridId != null && position != null && equipmentSlot == -1
              : regionDecoration ? gridId != null && position == null && equipmentSlot == -1
              : equipment ? gridId == null && position == null && equipmentSlot >= 0
              : gridId == null && position == null && equipmentSlot == -1,
          "only region cells and their headers carry a grid id");
    }

    static DisplayCell region(String gridId, GridPosition position) {
      return new DisplayCell(Kind.REGION,
          Preconditions.checkNotNull(gridId, "gridId"),
          Preconditions.checkNotNull(position, "position"), -1);
    }

    static DisplayCell header(String gridId) {
      return new DisplayCell(Kind.HEADER,
          Preconditions.checkNotNull(gridId, "gridId"), null, -1);
    }

    static DisplayCell border(String gridId) {
      return new DisplayCell(Kind.BORDER,
          Preconditions.checkNotNull(gridId, "gridId"), null, -1);
    }

    static DisplayCell locked() {
      return new DisplayCell(Kind.LOCKED, null, null, -1);
    }

    static DisplayCell equipment(int physicalSlot) {
      return new DisplayCell(Kind.EQUIPMENT, null, null, physicalSlot);
    }

    static DisplayCell secure() {
      return new DisplayCell(Kind.SECURE, null, null, -1);
    }

    static DisplayCell summary() {
      return new DisplayCell(Kind.SUMMARY, null, null, -1);
    }

    static DisplayCell close() {
      return new DisplayCell(Kind.CLOSE, null, null, -1);
    }

    static DisplayCell decoration() {
      return new DisplayCell(Kind.DECORATION, null, null, -1);
    }
  }

  private record Box(RaidCarryGridPlanner.Region region, int width, int height) {

    private Box(RaidCarryGridPlanner.Region region, boolean headerStrip) {
      this(region, region.shape().width(), region.shape().height() + (headerStrip ? 1 : 0));
    }

    private int area() {
      return width * height;
    }
  }

  private record Origin(int x, int y) {}
}
