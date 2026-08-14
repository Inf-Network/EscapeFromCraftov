package com.birditch.escapefromcraftov.market;

import com.birditch.escapefromcraftov.api.economy.Currency;
import com.birditch.escapefromcraftov.api.economy.EconomyDirection;
import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.economy.TransactionType;
import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import com.birditch.escapefromcraftov.common.time.TimeSource;
import com.birditch.escapefromcraftov.core.database.Database;
import com.birditch.escapefromcraftov.core.database.entity.EconomyTransactionEntity;
import com.birditch.escapefromcraftov.core.database.entity.MarketListingEntity;
import com.birditch.escapefromcraftov.core.database.entity.MarketListingStatus;
import com.birditch.escapefromcraftov.core.database.entity.MarketOperationEntity;
import com.birditch.escapefromcraftov.core.database.entity.MarketOperationType;
import com.birditch.escapefromcraftov.core.database.entity.PlayerItemEntity;
import com.birditch.escapefromcraftov.core.database.entity.PlayerProfileEntity;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.hibernate.Session;

/// PostgreSQL market transaction boundary.
///
/// Mutation lock order is operation advisory lock, listing row, profile rows in
/// UUID order, then the market-reserved item. A listing row exclusively owns its
/// MARKET item, so a buyer can never race a cancellation or second buyer.
public final class HibernatePlayerMarketService implements PlayerMarketService {

  public static final long MAX_PRICE = 1_000_000_000L;
  private static final int EXPIRY_BATCH = 100;

  private final Database database;
  private final ExecutorService executor;
  private final TimeSource timeSource;

  public HibernatePlayerMarketService(
      Database database, ExecutorService executor, TimeSource timeSource) {
    this.database = Preconditions.checkNotNull(database, "database");
    this.executor = Preconditions.checkNotNull(executor, "executor");
    this.timeSource = Preconditions.checkNotNull(timeSource, "timeSource");
  }

  @Override
  public CompletableFuture<Result<MarketMutation>> listItem(UUID operationId, UUID listingId,
      UUID sellerId, UUID storageId, Money price, Duration lifetime) {
    requireIds(operationId, listingId, sellerId, storageId);
    requirePrice(price);
    Preconditions.checkNotNull(lifetime, "lifetime");
    Preconditions.checkArgument(!lifetime.isNegative() && !lifetime.isZero()
        && lifetime.compareTo(Duration.ofDays(7)) <= 0,
        "lifetime must be between 1 ms and 7 days");
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      lockOperation(session, operationId);
      MarketOperationEntity replay = session.find(MarketOperationEntity.class, operationId);
      if (replay != null) {
        verifyOperation(replay, MarketOperationType.LIST, sellerId, listingId);
        MarketListingEntity existing = requireListing(session, listingId);
        verifyListReplay(existing, operationId, sellerId, storageId, price, lifetime);
        return Result.ok(new MarketMutation(toListing(existing), true));
      }
      if (session.find(MarketListingEntity.class, listingId) != null) {
        throw new IllegalStateException("Market listing id is already in use: " + listingId);
      }
      PlayerItemEntity item = session.find(
          PlayerItemEntity.class, storageId, LockModeType.PESSIMISTIC_WRITE);
      if (item == null || !item.playerId().equals(sellerId)) return Result.fail(ITEM_NOT_FOUND);
      if (item.location() != InventoryLocation.WAREHOUSE) {
        return Result.fail(ITEM_NOT_IN_WAREHOUSE);
      }
      Instant now = timeSource.now();
      Instant expiresAt = now.plus(lifetime);
      item.moveTo(InventoryLocation.MARKET, null, now);
      MarketListingEntity listing = new MarketListingEntity(listingId, operationId, sellerId,
          storageId, item.instanceIdOrStorageId(), item.itemId(), item.quantity(), item.itemData(),
          price.units(), now, expiresAt);
      session.persist(listing);
      session.persist(new MarketOperationEntity(operationId, MarketOperationType.LIST,
          sellerId, listingId, now));
      session.flush();
      return Result.ok(new MarketMutation(toListing(listing), false));
    }), executor);
  }

  @Override
  public CompletableFuture<Result<MarketTrade>> buy(
      UUID operationId, UUID buyerId, UUID listingId) {
    requireIds(operationId, buyerId, listingId);
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      lockOperation(session, operationId);
      MarketOperationEntity replay = session.find(MarketOperationEntity.class, operationId);
      if (replay != null) {
        verifyOperation(replay, MarketOperationType.BUY, buyerId, listingId);
        return Result.ok(replayedTrade(session, operationId, buyerId, listingId));
      }
      MarketListingEntity listing = session.find(
          MarketListingEntity.class, listingId, LockModeType.PESSIMISTIC_WRITE);
      if (listing == null) return Result.fail(LISTING_NOT_FOUND);
      if (listing.status() != MarketListingStatus.ACTIVE) return Result.fail(LISTING_UNAVAILABLE);
      Instant now = timeSource.now();
      if (!listing.expiresAt().isAfter(now)) {
        expire(session, listing, now);
        return Result.fail(LISTING_UNAVAILABLE);
      }
      if (listing.sellerId().equals(buyerId)) return Result.fail(CANNOT_BUY_OWN);

      Profiles profiles = lockProfiles(session, buyerId, listing.sellerId());
      if (profiles.buyer() == null || profiles.seller() == null) return Result.fail(PROFILE_NOT_FOUND);
      if (profiles.buyer().balance() < listing.price()) return Result.fail(INSUFFICIENT_FUNDS);
      PlayerItemEntity item = requireReservedItem(session, listing);
      long buyerAfter = profiles.buyer().balance() - listing.price();
      long sellerAfter = Math.addExact(profiles.seller().balance(), listing.price());
      profiles.buyer().updateBalance(buyerAfter, now);
      profiles.seller().updateBalance(sellerAfter, now);
      item.transferTo(buyerId, InventoryLocation.WAREHOUSE, now);
      listing.markSold(operationId, buyerId, now);

      String buyDescription = buyDescription(listingId);
      session.persist(new EconomyTransactionEntity(operationId, buyerId,
          TransactionType.MARKET_BUY, EconomyDirection.DEBIT, Currency.COINS,
          listing.price(), buyerAfter, buyDescription, now));
      session.persist(new EconomyTransactionEntity(sellerAuditId(operationId), listing.sellerId(),
          TransactionType.MARKET_SELL, EconomyDirection.CREDIT, Currency.COINS,
          listing.price(), sellerAfter, sellDescription(listingId, operationId), now));
      session.persist(new MarketOperationEntity(operationId, MarketOperationType.BUY,
          buyerId, listingId, now));
      session.flush();
      return Result.ok(new MarketTrade(operationId, toListing(listing), buyerId,
          Money.of(buyerAfter), Money.of(sellerAfter), false));
    }), executor);
  }

  @Override
  public CompletableFuture<Result<MarketMutation>> cancel(
      UUID operationId, UUID sellerId, UUID listingId) {
    requireIds(operationId, sellerId, listingId);
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      lockOperation(session, operationId);
      MarketOperationEntity replay = session.find(MarketOperationEntity.class, operationId);
      if (replay != null) {
        verifyOperation(replay, MarketOperationType.CANCEL, sellerId, listingId);
        return Result.ok(new MarketMutation(toListing(requireListing(session, listingId)), true));
      }
      MarketListingEntity listing = session.find(
          MarketListingEntity.class, listingId, LockModeType.PESSIMISTIC_WRITE);
      if (listing == null) return Result.fail(LISTING_NOT_FOUND);
      if (!listing.sellerId().equals(sellerId)) return Result.fail(NOT_LISTING_OWNER);
      if (listing.status() != MarketListingStatus.ACTIVE) return Result.fail(LISTING_UNAVAILABLE);
      Instant now = timeSource.now();
      if (!listing.expiresAt().isAfter(now)) {
        expire(session, listing, now);
        return Result.fail(LISTING_UNAVAILABLE);
      }
      returnReservedItem(session, listing, now);
      listing.markCancelled(operationId, now);
      session.persist(new MarketOperationEntity(operationId, MarketOperationType.CANCEL,
          sellerId, listingId, now));
      session.flush();
      return Result.ok(new MarketMutation(toListing(listing), false));
    }), executor);
  }

  @Override
  public CompletableFuture<MarketPage> browse(int requestedPage, int pageSize) {
    requirePage(requestedPage, pageSize);
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      Instant now = timeSource.now();
      expireDue(session, now);
      long total = session.createSelectionQuery(
          "select count(*) from MarketListingEntity where status = :status and expiresAt > :now",
          Long.class).setParameter("status", MarketListingStatus.ACTIVE)
          .setParameter("now", now).getSingleResult();
      int totalPages = totalPages(total, pageSize);
      int page = Math.min(requestedPage, totalPages - 1);
      List<MarketListing> listings = session.createSelectionQuery(
          "from MarketListingEntity where status = :status and expiresAt > :now "
              + "order by createdAt desc, listingId",
          MarketListingEntity.class).setParameter("status", MarketListingStatus.ACTIVE)
          .setParameter("now", now).setFirstResult(page * pageSize).setMaxResults(pageSize)
          .getResultList().stream().map(HibernatePlayerMarketService::toListing).toList();
      return new MarketPage(listings, page, totalPages, total);
    }), executor);
  }

  @Override
  public CompletableFuture<MarketPage> mine(UUID sellerId, int requestedPage, int pageSize) {
    Preconditions.checkNotNull(sellerId, "sellerId");
    requirePage(requestedPage, pageSize);
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      Instant now = timeSource.now();
      expireDue(session, now);
      long total = session.createSelectionQuery(
          "select count(*) from MarketListingEntity where sellerId = :sellerId "
              + "and status = :status and expiresAt > :now", Long.class)
          .setParameter("sellerId", sellerId).setParameter("status", MarketListingStatus.ACTIVE)
          .setParameter("now", now).getSingleResult();
      int totalPages = totalPages(total, pageSize);
      int page = Math.min(requestedPage, totalPages - 1);
      List<MarketListing> listings = session.createSelectionQuery(
          "from MarketListingEntity where sellerId = :sellerId and status = :status "
              + "and expiresAt > :now order by createdAt desc, listingId",
          MarketListingEntity.class).setParameter("sellerId", sellerId)
          .setParameter("status", MarketListingStatus.ACTIVE).setParameter("now", now)
          .setFirstResult(page * pageSize).setMaxResults(pageSize).getResultList().stream()
          .map(HibernatePlayerMarketService::toListing).toList();
      return new MarketPage(listings, page, totalPages, total);
    }), executor);
  }

  @Override
  public CompletableFuture<Integer> expireDue() {
    return CompletableFuture.supplyAsync(
        () -> database.transaction(session -> expireDue(session, timeSource.now())), executor);
  }

  private static int expireDue(Session session, Instant now) {
    List<MarketListingEntity> expired = session.createSelectionQuery(
        "from MarketListingEntity where status = :status and expiresAt <= :now "
            + "order by listingId", MarketListingEntity.class)
        .setParameter("status", MarketListingStatus.ACTIVE).setParameter("now", now)
        .setMaxResults(EXPIRY_BATCH).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
    for (MarketListingEntity listing : expired) expire(session, listing, now);
    return expired.size();
  }

  private static void expire(Session session, MarketListingEntity listing, Instant now) {
    returnReservedItem(session, listing, now);
    listing.markExpired(now);
  }

  private static void returnReservedItem(
      Session session, MarketListingEntity listing, Instant now) {
    requireReservedItem(session, listing).moveTo(InventoryLocation.WAREHOUSE, null, now);
  }

  private static PlayerItemEntity requireReservedItem(
      Session session, MarketListingEntity listing) {
    PlayerItemEntity item = session.find(PlayerItemEntity.class, listing.storageId());
    boolean exact = item != null
        && item.playerId().equals(listing.sellerId())
        && item.location() == InventoryLocation.MARKET
        && item.instanceIdOrStorageId().equals(listing.instanceId())
        && item.itemId().equals(listing.itemId())
        && item.quantity() == listing.quantity()
        && java.util.Objects.equals(item.itemData(), listing.itemData());
    if (!exact) {
      throw new IllegalStateException(
          "Market-reserved item no longer matches listing: " + listing.listingId());
    }
    return item;
  }

  private static Profiles lockProfiles(Session session, UUID buyerId, UUID sellerId) {
    List<UUID> ids = profileLockOrder(buyerId, sellerId);
    PlayerProfileEntity first = session.find(
        PlayerProfileEntity.class, ids.get(0), LockModeType.PESSIMISTIC_WRITE);
    PlayerProfileEntity second = session.find(
        PlayerProfileEntity.class, ids.get(1), LockModeType.PESSIMISTIC_WRITE);
    return ids.get(0).equals(buyerId) ? new Profiles(first, second) : new Profiles(second, first);
  }

  private static MarketTrade replayedTrade(
      Session session, UUID operationId, UUID buyerId, UUID listingId) {
    MarketListingEntity listing = requireListing(session, listingId);
    if (listing.status() != MarketListingStatus.SOLD
        || !operationId.equals(listing.closeOperationId())
        || !buyerId.equals(listing.buyerId())) {
      throw new IllegalStateException("Market buy operation does not match listing result");
    }
    EconomyTransactionEntity buyerAudit = session.find(EconomyTransactionEntity.class, operationId);
    verifyAudit(buyerAudit, buyerId, TransactionType.MARKET_BUY, EconomyDirection.DEBIT,
        listing.price(), buyDescription(listingId));
    EconomyTransactionEntity sellerAudit = session.find(
        EconomyTransactionEntity.class, sellerAuditId(operationId));
    verifyAudit(sellerAudit, listing.sellerId(), TransactionType.MARKET_SELL,
        EconomyDirection.CREDIT, listing.price(), sellDescription(listingId, operationId));
    return new MarketTrade(operationId, toListing(listing), buyerId,
        Money.of(buyerAudit.balanceAfter()), Money.of(sellerAudit.balanceAfter()), true);
  }

  private static void verifyAudit(EconomyTransactionEntity audit, UUID playerId,
      TransactionType type, EconomyDirection direction, long price, String description) {
    if (audit == null || !audit.playerId().equals(playerId) || audit.type() != type
        || audit.direction() != direction || audit.currency() != Currency.COINS
        || audit.amount() != price || !audit.description().equals(description)) {
      throw new IllegalStateException("Market economy audit does not match operation result");
    }
  }

  private static void verifyOperation(MarketOperationEntity operation,
      MarketOperationType type, UUID playerId, UUID listingId) {
    if (operation.type() != type || !operation.playerId().equals(playerId)
        || !operation.listingId().equals(listingId)) {
      throw new IllegalStateException(
          "Market operation id was reused with different request data: " + operation.operationId());
    }
  }

  private static void verifyListReplay(MarketListingEntity listing, UUID operationId,
      UUID sellerId, UUID storageId, Money price, Duration lifetime) {
    if (!listing.createOperationId().equals(operationId)
        || !listing.sellerId().equals(sellerId) || !listing.storageId().equals(storageId)
        || listing.price() != price.units()
        || !Duration.between(listing.createdAt(), listing.expiresAt()).equals(lifetime)) {
      throw new IllegalStateException("Market list replay does not match original request");
    }
  }

  private static MarketListingEntity requireListing(Session session, UUID listingId) {
    MarketListingEntity listing = session.find(MarketListingEntity.class, listingId);
    if (listing == null) throw new IllegalStateException("Market operation has no listing result");
    return listing;
  }

  private static MarketListing toListing(MarketListingEntity entity) {
    return new MarketListing(entity.listingId(), entity.sellerId(), entity.storageId(),
        entity.instanceId(), ItemId.of(entity.itemId()), entity.quantity(), entity.itemData(),
        Money.of(entity.price()), entity.status(), entity.buyerId(),
        entity.createdAt(), entity.expiresAt());
  }

  static UUID sellerAuditId(UUID operationId) {
    return UUID.nameUUIDFromBytes(
        ("market:seller:" + operationId).getBytes(StandardCharsets.UTF_8));
  }

  static String buyDescription(UUID listingId) { return "market:buy:" + listingId; }

  static String sellDescription(UUID listingId, UUID operationId) {
    return "market:sell:" + listingId + ':' + operationId;
  }

  static List<UUID> profileLockOrder(UUID first, UUID second) {
    return List.of(first, second).stream().sorted(Comparator.comparing(UUID::toString)).toList();
  }

  private static void lockOperation(Session session, UUID operationId) {
    long key = operationId.getMostSignificantBits() ^ operationId.getLeastSignificantBits();
    session.createNativeQuery("select pg_advisory_xact_lock(:lockKey)", Object.class)
        .setParameter("lockKey", key).getSingleResult();
  }

  private static int totalPages(long total, int pageSize) {
    return Math.max(1, Math.toIntExact((total + pageSize - 1L) / pageSize));
  }

  private static void requirePage(int requestedPage, int pageSize) {
    Preconditions.checkArgument(requestedPage >= 0, "requestedPage must not be negative");
    Preconditions.checkArgument(pageSize >= 1 && pageSize <= 100,
        "pageSize must be between 1 and 100");
  }

  private static void requirePrice(Money price) {
    Preconditions.checkNotNull(price, "price");
    Preconditions.checkArgument(price.units() >= 1 && price.units() <= MAX_PRICE,
        "price must be between 1 and " + MAX_PRICE);
  }

  private static void requireIds(UUID... ids) {
    for (UUID id : ids) Preconditions.checkNotNull(id, "id");
  }

  private record Profiles(PlayerProfileEntity buyer, PlayerProfileEntity seller) {}
}
