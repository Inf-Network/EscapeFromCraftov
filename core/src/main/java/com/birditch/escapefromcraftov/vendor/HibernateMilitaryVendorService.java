package com.birditch.escapefromcraftov.vendor;

import com.birditch.escapefromcraftov.api.economy.Currency;
import com.birditch.escapefromcraftov.api.economy.EconomyDirection;
import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.economy.TransactionType;
import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import com.birditch.escapefromcraftov.common.time.TimeSource;
import com.birditch.escapefromcraftov.core.database.Database;
import com.birditch.escapefromcraftov.core.database.entity.EconomyTransactionEntity;
import com.birditch.escapefromcraftov.core.database.entity.PlayerItemEntity;
import com.birditch.escapefromcraftov.core.database.entity.PlayerProfileEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/// PostgreSQL transaction boundary for the lobby military vendor.
///
/// The profile row is always locked first. That gives every player one global
/// trade lane even when multiple Lobby processes are running, and makes the
/// economy audit UUID a reliable idempotency key. Item and balance changes are
/// flushed in the same transaction, so neither side can commit alone.
public final class HibernateMilitaryVendorService implements MilitaryVendorService {

  private static final String BUY_PREFIX = "vendor:buy:";
  private static final String SELL_PREFIX = "vendor:sell:";

  private final Database database;
  private final ExecutorService executor;
  private final ItemRegistry items;
  private final TimeSource timeSource;

  public HibernateMilitaryVendorService(Database database, ExecutorService executor,
      ItemRegistry items, TimeSource timeSource) {
    this.database = Preconditions.checkNotNull(database, "database");
    this.executor = Preconditions.checkNotNull(executor, "executor");
    this.items = Preconditions.checkNotNull(items, "items");
    this.timeSource = Preconditions.checkNotNull(timeSource, "timeSource");
  }

  @Override
  public CompletableFuture<Result<VendorTrade>> buy(UUID operationId, UUID playerId,
      ItemId itemId, int quantity) {
    Preconditions.checkNotNull(operationId, "operationId");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(itemId, "itemId");
    VendorPrices.requirePurchaseQuantity(quantity);
    ItemDefinition definition = items.require(itemId);
    Preconditions.checkArgument(definition.tradable(), "item is not tradable");
    Money total = VendorPrices.buy(definition, quantity);
    String description = buyDescription(itemId, quantity);
    Instant now = timeSource.now();
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      PlayerProfileEntity profile = session.find(
          PlayerProfileEntity.class, playerId, LockModeType.PESSIMISTIC_WRITE);
      if (profile == null) return Result.fail(PROFILE_NOT_FOUND);
      EconomyTransactionEntity existing =
          session.find(EconomyTransactionEntity.class, operationId);
      if (existing != null) {
        verifyAudit(existing, playerId, TransactionType.SHOP_BUY,
            EconomyDirection.DEBIT, total, description);
        return Result.ok(trade(existing, VendorTradeType.BUY, itemId, quantity, true));
      }
      if (profile.balance() < total.units()) return Result.fail(INSUFFICIENT_FUNDS);
      long balanceAfter = profile.balance() - total.units();
      profile.updateBalance(balanceAfter, now);
      // The operation UUID is both the storage-row and physical-instance identity.
      // Replaying this request can therefore never mint a second item.
      session.persist(new PlayerItemEntity(operationId, playerId, operationId,
          itemId.value(), quantity, InventoryLocation.WAREHOUSE, null, null, now));
      session.persist(new EconomyTransactionEntity(operationId, playerId,
          TransactionType.SHOP_BUY, EconomyDirection.DEBIT, Currency.COINS,
          total.units(), balanceAfter, description, now));
      session.flush();
      return Result.ok(new VendorTrade(operationId, VendorTradeType.BUY, itemId,
          quantity, total, Money.of(balanceAfter), false));
    }), executor);
  }

  @Override
  public CompletableFuture<Result<VendorTrade>> sell(UUID operationId, UUID playerId,
      UUID storageId) {
    Preconditions.checkNotNull(operationId, "operationId");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(storageId, "storageId");
    Instant now = timeSource.now();
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      PlayerProfileEntity profile = session.find(
          PlayerProfileEntity.class, playerId, LockModeType.PESSIMISTIC_WRITE);
      if (profile == null) return Result.fail(PROFILE_NOT_FOUND);
      EconomyTransactionEntity existing =
          session.find(EconomyTransactionEntity.class, operationId);
      if (existing != null) {
        SellRequest replay = parseSellDescription(existing.description(), storageId);
        ItemDefinition definition = items.require(replay.itemId());
        Money total = VendorPrices.sell(definition, replay.quantity());
        verifyAudit(existing, playerId, TransactionType.SHOP_SELL,
            EconomyDirection.CREDIT, total, existing.description());
        return Result.ok(trade(existing, VendorTradeType.SELL,
            replay.itemId(), replay.quantity(), true));
      }
      PlayerItemEntity stored = session.find(
          PlayerItemEntity.class, storageId, LockModeType.PESSIMISTIC_WRITE);
      if (stored == null || !stored.playerId().equals(playerId)) {
        return Result.fail(ITEM_NOT_FOUND);
      }
      if (stored.location() != InventoryLocation.WAREHOUSE) {
        return Result.fail(ITEM_NOT_IN_WAREHOUSE);
      }
      ItemDefinition definition = items.find(ItemId.of(stored.itemId())).orElse(null);
      if (definition == null || !definition.sellable()) return Result.fail(ITEM_NOT_TRADABLE);
      Money total = VendorPrices.sell(definition, stored.quantity());
      long balanceAfter = Math.addExact(profile.balance(), total.units());
      String description = sellDescription(storageId, definition.id(), stored.quantity());
      profile.updateBalance(balanceAfter, now);
      session.remove(stored);
      session.persist(new EconomyTransactionEntity(operationId, playerId,
          TransactionType.SHOP_SELL, EconomyDirection.CREDIT, Currency.COINS,
          total.units(), balanceAfter, description, now));
      session.flush();
      return Result.ok(new VendorTrade(operationId, VendorTradeType.SELL, definition.id(),
          stored.quantity(), total, Money.of(balanceAfter), false));
    }), executor);
  }

  static String buyDescription(ItemId itemId, int quantity) {
    return BUY_PREFIX + itemId.value() + ':' + quantity;
  }

  static String sellDescription(UUID storageId, ItemId itemId, int quantity) {
    return SELL_PREFIX + storageId + ':' + itemId.value() + ':' + quantity;
  }

  static SellRequest parseSellDescription(String description, UUID expectedStorageId) {
    Preconditions.checkNotNull(description, "description");
    String prefix = SELL_PREFIX + expectedStorageId + ':';
    if (!description.startsWith(prefix)) {
      throw new IllegalStateException("Vendor operation id was reused for another request");
    }
    String[] parts = description.substring(prefix.length()).split(":", -1);
    if (parts.length != 2) {
      throw new IllegalStateException("Invalid vendor sell audit payload");
    }
    try {
      int quantity = Integer.parseInt(parts[1]);
      Preconditions.checkArgument(quantity >= 1, "quantity must be positive");
      return new SellRequest(ItemId.of(parts[0]), quantity);
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException("Invalid vendor sell audit payload", error);
    }
  }

  private static void verifyAudit(EconomyTransactionEntity audit, UUID playerId,
      TransactionType type, EconomyDirection direction, Money total, String description) {
    boolean same = audit.playerId().equals(playerId)
        && audit.type() == type
        && audit.direction() == direction
        && audit.currency() == Currency.COINS
        && audit.amount() == total.units()
        && audit.description().equals(description);
    if (!same) {
      throw new IllegalStateException(
          "Vendor operation id was reused for another request: " + audit.transactionId());
    }
  }

  private static VendorTrade trade(EconomyTransactionEntity audit, VendorTradeType type,
      ItemId itemId, int quantity, boolean replayed) {
    return new VendorTrade(audit.transactionId(), type, itemId, quantity,
        Money.of(audit.amount()), Money.of(audit.balanceAfter()), replayed);
  }

  record SellRequest(ItemId itemId, int quantity) {}
}
