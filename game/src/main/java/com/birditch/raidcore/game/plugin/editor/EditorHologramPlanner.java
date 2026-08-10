package com.birditch.raidcore.game.plugin.editor;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.map.ManualRaidMap;
import com.birditch.raidcore.map.MapObject;
import com.birditch.raidcore.map.MapValidationIssue;
import com.birditch.raidcore.map.MapValidationSeverity;
import com.birditch.raidcore.game.plugin.integration.EditorHologramView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Pure selection step for the personal DecentHolograms editor overlay. */
final class EditorHologramPlanner {

  private EditorHologramPlanner() {}

  static List<PlannedLabel> plan(ManualRaidMap map, List<MapObject> nearby,
      Map<String, List<MapValidationIssue>> validation, @Nullable String selectedId,
      int maximumHologramsPerPlayer) {
    Preconditions.checkNotNull(map, "map");
    Preconditions.checkNotNull(nearby, "nearby");
    Preconditions.checkNotNull(validation, "validation");
    Preconditions.checkArgument(maximumHologramsPerPlayer > 0,
        "maximumHologramsPerPlayer must be positive");

    List<PlannedLabel> labels = new ArrayList<>();
    if (selectedId != null) {
      map.findObject(selectedId).ifPresent(object -> labels.add(new PlannedLabel(
          "selected_" + object.id(), object,
          validation.getOrDefault(object.id(), List.of()),
          EditorHologramView.Style.SELECTED)));
    }

    // MapObjectIndex supplies nearest-first input. Keep validation errors ahead of
    // ordinary labels so a crowded area cannot hide a broken object.
    nearby.stream()
        .filter(object -> !object.id().equals(selectedId))
        .filter(object -> validation.getOrDefault(object.id(), List.of()).stream()
            .anyMatch(issue -> issue.severity() == MapValidationSeverity.ERROR))
        .limit(Math.max(0, maximumHologramsPerPlayer - labels.size()))
        .forEach(object -> labels.add(new PlannedLabel("error_" + object.id(), object,
            validation.getOrDefault(object.id(), List.of()), EditorHologramView.Style.ERROR)));

    int remaining = maximumHologramsPerPlayer - labels.size();
    nearby.stream()
        .filter(object -> !object.id().equals(selectedId))
        .filter(object -> validation.getOrDefault(object.id(), List.of()).stream()
            .noneMatch(issue -> issue.severity() == MapValidationSeverity.ERROR))
        .limit(remaining)
        .forEach(object -> labels.add(new PlannedLabel("nearby_" + object.id(), object,
            validation.getOrDefault(object.id(), List.of()), EditorHologramView.Style.NEARBY)));
    return List.copyOf(labels);
  }

  record PlannedLabel(String key, MapObject object, List<MapValidationIssue> validationIssues,
                      EditorHologramView.Style style) {

    PlannedLabel {
      validationIssues = List.copyOf(validationIssues);
    }

    boolean selected() {
      return style == EditorHologramView.Style.SELECTED;
    }
  }
}
