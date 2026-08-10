package com.birditch.raidcore.game.plugin.editor;

import com.birditch.raidcore.api.economy.EconomyService;
import com.birditch.raidcore.api.economy.Money;
import com.birditch.raidcore.api.economy.TransactionType;
import com.birditch.raidcore.api.extraction.ExtractionCondition;
import com.birditch.raidcore.api.extraction.ExtractionContext;
import com.birditch.raidcore.api.extraction.ExtractionPoint;
import com.birditch.raidcore.api.extraction.ExtractionPointId;
import com.birditch.raidcore.api.extraction.ExtractionState;
import com.birditch.raidcore.api.extraction.ExtractionType;
import com.birditch.raidcore.api.game.RaidExtractionArea;
import com.birditch.raidcore.api.game.RaidAiZone;
import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.game.RaidLootPoint;
import com.birditch.raidcore.api.game.RaidPoi;
import com.birditch.raidcore.api.game.RaidSpawnLocation;
import com.birditch.raidcore.api.game.RaidSpawnArea;
import com.birditch.raidcore.api.loot.LootContainerType;
import com.birditch.raidcore.api.loot.LootTableId;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.service.ServiceRegistry;
import com.birditch.raidcore.game.RaidMapProvider;
import com.birditch.raidcore.game.RaidMapRuntime;
import com.birditch.raidcore.map.ExtractionDefinition;
import com.birditch.raidcore.map.RaidMapSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/// Compiles the enabled manual revision into one immutable raid runtime snapshot.
public final class ManualRaidMapProvider implements RaidMapProvider {

  private static final NamespacedKey ITEM_ID_KEY = Objects.requireNonNull(
      NamespacedKey.fromString("raidcore:item_id"));

  private final ManualMapRegistry maps;
  private final ServiceRegistry services;

  public ManualRaidMapProvider(ManualMapRegistry maps, ServiceRegistry services) {
    this.maps = Preconditions.checkNotNull(maps, "maps");
    this.services = Preconditions.checkNotNull(services, "services");
  }

  @Override
  public Optional<RaidMapRuntime> runtime(RaidMapId mapId) {
    return maps.snapshot(mapId.value()).map(this::compile);
  }

  private RaidMapRuntime compile(RaidMapSnapshot map) {
    List<RaidSpawnLocation> spawns = map.spawns().stream()
        .filter(value -> value.enabled())
        .map(value -> new RaidSpawnLocation(value.id(), value.position().x(), value.position().y(),
            value.position().z(), value.yaw(), value.pitch(), value.team(), value.weight(),
            value.spawnRegion() == null ? null : new RaidSpawnArea(
                value.spawnRegion().minX(), value.spawnRegion().minY(), value.spawnRegion().minZ(),
                value.spawnRegion().maxX(), value.spawnRegion().maxY(), value.spawnRegion().maxZ())))
        .toList();
    List<RaidLootPoint> lootPoints = map.lootPoints().stream()
        .filter(value -> value.enabled())
        .map(value -> new RaidLootPoint(value.id(), value.position().x(), value.position().y(),
            value.position().z(), containerType(value.containerType()),
            LootTableId.of(value.lootTable()), value.tier(), value.virtualContainer()))
        .toList();
    List<ExtractionDefinition> enabledExtractions = map.extractions().stream()
        .filter(ExtractionDefinition::enabled).toList();
    List<ExtractionPoint> points = enabledExtractions.stream().map(this::point).toList();
    List<RaidExtractionArea> areas = enabledExtractions.stream().map(value ->
        new RaidExtractionArea(pointId(value), value.displayName(), value.bounds().minX(),
            value.bounds().minY(), value.bounds().minZ(), value.bounds().maxX(),
            value.bounds().maxY(), value.bounds().maxZ())).toList();
    List<RaidAiZone> aiZones = map.aiZones().stream().filter(value -> value.enabled()).map(value ->
        new RaidAiZone(value.id(), value.aiType(), value.minimum(), value.maximum(),
            value.respawnSeconds(), value.difficulty(), value.team(), value.bounds().minX(),
            value.bounds().minY(), value.bounds().minZ(), value.bounds().maxX(),
            value.bounds().maxY(), value.bounds().maxZ())).toList();
    List<RaidPoi> pois = map.pois().stream().filter(value -> value.enabled()).map(value ->
        new RaidPoi(value.id(), value.displayName(), value.tier(), value.bounds().minX(),
            value.bounds().minY(), value.bounds().minZ(), value.bounds().maxX(),
            value.bounds().maxY(), value.bounds().maxZ())).toList();
    return new RaidMapRuntime(map.revision(), spawns, lootPoints, points, areas, aiZones, pois);
  }

  private static LootContainerType containerType(String value) {
    try {
      return LootContainerType.valueOf(value.toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      return LootContainerType.CHEST;
    }
  }

  private ExtractionPoint point(ExtractionDefinition value) {
    return new ExtractionPoint(pointId(value), value.displayName(), switch (value.extractionType()) {
      case NORMAL -> ExtractionType.NORMAL;
      case ITEM_REQUIRED -> ExtractionType.ITEM_REQUIRED;
      case PAID -> ExtractionType.PAID;
      case TIMED -> ExtractionType.TIMED;
    }, Duration.ofSeconds(value.durationSeconds()), conditions(value), ExtractionState.AVAILABLE);
  }

  private List<ExtractionCondition> conditions(ExtractionDefinition value) {
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
        Instant threshold = services.require(RaidGameService.class).find(context.gameId())
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
            Money.of(cost), TransactionType.RAID_PENALTY, "条件撤离费用")
            .thenApply(com.birditch.raidcore.common.result.Result::isSuccess);
      }
    };
  }

  private static UUID paidTransactionId(ExtractionContext context, String pointId) {
    String value = "raid-paid-extraction:" + context.gameId().value() + ":"
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
