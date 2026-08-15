package com.birditch.escapefromcraftov.game.plugin.editor;

import com.birditch.escapefromcraftov.api.economy.EconomyService;
import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.economy.TransactionType;
import com.birditch.escapefromcraftov.api.extraction.ExtractionCondition;
import com.birditch.escapefromcraftov.api.extraction.ExtractionContext;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.extraction.ExtractionState;
import com.birditch.escapefromcraftov.api.extraction.ExtractionType;
import com.birditch.escapefromcraftov.api.game.CraftovExtractionArea;
import com.birditch.escapefromcraftov.api.game.CraftovExtractionRouteScope;
import com.birditch.escapefromcraftov.api.game.CraftovAiZone;
import com.birditch.escapefromcraftov.api.game.CraftovAiWaypoint;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.CraftovLootPoint;
import com.birditch.escapefromcraftov.api.game.CraftovLootZone;
import com.birditch.escapefromcraftov.api.game.CraftovMapEvent;
import com.birditch.escapefromcraftov.api.game.CraftovMapEventType;
import com.birditch.escapefromcraftov.api.game.CraftovPoi;
import com.birditch.escapefromcraftov.api.game.CraftovSpawnLocation;
import com.birditch.escapefromcraftov.api.game.CraftovSpawnArea;
import com.birditch.escapefromcraftov.api.loot.LootContainerType;
import com.birditch.escapefromcraftov.api.loot.LootTableId;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.core.service.ServiceRegistry;
import com.birditch.escapefromcraftov.game.CraftovMapProvider;
import com.birditch.escapefromcraftov.game.CraftovMapRuntime;
import com.birditch.escapefromcraftov.game.plugin.extraction.BukkitCarriedInventoryLookup;
import com.birditch.escapefromcraftov.game.plugin.extraction.EventCompletionLookup;
import com.birditch.escapefromcraftov.game.plugin.extraction.ExtractionConditionCompiler;
import com.birditch.escapefromcraftov.game.plugin.secure.SecureContainerController;
import com.birditch.escapefromcraftov.map.ExtractionDefinition;
import com.birditch.escapefromcraftov.map.CraftovMapSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/// Compiles the enabled manual revision into one immutable craftov runtime snapshot.
public final class ManualCraftovMapProvider implements CraftovMapProvider {

  private static final NamespacedKey ITEM_ID_KEY = Objects.requireNonNull(
      NamespacedKey.fromString("escapefromcraftov:item_id"));

  private final ManualMapRegistry maps;
  private final ServiceRegistry services;
  private final ExtractionConditionCompiler conditionCompiler;

  public ManualCraftovMapProvider(ManualMapRegistry maps, ServiceRegistry services) {
    this(maps, services, defaultCompiler(services));
  }

  ManualCraftovMapProvider(ManualMapRegistry maps, ServiceRegistry services,
      ExtractionConditionCompiler conditionCompiler) {
    this.maps = Preconditions.checkNotNull(maps, "maps");
    this.services = Preconditions.checkNotNull(services, "services");
    this.conditionCompiler = Preconditions.checkNotNull(conditionCompiler, "conditionCompiler");
  }

  private static ExtractionConditionCompiler defaultCompiler(ServiceRegistry services) {
    Preconditions.checkNotNull(services, "services");
    // The provider is constructed before ItemModule initializes. Resolve the
    // registry lazily when a live condition inspects a player, not at bootstrap.
    EventCompletionLookup events = services.find(EventCompletionLookup.class)
        .orElse(EventCompletionLookup.DENY_ALL);
    return new ExtractionConditionCompiler(new Random(), playerId -> {
      ItemRegistry items = services.require(ItemRegistry.class);
      return new BukkitCarriedInventoryLookup(new CraftovItemStacks(items), items,
          id -> services.find(SecureContainerController.class)
              .map(secure -> secure.contents(id)).orElse(List.of())).find(playerId);
    }, events);
  }

  @Override
  public Optional<CraftovMapRuntime> runtime(CraftovMapId mapId) {
    return maps.snapshot(mapId.value()).map(this::compile);
  }

  private CraftovMapRuntime compile(CraftovMapSnapshot map) {
    List<CraftovSpawnLocation> spawns = map.spawns().stream()
        .filter(value -> value.enabled())
        .map(value -> new CraftovSpawnLocation(value.id(), value.position().x(), value.position().y(),
            value.position().z(), value.yaw(), value.pitch(), value.team(), value.weight(),
            value.spawnRegion() == null ? null : new CraftovSpawnArea(
                value.spawnRegion().minX(), value.spawnRegion().minY(), value.spawnRegion().minZ(),
                value.spawnRegion().maxX(), value.spawnRegion().maxY(), value.spawnRegion().maxZ()),
            value.routeGroup()))
        .toList();
    List<CraftovLootPoint> lootPoints = map.lootPoints().stream()
        .filter(value -> value.enabled())
        .map(value -> new CraftovLootPoint(value.id(), value.position().x(), value.position().y(),
            value.position().z(), containerType(value.containerType()),
            LootTableId.of(value.lootTable()), value.tier(), value.virtualContainer()))
        .toList();
    List<CraftovLootZone> lootZones = map.lootZones().stream()
        .filter(value -> value.enabled())
        .map(value -> new CraftovLootZone(value.id(), LootTableId.of(value.lootTable()),
            value.tier(), value.density(), value.danger(), value.bounds().minX(),
            value.bounds().minY(), value.bounds().minZ(), value.bounds().maxX(),
            value.bounds().maxY(), value.bounds().maxZ()))
        .toList();
    List<ExtractionDefinition> enabledExtractions = map.extractions().stream()
        .filter(ExtractionDefinition::enabled).toList();
    Map<String, String> eventDisplayNames = map.events().stream()
        .filter(value -> value.enabled())
        .collect(java.util.stream.Collectors.toUnmodifiableMap(
            value -> value.id(), value -> value.displayName()));
    List<ExtractionPoint> points = enabledExtractions.stream()
        .map(value -> point(value, eventDisplayNames)).toList();
    List<CraftovExtractionArea> areas = enabledExtractions.stream().map(value ->
        new CraftovExtractionArea(pointId(value), value.displayName(), value.bounds().minX(),
            value.bounds().minY(), value.bounds().minZ(), value.bounds().maxX(),
            value.bounds().maxY(), value.bounds().maxZ(), value.routeScope() == null ? null
                : new CraftovExtractionRouteScope(value.routeScope().global(),
                    new java.util.LinkedHashSet<>(
                        value.routeScope().eligibleSpawnGroups())))).toList();
    List<CraftovAiZone> aiZones = map.aiZones().stream().filter(value -> value.enabled()).map(value ->
        new CraftovAiZone(value.id(), value.aiType(), value.minimum(), value.maximum(),
            value.respawnSeconds(), value.difficulty(), value.team(), value.bounds().minX(),
            value.bounds().minY(), value.bounds().minZ(), value.bounds().maxX(),
            value.bounds().maxY(), value.bounds().maxZ(), value.patrolWaypoints().stream()
                .map(point -> new CraftovAiWaypoint(point.x(), point.y(), point.z()))
                .toList())).toList();
    List<CraftovPoi> pois = map.pois().stream().filter(value -> value.enabled()).map(value ->
        new CraftovPoi(value.id(), value.displayName(), value.tier(), value.bounds().minX(),
            value.bounds().minY(), value.bounds().minZ(), value.bounds().maxX(),
            value.bounds().maxY(), value.bounds().maxZ())).toList();
    List<CraftovMapEvent> mapEvents = map.events().stream().filter(value -> value.enabled())
        .map(value -> new CraftovMapEvent(value.id(), value.displayName(),
            CraftovMapEventType.valueOf(value.type().name()), value.aiZoneId(),
            value.cacheLootPointId(), value.startDelaySeconds(), value.healthMultiplier(),
            value.damageMultiplier(), value.protectionSeconds(), true))
        .toList();
    return new CraftovMapRuntime(map.revision(), spawns, lootPoints, lootZones, points, areas,
        aiZones, pois, map.actionTiers(), mapEvents);
  }

  private static LootContainerType containerType(String value) {
    try {
      return LootContainerType.valueOf(value.toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      return LootContainerType.CHEST;
    }
  }

  private ExtractionPoint point(
      ExtractionDefinition value, Map<String, String> eventDisplayNames) {
    ExtractionType legacyType = switch (value.extractionType()) {
      case NORMAL -> ExtractionType.NORMAL;
      case ITEM_REQUIRED -> ExtractionType.ITEM_REQUIRED;
      case PAID -> ExtractionType.PAID;
      case TIMED -> ExtractionType.TIMED;
    };
    ExtractionType runtimeType = value.conditions().isEmpty() ? legacyType
        : ExtractionType.CONDITIONAL;
    List<ExtractionCondition> conditions = conditionCompiler.compile(value,
        legacyConditions(value), eventDisplayNames);
    return new ExtractionPoint(pointId(value), value.displayName(), runtimeType,
        Duration.ofSeconds(value.durationSeconds()), conditions, ExtractionState.AVAILABLE);
  }

  private List<ExtractionCondition> legacyConditions(ExtractionDefinition value) {
    return switch (value.extractionType()) {
      case NORMAL -> List.of();
      case ITEM_REQUIRED -> List.of(itemCondition(value.conditionValue()));
      case PAID -> List.of(paidCondition(value.id(), value.conditionValue()));
      case TIMED -> List.of(timedCondition(value.conditionValue()));
    };
  }

  private ExtractionCondition timedCondition(String rawSeconds) {
    long seconds = Long.parseLong(rawSeconds);
    return new ExtractionCondition() {
      @Override public String describe() { return "行动开始 " + seconds + " 秒后开放"; }

      @Override
      public boolean isSatisfied(ExtractionContext context) {
        Instant threshold = services.require(CraftovGameService.class).find(context.gameId())
            .flatMap(game -> game.startedAt()).map(started -> started.plusSeconds(seconds))
            .orElse(Instant.MAX);
        return !Instant.now().isBefore(threshold);
      }
    };
  }

  private ExtractionCondition paidCondition(String pointId, String rawCost) {
    long cost = Long.parseLong(rawCost);
    return new ExtractionCondition() {
      @Override public String describe() { return "需要支付 " + Money.of(cost).format() + " 金币"; }

      @Override
      public boolean isSatisfied(ExtractionContext context) {
        return services.require(EconomyService.class).balance(context.playerId())
            .filter(balance -> balance.atLeast(Money.of(cost))).isPresent();
      }

      @Override
      public java.util.concurrent.CompletableFuture<Boolean> authorize(
          ExtractionContext context) {
        UUID transactionId = paidTransactionId(context, pointId);
        return services.require(EconomyService.class).withdraw(transactionId, context.playerId(),
            Money.of(cost), TransactionType.CRAFTOV_PENALTY, "条件撤离费用")
            .thenApply(com.birditch.escapefromcraftov.common.result.Result::isSuccess);
      }
    };
  }

  private static UUID paidTransactionId(ExtractionContext context, String pointId) {
    String value = "craftov-paid-extraction:" + context.gameId().value() + ":"
        + context.playerId() + ":" + pointId;
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static ExtractionCondition itemCondition(String itemId) {
    return new ExtractionCondition() {
      @Override public String describe() { return "需要物品 " + itemId; }

      @Override
      public boolean isSatisfied(ExtractionContext context) {
        Player player = Bukkit.getPlayer(context.playerId());
        if (player == null) return false;
        for (ItemStack item : player.getInventory().getContents()) {
          if (item == null || !item.hasItemMeta()) continue;
          String stored = item.getItemMeta().getPersistentDataContainer()
              .get(ITEM_ID_KEY, PersistentDataType.STRING);
          if (itemId.equals(stored)) return true;
        }
        return false;
      }
    };
  }

  public static ExtractionPointId pointId(ExtractionDefinition value) {
    return ExtractionPointId.of(value.id());
  }
}
