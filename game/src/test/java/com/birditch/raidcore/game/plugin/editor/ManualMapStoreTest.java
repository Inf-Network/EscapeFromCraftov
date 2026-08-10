package com.birditch.raidcore.game.plugin.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.birditch.raidcore.map.AiZoneDefinition;
import com.birditch.raidcore.map.BlockPosition;
import com.birditch.raidcore.map.CuboidBounds;
import com.birditch.raidcore.map.ExtractionDefinition;
import com.birditch.raidcore.map.LootPointDefinition;
import com.birditch.raidcore.map.LootZoneDefinition;
import com.birditch.raidcore.map.ManualExtractionType;
import com.birditch.raidcore.map.ManualRaidMap;
import com.birditch.raidcore.map.PoiDefinition;
import com.birditch.raidcore.map.SpawnDefinition;
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

    ManualRaidMap persisted = store.save(map());

    assertEquals(1, persisted.revision());
    assertEquals(persisted, store.load("factory").orElseThrow());
  }

  @Test
  void rollbackCreatesNewRevisionAndStaleDraftCannotOverwriteIt() {
    ManualMapStore store = new ManualMapStore(temporaryDirectory);
    ManualRaidMap first = store.save(map());
    ManualRaidMap second = store.save(first.withBounds(new CuboidBounds(0, 0, 0, 600, 100, 600)));

    assertEquals(List.of(2L, 1L), store.revisions("factory"));
    assertThrows(MapRevisionConflictException.class, () ->
        store.save(first.withEnabled(true)));

    ManualRaidMap rolledBack = store.rollback("factory", 1);

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
        com.birditch.raidcore.map.ManualMapValidationSettings.defaults(),
        _ -> com.birditch.raidcore.map.ManualMapReferences.allowing("debug_common"), mapsRoot);

    ManualRaidMap imported = service.importPackage("factory_copy", packageDirectory);

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
        com.birditch.raidcore.map.ManualMapValidationSettings.defaults(),
        _ -> com.birditch.raidcore.map.ManualMapReferences.allowing("debug_common"),
        mapsRoot, worldRoot);

    ManualRaidMap imported = service.importPackage("castle", rawWorld);
    Path editorWorld = worldRoot.resolve("raid_source_castle");

    assertEquals("castle", imported.mapId());
    assertEquals("raid_source_castle", imported.worldName());
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
        com.birditch.raidcore.map.ManualMapValidationSettings.defaults(),
        _ -> com.birditch.raidcore.map.ManualMapReferences.allowing("debug_common"),
        mapsRoot, worldRoot);

    assertThrows(com.birditch.raidcore.api.RaidException.class,
        () -> service.importPackage("linked", rawWorld));
    assertFalse(Files.exists(worldRoot.resolve("raid_source_linked")));
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

    assertThrows(com.birditch.raidcore.api.RaidException.class, store::loadAll);
  }

  private static ManualRaidMap map() {
    CuboidBounds bounds = new CuboidBounds(0, 0, 0, 500, 100, 500);
    return new ManualRaidMap(ManualRaidMap.SCHEMA_VERSION, "factory", "Factory", "world",
        bounds, 0, false, Map.of("author", "admin"),
        List.of(new SpawnDefinition("spawn_a", new BlockPosition(20, 64, 20), 10, 0,
            "players", 1, true, null)),
        List.of(new LootPointDefinition("loot_a", new BlockPosition(250, 64, 250),
            "virtual", "debug_common", 2, true, true)),
        List.of(new LootZoneDefinition("loot_zone_a",
            new CuboidBounds(200, 50, 200, 300, 80, 300), "debug_common", 2, 1, 0.5, true)),
        List.of(new AiZoneDefinition("ai_zone_a",
            new CuboidBounds(210, 50, 210, 290, 80, 290), "scav", 2, 4, 60, 1,
            "scav", true)),
        List.of(new ExtractionDefinition("north_gate", "North Gate",
            new CuboidBounds(10, 60, 450, 18, 70, 458), ManualExtractionType.NORMAL, 10,
            null, true)),
        List.of(new PoiDefinition("central_factory", "Central Factory",
            new CuboidBounds(180, 40, 180, 320, 90, 320), 3, true)));
  }
}
