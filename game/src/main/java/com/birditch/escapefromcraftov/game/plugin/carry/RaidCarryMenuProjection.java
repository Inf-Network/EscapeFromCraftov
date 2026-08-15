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
  private static final int WIDTH = 9;
  private static final int HEIGHT = 6;

  private RaidCarryMenuProjection() {}

  static Projection project(
      List<RaidCarryGridPlanner.Region> regions, int lockedCellCount) {
    List<RaidCarryGridPlanner.Region> checked = List.copyOf(
        Preconditions.checkNotNull(regions, "regions"));
    Preconditions.checkArgument(lockedCellCount >= 0, "lockedCellCount must not be negative");
    List<Box> boxes = boxes(checked, true);
    boolean[][] occupied = controls();
    Map<String, Origin> origins = new HashMap<>();
    boolean headerStrips = pack(boxes, 0, occupied, origins);
    if (!headerStrips) {
      boxes = boxes(checked, false);
      occupied = controls();
      origins.clear();
    }
    Preconditions.checkState(headerStrips || pack(boxes, 0, occupied, origins),
        "carry regions cannot fit the 9x6 TrMenu projection");

    List<DisplayCell> result = new ArrayList<>(SIZE);
    for (int slot = 0; slot < SIZE; slot++) result.add(DisplayCell.decoration());
    result.set(SECURE_SLOT, DisplayCell.secure());
    result.set(SUMMARY_SLOT, DisplayCell.summary());
    result.set(CLOSE_SLOT, DisplayCell.close());
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
    Preconditions.checkState(remaining == 0, "locked cells exceed the TrMenu projection");
    return new Projection(result);
  }

  private static List<Box> boxes(List<RaidCarryGridPlanner.Region> regions,
      boolean headerStrip) {
    return regions.stream().map(region -> new Box(region, headerStrip))
        .sorted(Comparator.comparingInt(Box::area).reversed()
            .thenComparing(box -> box.region().id()))
        .toList();
  }

  private static boolean[][] controls() {
    boolean[][] occupied = new boolean[HEIGHT][WIDTH];
    occupied[SECURE_SLOT / WIDTH][SECURE_SLOT % WIDTH] = true;
    occupied[SUMMARY_SLOT / WIDTH][SUMMARY_SLOT % WIDTH] = true;
    occupied[CLOSE_SLOT / WIDTH][CLOSE_SLOT % WIDTH] = true;
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
    SECURE,
    SUMMARY,
    CLOSE,
    DECORATION
  }

  record Projection(List<DisplayCell> cells) {

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
      @Nullable GridPosition position) {

    DisplayCell {
      Preconditions.checkNotNull(kind, "kind");
      boolean region = kind == Kind.REGION;
      boolean regionDecoration = kind == Kind.HEADER || kind == Kind.BORDER;
      Preconditions.checkArgument(region
              ? gridId != null && position != null
              : regionDecoration ? gridId != null && position == null
              : gridId == null && position == null,
          "only region cells and their headers carry a grid id");
    }

    static DisplayCell region(String gridId, GridPosition position) {
      return new DisplayCell(Kind.REGION,
          Preconditions.checkNotNull(gridId, "gridId"),
          Preconditions.checkNotNull(position, "position"));
    }

    static DisplayCell header(String gridId) {
      return new DisplayCell(Kind.HEADER,
          Preconditions.checkNotNull(gridId, "gridId"), null);
    }

    static DisplayCell border(String gridId) {
      return new DisplayCell(Kind.BORDER,
          Preconditions.checkNotNull(gridId, "gridId"), null);
    }

    static DisplayCell locked() {
      return new DisplayCell(Kind.LOCKED, null, null);
    }

    static DisplayCell secure() {
      return new DisplayCell(Kind.SECURE, null, null);
    }

    static DisplayCell summary() {
      return new DisplayCell(Kind.SUMMARY, null, null);
    }

    static DisplayCell close() {
      return new DisplayCell(Kind.CLOSE, null, null);
    }

    static DisplayCell decoration() {
      return new DisplayCell(Kind.DECORATION, null, null);
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
