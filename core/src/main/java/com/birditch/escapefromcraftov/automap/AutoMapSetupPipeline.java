package com.birditch.escapefromcraftov.automap;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/// Pure deterministic pipeline. Paper world access and YAML persistence live
/// outside this class so every generation stage can be unit tested.
public final class AutoMapSetupPipeline {

  public static final int SCHEMA_VERSION = 1;
  public static final String GENERATOR_VERSION = "1.0";

  private final TerrainAnalyzer terrainAnalyzer = new TerrainAnalyzer();
  private final POIGenerator poiGenerator = new POIGenerator();
  private final SpawnGenerator spawnGenerator = new SpawnGenerator();
  private final LootGenerator lootGenerator = new LootGenerator();
  private final AISpawnGenerator aiGenerator = new AISpawnGenerator();
  private final ExtractionGenerator extractionGenerator = new ExtractionGenerator();
  private final MapScore mapScore = new MapScore();
  private final MapValidator validator = new MapValidator();

  public CraftovMapDefinition generate(String mapId, MapSnapshot snapshot,
      AutoSetupSettings settings, long generationSeed, int revision,
      long generatedAtEpochMillis, Consumer<AutoSetupStage> progress) {
    Preconditions.checkNotNull(mapId, "mapId");
    Preconditions.checkNotNull(snapshot, "snapshot");
    Preconditions.checkNotNull(settings, "settings");
    Preconditions.checkNotNull(progress, "progress");
    Preconditions.checkArgument(!mapId.isBlank(), "mapId must not be blank");
    progress.accept(AutoSetupStage.VALIDATE_WORLD);
    Preconditions.checkArgument(!snapshot.samples().isEmpty(), "World snapshot has no samples");

    progress.accept(AutoSetupStage.ANALYZE_TERRAIN);
    List<TerrainSample> terrain = terrainAnalyzer.analyze(snapshot);

    progress.accept(AutoSetupStage.GENERATE_POI);
    List<PointOfInterest> pois = poiGenerator.generate(terrain, snapshot.bounds(),
        snapshot.sampleStep(), settings.poi());

    progress.accept(AutoSetupStage.GENERATE_SPAWNS);
    List<SpawnRegion> spawns = spawnGenerator.generate(terrain, snapshot.bounds(), pois,
        settings.spawns(), generationSeed);

    progress.accept(AutoSetupStage.GENERATE_LOOT_AI);
    LootGenerator.Result loot = lootGenerator.generate(terrain, snapshot.bounds(), pois,
        settings.loot(), generationSeed);
    List<AiSpawnRegion> ai = aiGenerator.generate(pois, settings.ai());

    progress.accept(AutoSetupStage.GENERATE_EXTRACTIONS);
    List<ExtractionRegion> extractions = extractionGenerator.generate(terrain,
        snapshot.bounds(), spawns, settings.extractions(), generationSeed);

    progress.accept(AutoSetupStage.VALIDATE_BALANCE);
    int score = mapScore.calculate(snapshot.bounds(), spawns, loot.zones(), loot.points(),
        pois, extractions);
    MapValidationReport report = validator.validate(score, snapshot.bounds(), pois, spawns,
        loot.zones(), loot.points(), extractions, settings);
    MapSetupState state = report.hasErrors() ? MapSetupState.FAILED : MapSetupState.READY;
    return new CraftovMapDefinition(SCHEMA_VERSION, GENERATOR_VERSION, mapId,
        snapshot.worldName(), snapshot.bounds(), generationSeed, revision,
        generatedAtEpochMillis, state, false, score, terrainCounts(terrain), pois, spawns,
        loot.zones(), loot.points(), ai, extractions, report);
  }

  private static Map<TerrainType, Integer> terrainCounts(List<TerrainSample> samples) {
    Map<TerrainType, Integer> counts = new EnumMap<>(TerrainType.class);
    for (TerrainSample sample : samples) counts.merge(sample.type(), 1, Integer::sum);
    return counts;
  }
}
