package com.birditch.escapefromcraftov.game.plugin.editor;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.map.ManualMapValidationSettings;
import org.bukkit.configuration.Configuration;

public final class MapEditorConfigMapper {

  private MapEditorConfigMapper() {}

  public static MapEditorSettings read(Configuration yaml) {
    Preconditions.checkNotNull(yaml, "yaml");
    return new MapEditorSettings(
        yaml.getInt("map-editor.retained-revisions", 3),
        yaml.getInt("map-editor.confirmation-seconds", 15),
        yaml.getInt("map-editor.default-visibility-radius", 96),
        yaml.getInt("map-editor.visualization.refresh-ticks", 10),
        yaml.getInt("map-editor.visualization.maximum-objects-per-frame", 160),
        yaml.getInt("map-editor.visualization.maximum-holograms-per-player", 32),
        new ManualMapValidationSettings(
            yaml.getInt("map-editor.validation.minimum-spawns", 2),
            yaml.getInt("map-editor.validation.minimum-extractions", 2),
            yaml.getInt("map-editor.validation.minimum-loot-zones", 1),
            yaml.getInt("map-editor.validation.minimum-ai-zones", 1),
            yaml.getInt("map-editor.validation.minimum-pois", 1),
            yaml.getDouble("map-editor.validation.minimum-spawn-distance", 32),
            yaml.getDouble("map-editor.validation.minimum-spawn-extraction-distance", 64)));
  }
}
