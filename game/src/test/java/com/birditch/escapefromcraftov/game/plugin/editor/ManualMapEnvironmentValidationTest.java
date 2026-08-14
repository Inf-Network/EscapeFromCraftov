package com.birditch.escapefromcraftov.game.plugin.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.map.BlockPosition;
import com.birditch.escapefromcraftov.map.CuboidBounds;
import com.birditch.escapefromcraftov.map.ExtractionDefinition;
import com.birditch.escapefromcraftov.map.LootPointDefinition;
import com.birditch.escapefromcraftov.map.ManualCraftovMap;
import com.birditch.escapefromcraftov.map.ManualExtractionType;
import com.birditch.escapefromcraftov.map.ManualMapReferences;
import com.birditch.escapefromcraftov.map.ManualMapValidationResult;
import com.birditch.escapefromcraftov.map.ManualMapValidationSettings;
import com.birditch.escapefromcraftov.map.MapValidationIssue;
import com.birditch.escapefromcraftov.map.MapValidationSeverity;
import com.birditch.escapefromcraftov.map.SpawnDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualMapEnvironmentValidationTest {

  @TempDir Path temporaryDirectory;

  @Test
  void oldConstructorRemainsEnvironmentNeutral() {
    ManualMapStore store = new ManualMapStore(temporaryDirectory.resolve("old"));
    ManualMapService service = new ManualMapService(store, new ManualMapRegistry(store),
        validationSettings(), _ -> ManualMapReferences.allowing("craftov_tier_1"));

    ManualMapValidationResult result = service.validate(validMap());

    assertEquals(100, result.qualityScore());
    assertTrue(result.issues().isEmpty());
  }

  @Test
  void mergesEnvironmentIssuesAndRecomputesQualityScore() {
    ManualMapStore store = new ManualMapStore(temporaryDirectory.resolve("merged"));
    ManualMapEnvironmentValidator environment = _ -> List.of(new MapValidationIssue(
        MapValidationSeverity.ERROR, "spawn_environment_unsafe", "spawn_1", "unsafe"));
    ManualMapService service = new ManualMapService(store, new ManualMapRegistry(store),
        validationSettings(), _ -> ManualMapReferences.allowing("craftov_tier_1"),
        null, null, environment);

    ManualMapValidationResult result = service.validate(validMap());

    assertEquals(80, result.qualityScore());
    assertEquals(1, result.errorCount());
    assertEquals("spawn_environment_unsafe", result.issues().getFirst().code());
  }

  @Test
  void environmentErrorPreventsMapEnablement() {
    ManualMapStore store = new ManualMapStore(temporaryDirectory.resolve("enable"));
    ManualCraftovMap persisted = store.save(validMap());
    ManualMapRegistry registry = new ManualMapRegistry(store);
    ManualMapEnvironmentValidator environment = _ -> List.of(new MapValidationIssue(
        MapValidationSeverity.ERROR, "extraction_environment_unsafe", "exit_1",
        "区域 Y 必须覆盖玩家脚部，而不是只圈地面方块"));
    ManualMapService service = new ManualMapService(store, registry, validationSettings(),
        _ -> ManualMapReferences.allowing("craftov_tier_1"), null, null, environment);

    assertThrows(IllegalStateException.class,
        () -> service.setEnabled(persisted.mapId(), true));
  }

  @Test
  void environmentValidatorIsSkippedWhenWorldReferenceIsMissing() {
    ManualMapStore store = new ManualMapStore(temporaryDirectory.resolve("missing"));
    ManualMapService service = new ManualMapService(store, new ManualMapRegistry(store),
        validationSettings(), _ -> new ManualMapReferences(false, java.util.Set.of(
            "craftov_tier_1"), java.util.Set.of()), null, null,
        _ -> List.of(new MapValidationIssue(MapValidationSeverity.ERROR,
            "spawn_environment_unsafe", "spawn_1", "must not be merged")));

    ManualMapValidationResult result = service.validate(validMap());

    assertEquals(1, result.errorCount());
    assertEquals("world_missing", result.issues().getFirst().code());
  }

  private static ManualMapValidationSettings validationSettings() {
    return new ManualMapValidationSettings(2, 2, 0, 0, 0, 0, 0);
  }

  private static ManualCraftovMap validMap() {
    return new ManualCraftovMap(ManualCraftovMap.SCHEMA_VERSION, "test_flat", "Test Flat",
        "craftov_test_flat", new CuboidBounds(0, 0, 0, 100, 100, 100), 0, false, Map.of(),
        List.of(
            new SpawnDefinition("spawn_1", new BlockPosition(10, 64, 10), 0, 0,
                "players", 1, true, null),
            new SpawnDefinition("spawn_2", new BlockPosition(90, 64, 10), 0, 0,
                "players", 1, true, null)),
        List.of(new LootPointDefinition("loot_1", new BlockPosition(50, 64, 50), "chest",
            "craftov_tier_1", 1, false, true)),
        List.of(), List.of(),
        List.of(
            new ExtractionDefinition("exit_1", "Exit 1",
                new CuboidBounds(8, 63, 88, 12, 66, 92), ManualExtractionType.NORMAL, 10,
                null, true),
            new ExtractionDefinition("exit_2", "Exit 2",
                new CuboidBounds(88, 63, 88, 92, 66, 92), ManualExtractionType.NORMAL, 10,
                null, true)),
        List.of());
  }
}
