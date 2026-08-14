package com.birditch.escapefromcraftov.game.plugin.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.birditch.escapefromcraftov.api.game.LegacyRaidTierResolver;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import com.birditch.escapefromcraftov.map.AiZoneDefinition;
import com.birditch.escapefromcraftov.map.BlockPosition;
import com.birditch.escapefromcraftov.map.CuboidBounds;
import com.birditch.escapefromcraftov.map.ExtractionDefinition;
import com.birditch.escapefromcraftov.map.ExtractionRuleDefinition;
import com.birditch.escapefromcraftov.map.LootPointDefinition;
import com.birditch.escapefromcraftov.map.LootZoneDefinition;
import com.birditch.escapefromcraftov.map.ManualExtractionType;
import com.birditch.escapefromcraftov.map.ManualCraftovMap;
import com.birditch.escapefromcraftov.map.PoiDefinition;
import com.birditch.escapefromcraftov.map.SpawnDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualMapStoreTest {

  @TempDir Path temporaryDirectory;

  @Test
  void roundTripsEveryAuthoredObject() {
    ManualMapStore store = new ManualMapStore(temporaryDirectory);

    ManualCraftovMap persisted = store.save(map());

    assertEquals(1, persisted.revision());
    assertEquals(persisted, store.load("factory").orElseThrow());
  }

  @Test
  void legacyYamlWithoutActionTiersGetsCanonicalCompatibilityProfiles() throws Exception {
    Path directory = temporaryDirectory.resolve("legacy");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("map.yml"), """
        schema-version: 1
        map-id: legacy
        display-name: Legacy
        world: world
        revision: 4
        enabled: false
        metadata: {}
        spawns:
          - id: legacy_spawn
            enabled: true
            position: {x: 1, y: 64, z: 1}
            yaw: 0
            pitch: 0
            team: players
            weight: 1.0
        loot-points: []
        loot-zones: []
        ai-zones: []
        extractions:
          - id: legacy_exit
            enabled: true
            display-name: Legacy Exit
            bounds: {min-x: 10, min-y: 60, min-z: 10, max-x: 12, max-y: 70, max-z: 12}
            type: NORMAL
            duration-seconds: 10
        pois: []
        """);

    ManualCraftovMap loaded = new ManualMapStore(temporaryDirectory)
        .load("legacy").orElseThrow();

    assertEquals(LegacyRaidTierResolver.defaultProfiles(), loaded.actionTiers());
    assertEquals("legacy_spawn", loaded.spawns().getFirst().routeGroup());
    assertEquals(null, loaded.extractions().getFirst().routeScope());
  }

  @Test
  void rollbackCreatesNewRevisionAndStaleDraftCannotOverwriteIt() {
    ManualMapStore store = new ManualMapStore(temporaryDirectory);
    ManualCraftovMap first = store.save(map());
    ManualCraftovMap second = store.save(first.withBounds(new CuboidBounds(0, 0, 0, 600, 100, 600)));

    assertEquals(List.of(2L, 1L), store.revisions("factory"));
    assertThrows(MapRevisionConflictException.class, () ->
        store.save(first.withEnabled(true)));

    ManualCraftovMap rolledBack = store.rollback("factory", 1);

    assertEquals(3, rolledBack.revision());
    assertEquals(first.bounds(), rolledBack.bounds());
    assertEquals(second.revision(), 2);
  }

  @Test
  void importsCompletePackageAndCopiesTemplateFiles() throws Exception {
    Path packageDirectory = temporaryDirectory.resolve("imports/factory");
    ManualMapStore packageStore = new ManualMapStore(packageDirectory.getParent());
    packageStore.save(map());
    Files.writeString(packageDirectory.resolve("level.dat"), "template");

    Path mapsRoot = temporaryDirectory.resolve("maps");
    ManualMapStore targetStore = new ManualMapStore(mapsRoot);
    ManualMapRegistry registry = new ManualMapRegistry(targetStore);
    ManualMapService service = new ManualMapService(targetStore, registry,
        com.birditch.escapefromcraftov.map.ManualMapValidationSettings.defaults(),
        _ -> com.birditch.escapefromcraftov.map.ManualMapReferences.allowing("debug_common"), mapsRoot);

    ManualCraftovMap imported = service.importPackage("factory_copy", packageDirectory);

    assertEquals("factory_copy", imported.mapId());
    assertEquals(1, imported.spawns().size());
    assertEquals(1, imported.lootPoints().size());
    assertEquals(1, imported.extractions().size());
    assertTrue(Files.isRegularFile(mapsRoot.resolve("factory_copy/level.dat")));
    assertEquals(imported, targetStore.load("factory_copy").orElseThrow());
  }

  @Test
  void importsRawMinecraftWorldIntoDedicatedEditorSource() throws Exception {
    Path rawWorld = temporaryDirectory.resolve("imports/castle");
    Files.createDirectories(rawWorld.resolve("region"));
    Files.writeString(rawWorld.resolve("level.dat"), "world");
    Files.writeString(rawWorld.resolve("uid.dat"), "source-uuid");
    Files.writeString(rawWorld.resolve("session.lock"), "source-lock");
    Files.writeString(rawWorld.resolve("region/r.0.0.mca"), "region");

    Path mapsRoot = temporaryDirectory.resolve("maps");
    Path worldRoot = temporaryDirectory.resolve("worlds");
    ManualMapStore targetStore = new ManualMapStore(mapsRoot);
    ManualMapRegistry registry = new ManualMapRegistry(targetStore);
    ManualMapService service = new ManualMapService(targetStore, registry,
        com.birditch.escapefromcraftov.map.ManualMapValidationSettings.defaults(),
        _ -> com.birditch.escapefromcraftov.map.ManualMapReferences.allowing("debug_common"),
        mapsRoot, worldRoot);

    ManualCraftovMap imported = service.importPackage("castle", rawWorld);
    Path editorWorld = worldRoot.resolve("craftov_source_castle");

    assertEquals("castle", imported.mapId());
    assertEquals("craftov_source_castle", imported.worldName());
    assertEquals(1, imported.revision());
    assertFalse(imported.enabled());
    assertTrue(imported.objects().isEmpty());
    assertTrue(Files.isRegularFile(editorWorld.resolve("level.dat")));
    assertTrue(Files.isRegularFile(editorWorld.resolve("region/r.0.0.mca")));
    assertFalse(Files.exists(editorWorld.resolve("uid.dat")));
    assertFalse(Files.exists(editorWorld.resolve("session.lock")));
    assertTrue(Files.isRegularFile(rawWorld.resolve("uid.dat")));
    assertEquals(imported, targetStore.load("castle").orElseThrow());
  }

  @Test
  void rejectsSymbolicLinksInsideImportedWorld() throws Exception {
    Path rawWorld = temporaryDirectory.resolve("imports/linked");
    Files.createDirectories(rawWorld.resolve("region"));
    Files.writeString(rawWorld.resolve("level.dat"), "world");
    Path outside = temporaryDirectory.resolve("outside.mca");
    Files.writeString(outside, "must-not-be-copied");
    try {
      Files.createSymbolicLink(rawWorld.resolve("region/r.0.0.mca"), outside);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException error) {
      assumeTrue(false, "symbolic links are unavailable on this test filesystem");
    }

    Path mapsRoot = temporaryDirectory.resolve("maps");
    Path worldRoot = temporaryDirectory.resolve("worlds");
    ManualMapStore targetStore = new ManualMapStore(mapsRoot);
    ManualMapService service = new ManualMapService(targetStore,
        new ManualMapRegistry(targetStore),
        com.birditch.escapefromcraftov.map.ManualMapValidationSettings.defaults(),
        _ -> com.birditch.escapefromcraftov.map.ManualMapReferences.allowing("debug_common"),
        mapsRoot, worldRoot);

    assertThrows(com.birditch.escapefromcraftov.api.CraftovException.class,
        () -> service.importPackage("linked", rawWorld));
    assertFalse(Files.exists(worldRoot.resolve("craftov_source_linked")));
    assertEquals("must-not-be-copied", Files.readString(outside));
  }

  @Test
  void rejectsSymbolicLinkMapDirectoriesWhenLoadingCatalog() throws Exception {
    Path mapsRoot = temporaryDirectory.resolve("maps");
    ManualMapStore store = new ManualMapStore(mapsRoot);
    Path safe = temporaryDirectory.resolve("safe");
    Files.createDirectories(safe);
    store.save(map());
    Path link = mapsRoot.resolve("alias");
    try {
      Files.createSymbolicLink(link, safe);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException error) {
      assumeTrue(false, "symbolic links are unavailable on this test filesystem");
    }

    assertThrows(com.birditch.escapefromcraftov.api.CraftovException.class, store::loadAll);
  }

  private static ManualCraftovMap map() {
    CuboidBounds bounds = new CuboidBounds(0, 0, 0, 500, 100, 500);
    return new ManualCraftovMap(ManualCraftovMap.SCHEMA_VERSION, "factory", "Factory", "world",
        bounds, 0, false, Map.of("author", "admin"),
        List.of(new SpawnDefinition("spawn_a", new BlockPosition(20, 64, 20), 10, 0,
            "players", 1, true, null, "north")),
        List.of(new LootPointDefinition("loot_a", new BlockPosition(250, 64, 250),
            "virtual", "debug_common", 2, true, true)),
        List.of(new LootZoneDefinition("loot_zone_a",
            new CuboidBounds(200, 50, 200, 300, 80, 300), "debug_common", 2, 1, 0.5, true)),
        List.of(new AiZoneDefinition("ai_zone_a",
            new CuboidBounds(210, 50, 210, 290, 80, 290), "scav", 2, 4, 60, 1,
            "scav", true)),
        List.of(new ExtractionDefinition("north_gate", "North Gate",
            new CuboidBounds(10, 60, 450, 18, 70, 458), ManualExtractionType.NORMAL, 10,
            null, true, List.of(
                new ExtractionRuleDefinition.SwitchActivated("north_power",
                    new BlockPosition(30, 64, 430)),
                new ExtractionRuleDefinition.SwitchActivated("backup_power",
                    new BlockPosition(32, 64, 430)),
                new ExtractionRuleDefinition.RandomOpen(0.5),
                new ExtractionRuleDefinition.NoBackpack(),
                new ExtractionRuleDefinition.MaxWeight(30_000),
                new ExtractionRuleDefinition.Capacity(3)),
            new com.birditch.escapefromcraftov.map.ExtractionRouteScope(
                false, List.of("north")))),
        List.of(new PoiDefinition("central_factory", "Central Factory",
            new CuboidBounds(180, 40, 180, 320, 90, 320), 3, true)),
        List.of(new RaidActionProfile(RaidTierId.of("top_secret"), "绝密行动", 112_500,
            1.875, 3.0, 5_000, List.of(new RaidActionProfile.LegacyAlias(
                RaidMode.CONFIDENTIAL, RaidDifficulty.HARD)))));
  }
}
