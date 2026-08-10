package com.birditch.raidcore.api.inventory;

import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.item.ItemInstance;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/// PostgreSQL-backed player item ownership and idempotent raid settlement.
public interface PlayerInventoryService {

  int MAX_LOADOUT_STACKS = 9;

  CompletableFuture<PlayerInventorySnapshot> load(UUID playerId);

  /// Moves one stack between WAREHOUSE and LOADOUT.
  CompletableFuture<PlayerInventorySnapshot> move(
      UUID playerId, UUID storageId, InventoryLocation target);

  /// Atomically moves the current LOADOUT into one RAID. Repeated claims for
  /// the same raid return the already claimed rows.
  CompletableFuture<List<StoredItem>> claimLoadout(UUID playerId, RaidGameId gameId);

  /// Replaces claimed RAID rows with the stacks actually carried at extraction.
  /// An idempotent retry returns the already committed outcome.
  CompletableFuture<RaidSettlementResult> settleExtraction(
      UUID playerId, RaidGameId gameId, List<ItemInstance> carriedItems);

  /// Deletes every claimed item for this raid and returns the durable outcome.
  CompletableFuture<RaidSettlementResult> settleDeath(UUID playerId, RaidGameId gameId);

  /// Applies death-loss to stale RAID rows left by an interrupted server.
  CompletableFuture<Integer> purgeOrphanedRaidItems(UUID playerId);
}
