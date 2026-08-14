package com.birditch.escapefromcraftov.game.plugin.editor;

import com.birditch.escapefromcraftov.api.CraftovException;
import com.birditch.escapefromcraftov.api.game.LegacyRaidTierResolver;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.map.AiZoneDefinition;
import com.birditch.escapefromcraftov.map.BlockPosition;
import com.birditch.escapefromcraftov.map.CuboidBounds;
import com.birditch.escapefromcraftov.map.ExtractionDefinition;
import com.birditch.escapefromcraftov.map.ExtractionRuleDefinition;
import com.birditch.escapefromcraftov.map.ExtractionRouteScope;
import com.birditch.escapefromcraftov.map.LootPointDefinition;
import com.birditch.escapefromcraftov.map.LootZoneDefinition;
import com.birditch.escapefromcraftov.map.ManualExtractionType;
import com.birditch.escapefromcraftov.map.ManualCraftovMap;
import com.birditch.escapefromcraftov.map.PoiDefinition;
import com.birditch.escapefromcraftov.map.SpawnDefinition;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

/// Atomic, human-readable storage for administrator-authored Craftov maps.
public final class ManualMapStore {

  private static final Pattern ID = Pattern.compile("[a-z0-9_]+");
  private final Path root;
  private final int retainedRevisions;

  public ManualMapStore(Path root) {
    this(root, 3);
  }

  public ManualMapStore(Path root, int retainedRevisions) {
    this.root = Preconditions.checkNotNull(root, "root");
    Preconditions.checkArgument(retainedRevisions >= 1, "retainedRevisions must be positive");
    this.retainedRevisions = retainedRevisions;
  }

  public List<ManualCraftovMap> loadAll() {
    rejectSymbolicLink(root, "地图根目录");
    if (!Files.isDirectory(root)) return List.of();
    try (Stream<Path> directories = Files.list(root)) {
      return directories.filter(Files::isDirectory)
          .map(path -> {
            rejectSymbolicLink(path, "地图目录");
            return path.resolve("map.yml");
          })
          .filter(path -> Files.isRegularFile(path))
          .sorted()
          .map(this::loadFile)
          .toList();
    } catch (IOException e) {
      throw new CraftovException("无法读取地图配置目录: " + root, e);
    }
  }

  public Optional<ManualCraftovMap> load(String mapId) {
    Path file = mapFile(mapId);
    Path directory = file.getParent();
    if (Files.isSymbolicLink(directory)) {
      throw new CraftovException("拒绝读取符号链接地图目录: " + directory);
    }
    if (Files.isSymbolicLink(file)) {
      throw new CraftovException("拒绝读取符号链接地图文件: " + file);
    }
    return Files.isRegularFile(file) ? Optional.of(loadFile(file)) : Optional.empty();
  }

  /** Loads the authored map manifest from an external map package directory. */
  public ManualCraftovMap loadPackage(Path packageDirectory) {
    Preconditions.checkNotNull(packageDirectory, "packageDirectory");
    rejectSymbolicLink(packageDirectory, "地图包目录");
    Path manifest = packageDirectory.resolve("map.yml");
    rejectSymbolicLink(manifest, "地图包清单");
    if (!Files.isRegularFile(manifest)) {
      throw new IllegalArgumentException("地图包缺少 map.yml: " + packageDirectory.getFileName());
    }
    return loadFile(manifest);
  }

  /// Saves only when the draft revision still matches disk, preventing lost updates.
  public synchronized ManualCraftovMap save(ManualCraftovMap draft) {
    Preconditions.checkNotNull(draft, "draft");
    rejectSymbolicLink(root, "地图根目录");
    Optional<ManualCraftovMap> current = load(draft.mapId());
    if (current.isPresent() && current.get().revision() != draft.revision()) {
      throw new MapRevisionConflictException(draft.mapId(), draft.revision(),
          current.get().revision());
    }
    if (current.isEmpty() && draft.revision() != 0) {
      throw new MapRevisionConflictException(draft.mapId(), draft.revision(), 0);
    }
    ManualCraftovMap persisted = draft.withRevision(draft.revision() + 1, draft.enabled());
    Path target = mapFile(draft.mapId());
    try {
      Files.createDirectories(target.getParent());
      if (current.isPresent()) backup(target, current.get().revision());
      writeAtomically(target, toYaml(persisted));
      prune(target.getParent().resolve("revisions"), retainedRevisions);
      return persisted;
    } catch (IOException e) {
      throw new CraftovException("无法保存地图配置: " + draft.mapId(), e);
    }
  }

  public synchronized ManualCraftovMap rollback(String mapId, long revision) {
    ManualCraftovMap current = load(mapId)
        .orElseThrow(() -> new IllegalArgumentException("地图不存在: " + mapId));
    if (current.revision() == revision) return current;
    Path revisionFile = revisionFile(mapId, revision);
    if (!Files.isRegularFile(revisionFile)) {
      throw new IllegalArgumentException("地图 " + mapId + " 没有修订版本 " + revision);
    }
    ManualCraftovMap old = loadFile(revisionFile);
    ManualCraftovMap draft = new ManualCraftovMap(old.schemaVersion(), old.mapId(), old.displayName(),
        old.worldName(), old.bounds(), current.revision(), old.enabled(), old.metadata(),
        old.spawns(), old.lootPoints(), old.lootZones(), old.aiZones(), old.extractions(),
        old.pois(), old.actionTiers());
    return save(draft);
  }

  public List<Long> revisions(String mapId) {
    rejectSymbolicLink(root, "地图根目录");
    List<Long> result = new ArrayList<>();
    load(mapId).ifPresent(map -> result.add(map.revision()));
    Path directory = mapFile(mapId).getParent().resolve("revisions");
    if (!Files.isDirectory(directory)) return List.copyOf(result);
    try (Stream<Path> files = Files.list(directory)) {
      files.filter(Files::isRegularFile)
          .map(path -> parseRevision(path.getFileName().toString()))
          .filter(Optional::isPresent)
          .map(Optional::get)
          .forEach(result::add);
      return result.stream().distinct().sorted(Comparator.reverseOrder()).toList();
    } catch (IOException e) {
      throw new CraftovException("无法读取地图修订历史: " + mapId, e);
    }
  }

  private void backup(Path current, long revision) throws IOException {
    Path revisions = current.getParent().resolve("revisions");
    Files.createDirectories(revisions);
    Files.copy(current, revisions.resolve(fileName(revision)),
        StandardCopyOption.REPLACE_EXISTING);
  }

  private static void prune(Path revisions, int retainedRevisions) throws IOException {
    if (!Files.isDirectory(revisions)) return;
    try (Stream<Path> files = Files.list(revisions)) {
      List<Path> newestFirst = files.filter(Files::isRegularFile)
          .sorted(Comparator.reverseOrder()).toList();
      for (int index = retainedRevisions; index < newestFirst.size(); index++) {
        Files.deleteIfExists(newestFirst.get(index));
      }
    }
  }

  private Path mapFile(String mapId) {
    rejectSymbolicLink(root, "地图根目录");
    requireId(mapId, "mapId");
    return root.resolve(mapId).resolve("map.yml");
  }

  private static void rejectSymbolicLink(Path path, String description) {
    if (Files.isSymbolicLink(path)) {
      throw new CraftovException("拒绝读取符号链接" + description + ": " + path);
    }
  }

  private Path revisionFile(String mapId, long revision) {
    Preconditions.checkArgument(revision >= 0, "revision must not be negative");
    return mapFile(mapId).getParent().resolve("revisions").resolve(fileName(revision));
  }

  private static String fileName(long revision) {
    return String.format("revision-%06d.yml", revision);
  }

  private static Optional<Long> parseRevision(String fileName) {
    if (!fileName.startsWith("revision-") || !fileName.endsWith(".yml")) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(fileName.substring(9, fileName.length() - 4)));
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  private ManualCraftovMap loadFile(Path file) {
    rejectSymbolicLink(file, "地图配置文件");
    try {
      YamlConfiguration yaml = new YamlConfiguration();
      yaml.load(file.toFile());
      return fromYaml(yaml);
    } catch (IOException | InvalidConfigurationException | RuntimeException e) {
      throw new CraftovException("地图 YAML 无效: " + file, e);
    }
  }

  private static void writeAtomically(Path target, YamlConfiguration yaml) throws IOException {
    Path temporary = Files.createTempFile(target.getParent(), "map-", ".tmp");
    try {
      yaml.save(temporary.toFile());
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static YamlConfiguration toYaml(ManualCraftovMap map) {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.options().setHeader(List.of(
        "EscapeFromCraftov 人工地图配置。游戏内编辑器保存前会自动验证。",
        "直接编辑此文件前请停止对应编辑会话，并保留 revisions/ 目录。"));
    yaml.set("schema-version", map.schemaVersion());
    yaml.set("map-id", map.mapId());
    yaml.set("display-name", map.displayName());
    yaml.set("world", map.worldName());
    yaml.set("revision", map.revision());
    yaml.set("enabled", map.enabled());
    yaml.set("metadata", map.metadata());
    if (map.bounds() != null) yaml.set("bounds", boundsMap(map.bounds()));
    yaml.set("action-tiers",
        map.actionTiers().stream().map(ManualMapStore::actionTierMap).toList());
    yaml.set("spawns", map.spawns().stream().map(ManualMapStore::spawnMap).toList());
    yaml.set("loot-points", map.lootPoints().stream().map(ManualMapStore::lootPointMap).toList());
    yaml.set("loot-zones", map.lootZones().stream().map(ManualMapStore::lootZoneMap).toList());
    yaml.set("ai-zones", map.aiZones().stream().map(ManualMapStore::aiZoneMap).toList());
    yaml.set("extractions", map.extractions().stream().map(ManualMapStore::extractionMap).toList());
    yaml.set("pois", map.pois().stream().map(ManualMapStore::poiMap).toList());
    return yaml;
  }

  private static ManualCraftovMap fromYaml(YamlConfiguration yaml) {
    int schema = yaml.getInt("schema-version", ManualCraftovMap.SCHEMA_VERSION);
    String mapId = required(yaml.getString("map-id"), "map-id");
    Map<String, String> metadata = new LinkedHashMap<>();
    ConfigurationSection metadataSection = yaml.getConfigurationSection("metadata");
    if (metadataSection != null) metadataSection.getValues(false)
        .forEach((key, value) -> metadata.put(key, String.valueOf(value)));
    CuboidBounds bounds = yaml.contains("bounds")
        ? readBounds(sectionMap(yaml, "bounds")) : null;
    List<RaidActionProfile> actionTiers = yaml.contains("action-tiers")
        ? yaml.getMapList("action-tiers").stream().map(ManualMapStore::readActionTier).toList()
        : LegacyRaidTierResolver.defaultProfiles();
    return new ManualCraftovMap(schema, mapId,
        required(yaml.getString("display-name"), "display-name"),
        required(yaml.getString("world"), "world"), bounds, yaml.getLong("revision"),
        yaml.getBoolean("enabled"), metadata,
        yaml.getMapList("spawns").stream().map(ManualMapStore::readSpawn).toList(),
        yaml.getMapList("loot-points").stream().map(ManualMapStore::readLootPoint).toList(),
        yaml.getMapList("loot-zones").stream().map(ManualMapStore::readLootZone).toList(),
        yaml.getMapList("ai-zones").stream().map(ManualMapStore::readAiZone).toList(),
        yaml.getMapList("extractions").stream().map(ManualMapStore::readExtraction).toList(),
        yaml.getMapList("pois").stream().map(ManualMapStore::readPoi).toList(), actionTiers);
  }

  private static Map<String, Object> actionTierMap(RaidActionProfile value) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", value.id().value());
    result.put("display-name", value.displayName());
    result.put("minimum-loadout-value", value.minimumLoadoutValue());
    result.put("ai-multiplier", value.aiMultiplier());
    result.put("loot-multiplier", value.lootMultiplier());
    result.put("entry-fee", value.entryFee());
    result.put("legacy-aliases", value.legacyAliases().stream().map(alias -> Map.of(
        "mode", alias.mode().id(), "difficulty", alias.difficulty().id())).toList());
    return result;
  }

  private static RaidActionProfile readActionTier(Map<?, ?> map) {
    return new RaidActionProfile(RaidTierId.of(string(map, "id")),
        string(map, "display-name"), number(map, "minimum-loadout-value").longValue(),
        number(map, "ai-multiplier").doubleValue(),
        number(map, "loot-multiplier").doubleValue(), number(map, "entry-fee").longValue(),
        nestedMapList(map, "legacy-aliases").stream().map(alias ->
            new RaidActionProfile.LegacyAlias(RaidMode.parse(string(alias, "mode")),
                RaidDifficulty.parse(string(alias, "difficulty")))).toList());
  }

  private static Map<String, Object> spawnMap(SpawnDefinition value) {
    Map<String, Object> result = base(value.id(), value.enabled());
    result.put("position", positionMap(value.position()));
    result.put("yaw", value.yaw());
    result.put("pitch", value.pitch());
    result.put("team", value.team());
    result.put("weight", value.weight());
    result.put("route-group", value.routeGroup());
    if (value.spawnRegion() != null) result.put("region", boundsMap(value.spawnRegion()));
    return result;
  }

  private static Map<String, Object> lootPointMap(LootPointDefinition value) {
    Map<String, Object> result = base(value.id(), value.enabled());
    result.put("position", positionMap(value.position()));
    result.put("container-type", value.containerType());
    result.put("loot-table", value.lootTable());
    result.put("tier", value.tier());
    result.put("virtual", value.virtualContainer());
    return result;
  }

  private static Map<String, Object> lootZoneMap(LootZoneDefinition value) {
    Map<String, Object> result = base(value.id(), value.enabled());
    result.put("bounds", boundsMap(value.bounds()));
    result.put("loot-table", value.lootTable());
    result.put("tier", value.tier());
    result.put("density", value.density());
    result.put("danger", value.danger());
    return result;
  }

  private static Map<String, Object> aiZoneMap(AiZoneDefinition value) {
    Map<String, Object> result = base(value.id(), value.enabled());
    result.put("bounds", boundsMap(value.bounds()));
    result.put("ai-type", value.aiType());
    result.put("minimum", value.minimum());
    result.put("maximum", value.maximum());
    result.put("respawn-seconds", value.respawnSeconds());
    result.put("difficulty", value.difficulty());
    result.put("team", value.team());
    return result;
  }

  private static Map<String, Object> extractionMap(ExtractionDefinition value) {
    Map<String, Object> result = base(value.id(), value.enabled());
    result.put("display-name", value.displayName());
    result.put("bounds", boundsMap(value.bounds()));
    result.put("type", value.extractionType().name());
    result.put("duration-seconds", value.durationSeconds());
    if (value.conditionValue() != null) result.put("condition", value.conditionValue());
    if (!value.conditions().isEmpty()) {
      result.put("conditions", value.conditions().stream()
          .map(ManualMapStore::extractionConditionMap).toList());
    }
    if (value.routeScope() != null) {
      result.put("route-global", value.routeScope().global());
      result.put("eligible-spawn-groups", value.routeScope().eligibleSpawnGroups());
    }
    return result;
  }

  private static Map<String, Object> extractionConditionMap(ExtractionRuleDefinition value) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", value.type().name());
    switch (value) {
      case ExtractionRuleDefinition.SwitchActivated rule -> {
        result.put("switch-id", rule.switchId());
        result.put("position", positionMap(rule.position()));
      }
      case ExtractionRuleDefinition.RandomOpen rule -> result.put("chance", rule.chance());
      case ExtractionRuleDefinition.NoBackpack _ -> {
      }
      case ExtractionRuleDefinition.MaxWeight rule ->
          result.put("max-weight-grams", rule.maxWeightGrams());
      case ExtractionRuleDefinition.Capacity rule -> result.put("slots", rule.slots());
    }
    return result;
  }

  private static Map<String, Object> poiMap(PoiDefinition value) {
    Map<String, Object> result = base(value.id(), value.enabled());
    result.put("display-name", value.displayName());
    result.put("bounds", boundsMap(value.bounds()));
    result.put("tier", value.tier());
    return result;
  }

  private static SpawnDefinition readSpawn(Map<?, ?> map) {
    return new SpawnDefinition(string(map, "id"), readPosition(nested(map, "position")),
        number(map, "yaw").floatValue(), number(map, "pitch").floatValue(),
        string(map, "team"), number(map, "weight").doubleValue(), bool(map, "enabled"),
        map.containsKey("region") ? readBounds(nested(map, "region")) : null,
        map.containsKey("route-group") ? string(map, "route-group") : string(map, "id"));
  }

  private static LootPointDefinition readLootPoint(Map<?, ?> map) {
    return new LootPointDefinition(string(map, "id"), readPosition(nested(map, "position")),
        string(map, "container-type"), string(map, "loot-table"), number(map, "tier").intValue(),
        bool(map, "virtual"), bool(map, "enabled"));
  }

  private static LootZoneDefinition readLootZone(Map<?, ?> map) {
    return new LootZoneDefinition(string(map, "id"), readBounds(nested(map, "bounds")),
        string(map, "loot-table"), number(map, "tier").intValue(),
        number(map, "density").doubleValue(), number(map, "danger").doubleValue(),
        bool(map, "enabled"));
  }

  private static AiZoneDefinition readAiZone(Map<?, ?> map) {
    return new AiZoneDefinition(string(map, "id"), readBounds(nested(map, "bounds")),
        string(map, "ai-type"), number(map, "minimum").intValue(),
        number(map, "maximum").intValue(), number(map, "respawn-seconds").intValue(),
        number(map, "difficulty").doubleValue(), string(map, "team"), bool(map, "enabled"));
  }

  private static ExtractionDefinition readExtraction(Map<?, ?> map) {
    return new ExtractionDefinition(string(map, "id"), string(map, "display-name"),
        readBounds(nested(map, "bounds")), ManualExtractionType.valueOf(string(map, "type")),
        number(map, "duration-seconds").intValue(), nullableString(map.get("condition")),
        bool(map, "enabled"), map.containsKey("conditions")
            ? nestedMapList(map, "conditions").stream()
                .map(ManualMapStore::readExtractionCondition).toList()
            : List.of(), routeScope(map));
  }

  private static ExtractionRouteScope routeScope(Map<?, ?> map) {
    if (!map.containsKey("route-global") && !map.containsKey("eligible-spawn-groups")) {
      return null;
    }
    boolean global = map.containsKey("route-global") && bool(map, "route-global");
    return new ExtractionRouteScope(global, map.containsKey("eligible-spawn-groups")
        ? stringList(map, "eligible-spawn-groups") : List.of());
  }

  private static ExtractionRuleDefinition readExtractionCondition(Map<?, ?> map) {
    String type = string(map, "type").toUpperCase(java.util.Locale.ROOT);
    return switch (type) {
      case "SWITCH", "LEVER", "SWITCH_ACTIVATED" ->
          new ExtractionRuleDefinition.SwitchActivated(string(map, "switch-id"),
              readPosition(nested(map, "position")));
      case "RANDOM", "RANDOM_OPEN" ->
          new ExtractionRuleDefinition.RandomOpen(number(map, "chance").doubleValue());
      case "DROP_BACKPACK", "NO_BACKPACK" -> new ExtractionRuleDefinition.NoBackpack();
      case "WEIGHT", "MAX_WEIGHT" ->
          new ExtractionRuleDefinition.MaxWeight(number(map, "max-weight-grams").longValue());
      case "SLOTS", "CAPACITY" ->
          new ExtractionRuleDefinition.Capacity(number(map, "slots").intValue());
      default -> throw new IllegalArgumentException("未知撤离条件类型: " + type);
    };
  }

  private static PoiDefinition readPoi(Map<?, ?> map) {
    return new PoiDefinition(string(map, "id"), string(map, "display-name"),
        readBounds(nested(map, "bounds")), number(map, "tier").intValue(), bool(map, "enabled"));
  }

  private static Map<String, Object> base(String id, boolean enabled) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("enabled", enabled);
    return result;
  }

  private static Map<String, Object> positionMap(BlockPosition value) {
    return Map.of("x", value.x(), "y", value.y(), "z", value.z());
  }

  private static BlockPosition readPosition(Map<?, ?> map) {
    return new BlockPosition(number(map, "x").intValue(), number(map, "y").intValue(),
        number(map, "z").intValue());
  }

  private static Map<String, Object> boundsMap(CuboidBounds value) {
    return Map.of("min-x", value.minX(), "min-y", value.minY(), "min-z", value.minZ(),
        "max-x", value.maxX(), "max-y", value.maxY(), "max-z", value.maxZ());
  }

  private static CuboidBounds readBounds(Map<?, ?> map) {
    return new CuboidBounds(number(map, "min-x").intValue(), number(map, "min-y").intValue(),
        number(map, "min-z").intValue(), number(map, "max-x").intValue(),
        number(map, "max-y").intValue(), number(map, "max-z").intValue());
  }

  private static Map<?, ?> sectionMap(YamlConfiguration yaml, String path) {
    ConfigurationSection section = yaml.getConfigurationSection(path);
    if (section == null) throw new IllegalArgumentException("缺少配置节: " + path);
    return section.getValues(false);
  }

  private static Map<?, ?> nested(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (value instanceof Map<?, ?> nested) return nested;
    if (value instanceof ConfigurationSection section) return section.getValues(false);
    throw new IllegalArgumentException("字段必须是对象: " + key);
  }

  private static List<Map<?, ?>> nestedMapList(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException("字段必须是对象列表: " + key);
    }
    List<Map<?, ?>> result = new ArrayList<>(list.size());
    for (Object element : list) {
      if (element instanceof Map<?, ?> nested) {
        result.add(nested);
      } else if (element instanceof ConfigurationSection section) {
        result.add(section.getValues(false));
      } else {
        throw new IllegalArgumentException("字段必须是对象列表: " + key);
      }
    }
    return List.copyOf(result);
  }

  private static Number number(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (value instanceof Number number) return number;
    throw new IllegalArgumentException("字段必须是数字: " + key);
  }

  private static boolean bool(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (value instanceof Boolean bool) return bool;
    throw new IllegalArgumentException("字段必须是布尔值: " + key);
  }

  private static String string(Map<?, ?> map, String key) {
    return required(nullableString(map.get(key)), key);
  }

  private static List<String> stringList(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (!(value instanceof List<?> values)) {
      throw new IllegalArgumentException("字段必须是列表: " + key);
    }
    return values.stream().map(String::valueOf).toList();
  }

  private static @Nullable String nullableString(@Nullable Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static String required(@Nullable String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少字段: " + field);
    return value;
  }

  private static void requireId(String value, String field) {
    Preconditions.checkNotNull(value, field);
    Preconditions.checkArgument(ID.matcher(value).matches(),
        field + " must match [a-z0-9_]+ but was: " + value);
  }
}
