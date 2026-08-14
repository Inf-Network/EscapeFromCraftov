package com.birditch.escapefromcraftov.inventory;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.inventory.LoadoutSlot;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventoryService;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventorySnapshot;
import com.birditch.escapefromcraftov.api.inventory.CraftovInventoryOutcome;
import com.birditch.escapefromcraftov.api.inventory.CraftovInventoryClosedException;
import com.birditch.escapefromcraftov.api.inventory.CraftovSettlementResult;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.core.database.Database;
import com.birditch.escapefromcraftov.core.database.entity.PlayerItemEntity;
import com.birditch.escapefromcraftov.core.database.entity.CraftovInventorySettlementEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.jspecify.annotations.Nullable;

/// Hibernate implementation with atomic loadout claims and idempotent settlement.
public final class HibernatePlayerInventoryService implements PlayerInventoryService {

  private final Database database;
  private final ExecutorService executor;
  private final @Nullable ItemRegistry itemRegistry;

  public HibernatePlayerInventoryService(Database database, ExecutorService executor) {
    this(database, executor, null);
  }

  public HibernatePlayerInventoryService(Database database, ExecutorService executor,
      @Nullable ItemRegistry itemRegistry) {
    this.database = Preconditions.checkNotNull(database, "database");
    this.executor = Preconditions.checkNotNull(executor, "executor");
    this.itemRegistry = itemRegistry;
  }

  @Override
  public CompletableFuture<PlayerInventorySnapshot> load(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return CompletableFuture.supplyAsync(() -> database.transaction(session ->
        snapshot(playerId, session.createSelectionQuery(
            "from PlayerItemEntity where playerId = :playerId order by updatedAt, storageId",
            PlayerItemEntity.class).setParameter("playerId", playerId).getResultList())), executor);
  }

  @Override
  public CompletableFuture<PlayerInventorySnapshot> move(
      UUID playerId, UUID storageId, InventoryLocation target) {
    return move(playerId, storageId, target, null);
  }

  @Override
  public CompletableFuture<PlayerInventorySnapshot> move(
      UUID playerId, UUID storageId, InventoryLocation target,
      @Nullable LoadoutSlot loadoutSlot) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(storageId, "storageId");
    Preconditions.checkArgument(target == InventoryLocation.WAREHOUSE
        || target == InventoryLocation.LOADOUT, "target must be WAREHOUSE or LOADOUT");
    Preconditions.checkArgument(loadoutSlot == null || target == InventoryLocation.LOADOUT,
        "loadoutSlot is only valid when targeting LOADOUT");
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      lockPlayer(session, playerId);
      PlayerItemEntity item = session.find(PlayerItemEntity.class, storageId);
      if (item == null || !item.playerId().equals(playerId)) {
        throw new IllegalArgumentException("物品不存在或不属于该玩家: " + storageId);
      }
      if (item.location() != InventoryLocation.WAREHOUSE
          && item.location() != InventoryLocation.LOADOUT) {
        throw new IllegalStateException("行动中或已上架的物品不能在大厅移动");
      }
      if (target == InventoryLocation.LOADOUT && item.location() != target) {
        long current = session.createSelectionQuery(
            "select count(*) from PlayerItemEntity where playerId = :playerId and location = :location",
            Long.class).setParameter("playerId", playerId)
            .setParameter("location", InventoryLocation.LOADOUT).getSingleResult();
        if (current >= MAX_LOADOUT_STACKS) {
          throw new IllegalStateException("出战栏已满，最多 " + MAX_LOADOUT_STACKS + " 个物品栈");
        }
      }
      if (loadoutSlot != null) {
        ItemRegistry registry = itemRegistry;
        if (registry == null) {
          throw new IllegalStateException("当前物资服务尚未启用具名配装");
        }
        var definition = registry.require(ItemId.of(item.itemId()));
        if (!loadoutSlot.accepts(definition.category())) {
          throw new IllegalArgumentException("该物品不能装备到 " + loadoutSlot);
        }
        if (item.quantity() > definition.stackSize()
            || (loadoutSlot != LoadoutSlot.QUICK_USE_1
                && loadoutSlot != LoadoutSlot.QUICK_USE_2 && item.quantity() != 1)) {
          throw new IllegalArgumentException("该物品堆栈数量不能装备到 " + loadoutSlot);
        }
        long occupied = session.createSelectionQuery(
            "select count(*) from PlayerItemEntity where playerId = :playerId "
                + "and location = :location and loadoutSlot = :loadoutSlot "
                + "and storageId <> :storageId",
            Long.class).setParameter("playerId", playerId)
            .setParameter("location", InventoryLocation.LOADOUT)
            .setParameter("loadoutSlot", loadoutSlot)
            .setParameter("storageId", storageId).getSingleResult();
        if (occupied != 0) throw new IllegalStateException("配装位置已被占用: " + loadoutSlot);
        item.moveToLoadout(loadoutSlot, Instant.now());
      } else {
        item.moveTo(target, null, Instant.now());
      }
      session.flush();
      return snapshot(playerId, session.createSelectionQuery(
          "from PlayerItemEntity where playerId = :playerId order by updatedAt, storageId",
          PlayerItemEntity.class).setParameter("playerId", playerId).getResultList());
    }), executor);
  }

  @Override
  public CompletableFuture<List<StoredItem>> claimLoadout(UUID playerId, CraftovGameId gameId) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(gameId, "gameId");
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      lockPlayer(session, playerId);
      CraftovInventorySettlementEntity settlement =
          session.find(CraftovInventorySettlementEntity.class, settlementId(playerId, gameId));
      if (settlement != null) throw new CraftovInventoryClosedException(settlement.outcome());
      List<PlayerItemEntity> alreadyClaimed = craftovRows(session, playerId, gameId.value());
      if (!alreadyClaimed.isEmpty()) return alreadyClaimed.stream().map(this::toStored).toList();
      List<PlayerItemEntity> stale = session.createSelectionQuery(
          "from PlayerItemEntity where playerId = :playerId and location = :location",
          PlayerItemEntity.class).setParameter("playerId", playerId)
          .setParameter("location", InventoryLocation.CRAFTOV).getResultList();
      stale.forEach(session::remove);
      List<PlayerItemEntity> loadout = session.createSelectionQuery(
          "from PlayerItemEntity where playerId = :playerId and location = :location",
          PlayerItemEntity.class).setParameter("playerId", playerId)
          .setParameter("location", InventoryLocation.LOADOUT).getResultList();
      Instant now = Instant.now();
      loadout.forEach(item -> item.moveTo(InventoryLocation.CRAFTOV, gameId.value(), now));
      session.flush();
      return loadout.stream().map(this::toStored).toList();
    }), executor);
  }

  @Override
  public CompletableFuture<CraftovSettlementResult> settleExtraction(
      UUID playerId, CraftovGameId gameId, List<ItemInstance> carriedItems) {
    Preconditions.checkNotNull(carriedItems, "carriedItems");
    return settle(playerId, gameId, CraftovInventoryOutcome.EXTRACTED, List.copyOf(carriedItems));
  }

  @Override
  public CompletableFuture<CraftovSettlementResult> settleDeath(UUID playerId, CraftovGameId gameId) {
    return settle(playerId, gameId, CraftovInventoryOutcome.LOST, List.of());
  }

  @Override
  public CompletableFuture<CraftovSettlementResult> settleLoss(
      UUID playerId, CraftovGameId gameId, List<ItemInstance> protectedItems) {
    Preconditions.checkNotNull(protectedItems, "protectedItems");
    return settle(playerId, gameId, CraftovInventoryOutcome.LOST, List.copyOf(protectedItems));
  }

  @Override
  public CompletableFuture<Integer> purgeOrphanedCraftovItems(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      lockPlayer(session, playerId);
      List<PlayerItemEntity> rows = session.createSelectionQuery(
          "from PlayerItemEntity where playerId = :playerId and location = :location",
          PlayerItemEntity.class).setParameter("playerId", playerId)
          .setParameter("location", InventoryLocation.CRAFTOV).getResultList();
      rows.forEach(session::remove);
      return rows.size();
    }), executor);
  }

  private CompletableFuture<CraftovSettlementResult> settle(UUID playerId, CraftovGameId gameId,
      CraftovInventoryOutcome outcome, List<ItemInstance> carriedItems) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(gameId, "gameId");
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      lockPlayer(session, playerId);
      String settlementId = settlementId(playerId, gameId);
      CraftovInventorySettlementEntity existing =
          session.find(CraftovInventorySettlementEntity.class, settlementId);
      if (existing != null) return new CraftovSettlementResult(existing.outcome(), false);
      List<ItemInstance> orderedCarriedItems = carriedItems.stream()
          .sorted(Comparator.comparing(ItemInstance::instanceId)).toList();
      Map<UUID, ItemInstance> carriedInstances = carriedInstances(orderedCarriedItems);
      List<PlayerItemEntity> lockedRows = lockSettlementRows(
          session, playerId, gameId.value(), carriedInstances.keySet());
      settlementRowsToRemove(playerId, gameId.value(), carriedInstances, lockedRows)
          .forEach(session::remove);
      // The same physical instances are re-homed into fresh warehouse rows below.
      // Flush deletes first so the global unique instance index never observes the
      // old CRAFTOV row and its replacement simultaneously in Hibernate's action queue.
      session.flush();
      Instant now = Instant.now();
      // `orderedCarriedItems` means everything that survives this transaction.
      // On extraction that is the complete carried snapshot; on a loss it is
      // only the protected secure-container snapshot.
      for (ItemInstance item : orderedCarriedItems) {
        session.persist(new PlayerItemEntity(UUID.randomUUID(), playerId, item.instanceId(),
            item.itemId().value(), item.quantity(), InventoryLocation.WAREHOUSE, null,
            item.opaquePayload(), now));
      }
      session.persist(new CraftovInventorySettlementEntity(
          settlementId, playerId, gameId.value(), outcome, now));
      return new CraftovSettlementResult(outcome, true);
    }), executor);
  }

  private static List<PlayerItemEntity> lockSettlementRows(org.hibernate.Session session,
      UUID playerId, String craftovId, Set<UUID> carriedInstanceIds) {
    String carriedClause = carriedInstanceIds.isEmpty() ? "" : " or item.instanceId in :ids"
        + " or (item.instanceId is null and item.storageId in :ids)";
    // Every transaction locks its complete overlap set in storage-id order. This prevents
    // A-carries-B / B-carries-A extraction from acquiring the same two rows in reverse order.
    var query = session.createSelectionQuery(
        "from PlayerItemEntity item where "
            + "(item.playerId = :playerId and item.location = :location "
            + "and item.craftovId = :craftovId)" + carriedClause
            + " order by item.storageId",
        PlayerItemEntity.class).setParameter("playerId", playerId)
        .setParameter("location", InventoryLocation.CRAFTOV)
        .setParameter("craftovId", craftovId);
    if (!carriedInstanceIds.isEmpty()) query.setParameterList("ids", carriedInstanceIds);
    return query.setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
  }

  static Map<UUID, ItemInstance> carriedInstances(List<ItemInstance> carriedItems) {
    Map<UUID, ItemInstance> result = new LinkedHashMap<>();
    for (ItemInstance item : carriedItems) {
      if (result.putIfAbsent(item.instanceId(), item) != null) {
        throw new IllegalArgumentException("撤离快照包含重复物品实例: " + item.instanceId());
      }
    }
    return Map.copyOf(result);
  }

  static List<PlayerItemEntity> settlementRowsToRemove(UUID playerId, String craftovId,
      Map<UUID, ItemInstance> carriedInstances, List<PlayerItemEntity> lockedRows) {
    Map<UUID, PlayerItemEntity> existingInstances = new LinkedHashMap<>();
    Set<PlayerItemEntity> removals = new LinkedHashSet<>();
    for (PlayerItemEntity row : lockedRows) {
      UUID instanceId = row.instanceIdOrStorageId();
      ItemInstance carried = carriedInstances.get(instanceId);
      boolean sameCraftov = row.location() == InventoryLocation.CRAFTOV
          && craftovId.equals(row.craftovId());
      if (carried != null) {
        PlayerItemEntity duplicate = existingInstances.putIfAbsent(instanceId, row);
        if (duplicate != null && duplicate != row) {
          throw new IllegalStateException("数据库存在重复物品实例: " + instanceId);
        }
        if (!sameCraftov) {
          throw new IllegalStateException("拒绝转移已在安全仓库、出战栏或其他行动中的物品实例: "
              + instanceId);
        }
        if (!row.itemId().equals(carried.itemId().value())) {
          throw new IllegalStateException("拒绝改变既有物品实例的注册定义: " + instanceId);
        }
        // Same-game CRAFTOV ownership is provisional. Removing the source row here
        // atomically transfers a dropped/death-looted physical item to the extractor.
        removals.add(row);
      }
      if (row.playerId().equals(playerId) && sameCraftov) removals.add(row);
    }
    return List.copyOf(removals);
  }

  private static List<PlayerItemEntity> craftovRows(
      org.hibernate.Session session, UUID playerId, String craftovId) {
    return session.createSelectionQuery(
        "from PlayerItemEntity where playerId = :playerId and location = :location and craftovId = :craftovId",
        PlayerItemEntity.class).setParameter("playerId", playerId)
        .setParameter("location", InventoryLocation.CRAFTOV).setParameter("craftovId", craftovId)
        .getResultList();
  }

  private PlayerInventorySnapshot snapshot(UUID playerId, List<PlayerItemEntity> rows) {
    return new PlayerInventorySnapshot(playerId, rows.stream().map(this::toStored).toList());
  }

  private StoredItem toStored(PlayerItemEntity entity) {
    CraftovGameId craftovId = entity.craftovId() == null ? null : CraftovGameId.of(entity.craftovId());
    return new StoredItem(entity.storageId(), entity.instanceIdOrStorageId(),
        ItemId.of(entity.itemId()), entity.quantity(), entity.location(), craftovId,
        entity.itemData(), entity.loadoutSlot());
  }

  private static String settlementId(UUID playerId, CraftovGameId gameId) {
    return gameId.value() + ":" + playerId;
  }

  private static void lockPlayer(org.hibernate.Session session, UUID playerId) {
    // PostgreSQL transaction-scoped lock: it also works for a first-time
    // player whose profile row has not been inserted yet. Hash collisions only
    // serialize two unrelated players; they cannot weaken ownership safety.
    long lockKey = playerId.getMostSignificantBits() ^ playerId.getLeastSignificantBits();
    session.createNativeQuery("select pg_advisory_xact_lock(:lockKey)", Object.class)
        .setParameter("lockKey", lockKey).getSingleResult();
  }
}
