package com.birditch.escapefromcraftov.profile;

import com.birditch.escapefromcraftov.api.economy.Currency;
import com.birditch.escapefromcraftov.api.economy.EconomyDirection;
import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.economy.TransactionType;
import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.profile.PlayerProfile;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.time.TimeSource;
import com.birditch.escapefromcraftov.core.database.Database;
import com.birditch.escapefromcraftov.core.database.entity.EconomyTransactionEntity;
import com.birditch.escapefromcraftov.core.database.entity.PlayerItemEntity;
import com.birditch.escapefromcraftov.core.database.entity.PlayerProfileEntity;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/// Creates a brand-new profile, starter balance and starter warehouse rows in
/// one transaction. The profile primary key is the durable one-time marker:
/// existing players are returned unchanged and can never claim the kit again.
public final class HibernateStarterKitService implements StarterKitService {

  public static final Money STARTER_COINS = Money.of(100_000L);
  private static final List<StarterItem> STARTER_ITEMS = List.of(
      item("iron_sword", 1),
      item("bow", 1),
      item("arrow", 16),
      item("shield", 1),
      item("iron_chestplate", 1),
      item("basic_rig", 1),
      item("patrol_backpack", 1),
      item("healing_potion", 2),
      item("golden_apple", 2));

  private final Database database;
  private final ExecutorService executor;
  private final ItemRegistry items;
  private final TimeSource timeSource;

  public HibernateStarterKitService(Database database, ExecutorService executor,
      ItemRegistry items, TimeSource timeSource) {
    this.database = Preconditions.checkNotNull(database, "database");
    this.executor = Preconditions.checkNotNull(executor, "executor");
    this.items = Preconditions.checkNotNull(items, "items");
    this.timeSource = Preconditions.checkNotNull(timeSource, "timeSource");
    STARTER_ITEMS.forEach(starter -> items.require(starter.itemId()));
  }

  @Override
  public CompletableFuture<StarterKitResult> initialize(PlayerProfile profile) {
    Preconditions.checkNotNull(profile, "profile");
    ProfileSeed seed = new ProfileSeed(profile.playerId(), profile.name(), profile.level(),
        profile.experience(), profile.createdAt());
    Instant now = timeSource.now();
    return CompletableFuture.supplyAsync(() -> initializeNow(seed, now), executor);
  }

  private StarterKitResult initializeNow(ProfileSeed seed, Instant now) {
    try {
      return database.transaction(session -> {
        PlayerProfileEntity existing = session.find(PlayerProfileEntity.class, seed.playerId());
        if (existing != null) {
          return new StarterKitResult(false, Money.of(existing.balance()));
        }
        session.persist(new PlayerProfileEntity(seed.playerId(), seed.name(),
            STARTER_COINS.units(), seed.level(), seed.experience(), seed.createdAt(), now));
        for (int index = 0; index < STARTER_ITEMS.size(); index++) {
          StarterItem starter = STARTER_ITEMS.get(index);
          UUID identity = itemIdentity(seed.playerId(), index, starter.itemId());
          session.persist(new PlayerItemEntity(identity, seed.playerId(), identity,
              starter.itemId().value(), starter.quantity(), InventoryLocation.WAREHOUSE,
              null, null, now));
        }
        UUID auditId = starterAuditId(seed.playerId());
        session.persist(new EconomyTransactionEntity(auditId, seed.playerId(),
            TransactionType.ADMIN, EconomyDirection.CREDIT, Currency.COINS,
            STARTER_COINS.units(), STARTER_COINS.units(), "新手档案初始化", now));
        session.flush();
        return new StarterKitResult(true, STARTER_COINS);
      });
    } catch (RuntimeException creationFailure) {
      // Two Lobby processes can observe an absent profile together. The profile
      // primary key makes one transaction win; after that constraint wait ends,
      // the loser recovers the committed authoritative row instead of granting
      // a second kit or kicking the player.
      PlayerProfileEntity recovered = database.transaction(session ->
          session.find(PlayerProfileEntity.class, seed.playerId()));
      if (recovered != null) {
        return new StarterKitResult(false, Money.of(recovered.balance()));
      }
      throw creationFailure;
    }
  }

  static List<StarterItem> starterItems() {
    return STARTER_ITEMS;
  }

  static UUID starterAuditId(UUID playerId) {
    return stableId("starter:audit:" + playerId);
  }

  static UUID itemIdentity(UUID playerId, int index, ItemId itemId) {
    return stableId("starter:item:" + playerId + ':' + index + ':' + itemId.value());
  }

  private static UUID stableId(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static StarterItem item(String id, int quantity) {
    return new StarterItem(ItemId.of(id), quantity);
  }

  record StarterItem(ItemId itemId, int quantity) {}

  private record ProfileSeed(UUID playerId, String name, int level,
                             long experience, Instant createdAt) {}
}
