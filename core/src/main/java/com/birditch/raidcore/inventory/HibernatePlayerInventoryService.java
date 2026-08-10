package com.birditch.raidcore.inventory;

import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.inventory.InventoryLocation;
import com.birditch.raidcore.api.inventory.PlayerInventoryService;
import com.birditch.raidcore.api.inventory.PlayerInventorySnapshot;
import com.birditch.raidcore.api.inventory.RaidInventoryOutcome;
import com.birditch.raidcore.api.inventory.RaidInventoryClosedException;
import com.birditch.raidcore.api.inventory.RaidSettlementResult;
import com.birditch.raidcore.api.inventory.StoredItem;
import com.birditch.raidcore.api.item.ItemId;
import com.birditch.raidcore.api.item.ItemInstance;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.database.Database;
import com.birditch.raidcore.core.database.entity.PlayerItemEntity;
import com.birditch.raidcore.core.database.entity.RaidInventorySettlementEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/// Hibernate implementation with atomic loadout claims and idempotent settlement.
public final class HibernatePlayerInventoryService implements PlayerInventoryService {

  private final Database database;
  private final ExecutorService executor;

  public HibernatePlayerInventoryService(Database database, ExecutorService executor) {
    this.database = Preconditions.checkNotNull(database, "database");
    this.executor = Preconditions.checkNotNull(executor, "executor");
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
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(storageId, "storageId");
    Preconditions.checkArgument(target == InventoryLocation.WAREHOUSE
        || target == InventoryLocation.LOADOUT, "target must be WAREHOUSE or LOADOUT");
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      lockPlayer(session, playerId);
      PlayerItemEntity item = session.find(PlayerItemEntity.class, storageId);
      if (item == null || !item.playerId().equals(playerId)) {
        throw new IllegalArgumentException("物品不存在或不属于该玩家: " + storageId);
      }
      if (item.location() == InventoryLocation.RAID) {
        throw new IllegalStateException("行动中的物品不能在大厅移动");
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
      item.moveTo(target, null, Instant.now());
      session.flush();
      return snapshot(playerId, session.createSelectionQuery(
          "from PlayerItemEntity where playerId = :playerId order by updatedAt, storageId",
          PlayerItemEntity.class).setParameter("playerId", playerId).getResultList());
    }), executor);
  }

  @Override
  public CompletableFuture<List<StoredItem>> claimLoadout(UUID playerId, RaidGameId gameId) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(gameId, "gameId");
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      lockPlayer(session, playerId);
      RaidInventorySettlementEntity settlement =
          session.find(RaidInventorySettlementEntity.class, settlementId(playerId, gameId));
      if (settlement != null) throw new RaidInventoryClosedException(settlement.outcome());
      List<PlayerItemEntity> alreadyClaimed = raidRows(session, playerId, gameId.value());
      if (!alreadyClaimed.isEmpty()) return alreadyClaimed.stream().map(this::toStored).toList();
      List<PlayerItemEntity> stale = session.createSelectionQuery(
          "from PlayerItemEntity where playerId = :playerId and location = :location",
          PlayerItemEntity.class).setParameter("playerId", playerId)
          .setParameter("location", InventoryLocation.RAID).getResultList();
      stale.forEach(session::remove);
      List<PlayerItemEntity> loadout = session.createSelectionQuery(
          "from PlayerItemEntity where playerId = :playerId and location = :location",
          PlayerItemEntity.class).setParameter("playerId", playerId)
          .setParameter("location", InventoryLocation.LOADOUT).getResultList();
      Instant now = Instant.now();
      loadout.forEach(item -> item.moveTo(InventoryLocation.RAID, gameId.value(), now));
      session.flush();
      return loadout.stream().map(this::toStored).toList();
    }), executor);
  }

  @Override
  public CompletableFuture<RaidSettlementResult> settleExtraction(
      UUID playerId, RaidGameId gameId, List<ItemInstance> carriedItems) {
    Preconditions.checkNotNull(carriedItems, "carriedItems");
    return settle(playerId, gameId, RaidInventoryOutcome.EXTRACTED, List.copyOf(carriedItems));
  }

  @Override
  public CompletableFuture<RaidSettlementResult> settleDeath(UUID playerId, RaidGameId gameId) {
    return settle(playerId, gameId, RaidInventoryOutcome.LOST, List.of());
  }

  @Override
  public CompletableFuture<Integer> purgeOrphanedRaidItems(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      lockPlayer(session, playerId);
      List<PlayerItemEntity> rows = session.createSelectionQuery(
          "from PlayerItemEntity where playerId = :playerId and location = :location",
          PlayerItemEntity.class).setParameter("playerId", playerId)
          .setParameter("location", InventoryLocation.RAID).getResultList();
      rows.forEach(session::remove);
      return rows.size();
    }), executor);
  }

  private CompletableFuture<RaidSettlementResult> settle(UUID playerId, RaidGameId gameId,
      RaidInventoryOutcome outcome, List<ItemInstance> carriedItems) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(gameId, "gameId");
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      lockPlayer(session, playerId);
      String settlementId = settlementId(playerId, gameId);
      RaidInventorySettlementEntity existing =
          session.find(RaidInventorySettlementEntity.class, settlementId);
      if (existing != null) return new RaidSettlementResult(existing.outcome(), false);
      raidRows(session, playerId, gameId.value()).forEach(session::remove);
      Instant now = Instant.now();
      if (outcome == RaidInventoryOutcome.EXTRACTED) {
        for (ItemInstance item : carriedItems) {
          session.persist(new PlayerItemEntity(UUID.randomUUID(), playerId,
              item.itemId().value(), item.quantity(), InventoryLocation.WAREHOUSE, null, now));
        }
      }
      session.persist(new RaidInventorySettlementEntity(
          settlementId, playerId, gameId.value(), outcome, now));
      return new RaidSettlementResult(outcome, true);
    }), executor);
  }

  private static List<PlayerItemEntity> raidRows(
      org.hibernate.Session session, UUID playerId, String raidId) {
    return session.createSelectionQuery(
        "from PlayerItemEntity where playerId = :playerId and location = :location and raidId = :raidId",
        PlayerItemEntity.class).setParameter("playerId", playerId)
        .setParameter("location", InventoryLocation.RAID).setParameter("raidId", raidId)
        .getResultList();
  }

  private PlayerInventorySnapshot snapshot(UUID playerId, List<PlayerItemEntity> rows) {
    return new PlayerInventorySnapshot(playerId, rows.stream().map(this::toStored).toList());
  }

  private StoredItem toStored(PlayerItemEntity entity) {
    RaidGameId raidId = entity.raidId() == null ? null : RaidGameId.of(entity.raidId());
    return new StoredItem(entity.storageId(), ItemId.of(entity.itemId()), entity.quantity(),
        entity.location(), raidId);
  }

  private static String settlementId(UUID playerId, RaidGameId gameId) {
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
