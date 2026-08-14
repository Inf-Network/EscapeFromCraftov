package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/// PostgreSQL-backed player item ownership and idempotent craftov settlement.
public interface PlayerInventoryService {

  int MAX_LOADOUT_STACKS = 9;

  CompletableFuture<PlayerInventorySnapshot> load(UUID playerId);

  /// Moves one stack between WAREHOUSE and LOADOUT.
  CompletableFuture<PlayerInventorySnapshot> move(
      UUID playerId, UUID storageId, InventoryLocation target);

  /**
   * Moves one stack and, when targeting LOADOUT, assigns a server-validated
   * named position. Implementations predating named positions remain compatible
   * through the default null-slot path.
   */
  default CompletableFuture<PlayerInventorySnapshot> move(
      UUID playerId, UUID storageId, InventoryLocation target,
      @Nullable LoadoutSlot loadoutSlot) {
    if (loadoutSlot != null) {
      return CompletableFuture.failedFuture(
          new UnsupportedOperationException("named loadout slots are not supported"));
    }
    return move(playerId, storageId, target);
  }

  /// Atomically moves the current LOADOUT into one CRAFTOV. Repeated claims for
  /// the same craftov return the already claimed rows.
  CompletableFuture<List<StoredItem>> claimLoadout(UUID playerId, CraftovGameId gameId);

  /// Replaces claimed CRAFTOV rows with the stacks actually carried at extraction.
  /// An idempotent retry returns the already committed outcome.
  CompletableFuture<CraftovSettlementResult> settleExtraction(
      UUID playerId, CraftovGameId gameId, List<ItemInstance> carriedItems);

  /// Deletes every claimed item for this craftov and returns the durable outcome.
  CompletableFuture<CraftovSettlementResult> settleDeath(UUID playerId, CraftovGameId gameId);

  /// Applies a failed-craftov settlement while returning the explicitly protected
  /// physical stacks (for example a secure container) to WAREHOUSE. All other
  /// claimed CRAFTOV rows are lost. The protected snapshot is immutable across
  /// retries and is subject to the same global instance-identity checks as a
  /// successful extraction.
  CompletableFuture<CraftovSettlementResult> settleLoss(
      UUID playerId, CraftovGameId gameId, List<ItemInstance> protectedItems);

  /// Applies death-loss to stale CRAFTOV rows left by an interrupted server.
  CompletableFuture<Integer> purgeOrphanedCraftovItems(UUID playerId);
}
