package com.birditch.escapefromcraftov.profile;

import com.birditch.escapefromcraftov.api.economy.Currency;
import com.birditch.escapefromcraftov.api.economy.EconomyDirection;
import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.economy.TransactionType;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidAdmissionRejection;
import com.birditch.escapefromcraftov.api.game.RaidAdmissionResult;
import com.birditch.escapefromcraftov.api.game.RaidAdmissionService;
import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.time.TimeSource;
import com.birditch.escapefromcraftov.core.database.Database;
import com.birditch.escapefromcraftov.core.database.entity.CraftovInventorySettlementEntity;
import com.birditch.escapefromcraftov.core.database.entity.EconomyTransactionEntity;
import com.birditch.escapefromcraftov.core.database.entity.PlayerItemEntity;
import com.birditch.escapefromcraftov.core.database.entity.PlayerProfileEntity;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;

/** PostgreSQL transaction boundary for action-tier entry gates and loadout claims. */
public final class HibernateRaidAdmissionService implements RaidAdmissionService {

  private static final String AUDIT_PREFIX = "craftov:entry:v2:";

  private final Database database;
  private final ExecutorService executor;
  private final ItemRegistry items;
  private final TimeSource timeSource;

  public HibernateRaidAdmissionService(Database database, ExecutorService executor,
      ItemRegistry items, TimeSource timeSource) {
    this.database = Preconditions.checkNotNull(database, "database");
    this.executor = Preconditions.checkNotNull(executor, "executor");
    this.items = Preconditions.checkNotNull(items, "items");
    this.timeSource = Preconditions.checkNotNull(timeSource, "timeSource");
  }

  @Override
  public CompletableFuture<RaidAdmissionResult> admit(
      UUID playerId, CraftovGameId gameId, RaidActionProfile actionProfile) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(actionProfile, "actionProfile");
    return CompletableFuture.supplyAsync(() -> database.transaction(session ->
        admit(session, playerId, gameId, actionProfile)), executor);
  }

  private RaidAdmissionResult admit(Session session, UUID playerId, CraftovGameId gameId,
      RaidActionProfile actionProfile) {
    // This is the same PostgreSQL lock namespace used by inventory mutations.
    // It also covers the theoretically missing-profile case where no row exists to lock.
    lockPlayer(session, playerId);
    PlayerProfileEntity profile = session.find(
        PlayerProfileEntity.class, playerId, LockModeType.PESSIMISTIC_WRITE);
    List<PlayerItemEntity> loadout = lockRows(session, playerId, InventoryLocation.LOADOUT, null);
    List<PlayerItemEntity> allCraftov = lockRows(
        session, playerId, InventoryLocation.CRAFTOV, null);
    if (profile == null) {
      return rejected(RaidAdmissionRejection.PROFILE_NOT_FOUND, "玩家档案不存在");
    }
    String invalidProfile = invalidActionProfile(actionProfile);
    if (invalidProfile != null) {
      return rejected(RaidAdmissionRejection.INVALID_ACTION_PROFILE, invalidProfile);
    }

    UUID operationId = operationId(playerId, gameId);
    String signature = actionProfileSignature(playerId, gameId, actionProfile);
    EconomyTransactionEntity audit = session.find(EconomyTransactionEntity.class, operationId);
    RaidAdmissionResult.Rejected activeConflict = activeAdmissionConflict(
        session, playerId, gameId, operationId);
    if (activeConflict != null) return activeConflict;
    CraftovInventorySettlementEntity settlement = session.find(
        CraftovInventorySettlementEntity.class, settlementId(playerId, gameId),
        LockModeType.PESSIMISTIC_WRITE);
    List<PlayerItemEntity> claimed = allCraftov.stream()
        .filter(item -> gameId.value().equals(item.craftovId()))
        .toList();
    boolean hasAnotherActiveCraftov = allCraftov.stream()
        .anyMatch(item -> !gameId.value().equals(item.craftovId()));

    // Never reinterpret or delete another raid's durable carried state. A
    // prior implementation copied legacy cleanup semantics here, which let a
    // second game admission erase the first game's items and charge again.
    if (hasAnotherActiveCraftov) {
      return rejected(RaidAdmissionRejection.ACTIVE_CRAFTOV_EXISTS,
          "玩家仍有另一场行动的物资未结算");
    }

    if (audit != null) {
      return replay(audit, settlement, claimed, playerId, actionProfile, signature);
    }
    if (settlement != null) {
      return rejected(RaidAdmissionRejection.CRAFTOV_CLOSED, "该对局物资已经结算");
    }
    if (!claimed.isEmpty()) {
      return rejected(RaidAdmissionRejection.ADMISSION_STATE_CONFLICT,
          "该对局已有物资，但缺少行动入场审计");
    }

    LoadoutValuation valuation = loadoutValue(items, loadout);
    if (!valuation.valid()) return valuation.rejectionResult();
    long value = valuation.value();
    if (value < actionProfile.minimumLoadoutValue()) {
      return rejected(RaidAdmissionRejection.MINIMUM_LOADOUT_NOT_MET,
          "战备价值不足：当前 " + value + "，需要 " + actionProfile.minimumLoadoutValue());
    }
    if (profile.balance() < actionProfile.entryFee()) {
      return rejected(RaidAdmissionRejection.INSUFFICIENT_FUNDS,
          "余额不足：当前 " + profile.balance() + "，入场费 " + actionProfile.entryFee());
    }

    Instant now = timeSource.now();
    long balanceAfter = profile.balance() - actionProfile.entryFee();
    profile.updateBalance(balanceAfter, now);
    loadout.forEach(item -> item.moveTo(InventoryLocation.CRAFTOV, gameId.value(), now));
    session.persist(new EconomyTransactionEntity(operationId, playerId,
        TransactionType.CRAFTOV_ENTRY_FEE, EconomyDirection.DEBIT, Currency.COINS,
        actionProfile.entryFee(), balanceAfter,
        auditDescription(gameId, signature, value, loadoutSignature(loadout)), now));
    session.flush();
    return new RaidAdmissionResult.Admitted(
        loadout.stream().map(HibernateRaidAdmissionService::toStored).toList(),
        Money.of(value), Money.of(balanceAfter), false);
  }

  private RaidAdmissionResult replay(EconomyTransactionEntity audit,
      @Nullable CraftovInventorySettlementEntity settlement, List<PlayerItemEntity> claimed,
      UUID playerId, RaidActionProfile actionProfile, String expectedSignature) {
    if (audit.playerId().equals(playerId)
        && audit.type() == TransactionType.CRAFTOV_ENTRY_FEE
        && audit.direction() == EconomyDirection.DEBIT
        && audit.currency() == Currency.COINS
        && audit.amount() == actionProfile.entryFee()
        && audit.balanceAfter() >= 0) {
      AuditPayload payload = parseAuditDescription(audit.description());
      if (payload == null) {
        return rejected(RaidAdmissionRejection.ADMISSION_STATE_CONFLICT,
            "行动入场审计内容无效");
      }
      if (!payload.profileSignature().equals(expectedSignature)) {
        return rejected(RaidAdmissionRejection.ACTION_PROFILE_MISMATCH,
            "该对局已使用另一份行动档位配置入场");
      }
      if (settlement != null) {
        return rejected(RaidAdmissionRejection.CRAFTOV_CLOSED, "该对局物资已经结算");
      }
      if (!loadoutSignature(claimed).equals(payload.loadoutSignature())) {
        return rejected(RaidAdmissionRejection.ADMISSION_STATE_CONFLICT,
            "已领取的战备与行动入场审计不一致");
      }
      return new RaidAdmissionResult.Admitted(
          claimed.stream().map(HibernateRaidAdmissionService::toStored).toList(),
          Money.of(payload.loadoutValue()), Money.of(audit.balanceAfter()), true);
    }
    if (audit.type() == TransactionType.CRAFTOV_ENTRY_FEE
        && audit.playerId().equals(playerId)) {
      return rejected(RaidAdmissionRejection.ACTION_PROFILE_MISMATCH,
          "该对局的行动档位或入场费与首次请求不一致");
    }
    return rejected(RaidAdmissionRejection.ADMISSION_STATE_CONFLICT,
        "行动入场审计 ID 已被其他业务占用");
  }

  static LoadoutValuation loadoutValue(ItemRegistry items, List<PlayerItemEntity> rows) {
    Preconditions.checkNotNull(items, "items");
    Preconditions.checkNotNull(rows, "rows");
    long total = 0;
    for (PlayerItemEntity row : rows) {
      if (row.quantity() < 1) {
        return LoadoutValuation.rejected(RaidAdmissionRejection.INVALID_LOADOUT_DATA,
            "战备物品数量无效: " + row.storageId());
      }
      ItemId itemId;
      try {
        itemId = ItemId.of(row.itemId());
      } catch (RuntimeException invalidId) {
        return LoadoutValuation.rejected(RaidAdmissionRejection.INVALID_LOADOUT_DATA,
            "战备物品 ID 无效: " + row.itemId());
      }
      ItemDefinition definition = items.find(itemId).orElse(null);
      if (definition == null) {
        return LoadoutValuation.rejected(RaidAdmissionRejection.UNKNOWN_LOADOUT_ITEM,
            "战备包含未注册物品: " + itemId.value());
      }
      try {
        total = Math.addExact(total,
            Math.multiplyExact(definition.basePrice().units(), row.quantity()));
      } catch (ArithmeticException overflow) {
        return LoadoutValuation.rejected(RaidAdmissionRejection.LOADOUT_VALUE_OVERFLOW,
            "战备价值超出可计算范围");
      }
    }
    return LoadoutValuation.accepted(total);
  }

  static UUID operationId(UUID playerId, CraftovGameId gameId) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(gameId, "gameId");
    return stableId("craftov:entry:operation:" + playerId + ':' + gameId.value());
  }

  static String actionProfileSignature(
      UUID playerId, CraftovGameId gameId, RaidActionProfile profile) {
    String canonical = "craftov:entry:profile:" + playerId + ':' + gameId.value() + ':'
        + profile.id().value() + ':' + profile.minimumLoadoutValue() + ':'
        + Double.toHexString(profile.aiMultiplier()) + ':'
        + Double.toHexString(profile.lootMultiplier()) + ':' + profile.entryFee();
    return stableId(canonical).toString();
  }

  static String auditDescription(CraftovGameId gameId,
      String profileSignature, long loadoutValue, String loadoutSignature) {
    return AUDIT_PREFIX + gameId.value() + ':' + profileSignature + ':'
        + loadoutValue + ':' + loadoutSignature;
  }

  static @Nullable String invalidActionProfile(RaidActionProfile profile) {
    if (profile.id() == null || profile.displayName() == null
        || profile.displayName().isBlank() || profile.minimumLoadoutValue() < 0
        || !Double.isFinite(profile.aiMultiplier()) || profile.aiMultiplier() <= 0
        || profile.aiMultiplier() > RaidActionProfile.MAX_AI_MULTIPLIER
        || !Double.isFinite(profile.lootMultiplier()) || profile.lootMultiplier() <= 0
        || profile.lootMultiplier() > RaidActionProfile.MAX_LOOT_MULTIPLIER
        || profile.entryFee() < 0 || profile.legacyAliases() == null
        || profile.legacyAliases().stream().anyMatch(alias -> alias == null
            || alias.mode() == null || alias.difficulty() == null)) {
      return "行动档位配置无效";
    }
    return null;
  }

  static @Nullable AuditPayload parseAuditDescription(String description) {
    if (description == null || !description.startsWith(AUDIT_PREFIX)) return null;
    String[] parts = description.substring(AUDIT_PREFIX.length()).split(":", -1);
    if (parts.length != 4) return null;
    try {
      CraftovGameId gameId = CraftovGameId.of(parts[0]);
      UUID.fromString(parts[1]);
      UUID.fromString(parts[3]);
      long value = Long.parseLong(parts[2]);
      if (value < 0) return null;
      return new AuditPayload(gameId, parts[1], value, parts[3]);
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private static RaidAdmissionResult.@Nullable Rejected activeAdmissionConflict(
      Session session, UUID playerId, CraftovGameId requestedGameId, UUID requestedOperationId) {
    List<EconomyTransactionEntity> audits = session.createSelectionQuery(
        "from EconomyTransactionEntity where playerId = :playerId and type = :type "
            + "order by createdAt, transactionId",
        EconomyTransactionEntity.class).setParameter("playerId", playerId)
        .setParameter("type", TransactionType.CRAFTOV_ENTRY_FEE).getResultList();
    java.util.LinkedHashSet<String> priorSettlementIds = new java.util.LinkedHashSet<>();
    for (EconomyTransactionEntity prior : audits) {
      AuditPayload payload = parseAuditDescription(prior.description());
      if (payload == null) {
        return rejected(RaidAdmissionRejection.ADMISSION_STATE_CONFLICT,
            "历史行动入场审计无法验证");
      }
      if (!prior.transactionId().equals(operationId(playerId, payload.gameId()))) {
        return rejected(RaidAdmissionRejection.ADMISSION_STATE_CONFLICT,
            "历史行动入场审计标识无法验证");
      }
      if (prior.transactionId().equals(requestedOperationId)) {
        if (!payload.gameId().equals(requestedGameId)) {
          return rejected(RaidAdmissionRejection.ADMISSION_STATE_CONFLICT,
              "行动入场审计与对局不匹配");
        }
        continue;
      }
      priorSettlementIds.add(settlementId(playerId, payload.gameId()));
    }
    if (priorSettlementIds.isEmpty()) return null;
    List<String> closed = session.createSelectionQuery(
        "select settlementId from CraftovInventorySettlementEntity "
            + "where settlementId in :settlementIds",
        String.class).setParameterList("settlementIds", priorSettlementIds).getResultList();
    if (closed.size() != priorSettlementIds.size()) {
      return rejected(RaidAdmissionRejection.ACTIVE_CRAFTOV_EXISTS,
          "玩家已有另一场尚未结算的行动");
    }
    return null;
  }

  static String loadoutSignature(List<PlayerItemEntity> rows) {
    StringBuilder canonical = new StringBuilder("craftov:entry:loadout:");
    rows.stream().sorted(java.util.Comparator.comparing(PlayerItemEntity::storageId))
        .forEach(row -> {
          appendField(canonical, row.storageId().toString());
          appendField(canonical, row.instanceIdOrStorageId().toString());
          appendField(canonical, row.itemId());
          appendField(canonical, Integer.toString(row.quantity()));
          appendField(canonical, row.loadoutSlot() == null ? "" : row.loadoutSlot().name());
          appendField(canonical, row.itemData() == null ? "" : row.itemData());
        });
    return stableId(canonical.toString()).toString();
  }

  private static void appendField(StringBuilder target, String value) {
    target.append(value.length()).append(':').append(value);
  }

  private static List<PlayerItemEntity> lockRows(Session session, UUID playerId,
      InventoryLocation location, @Nullable String craftovId) {
    String craftovFilter = craftovId == null ? "" : " and craftovId = :craftovId";
    var query = session.createSelectionQuery(
        "from PlayerItemEntity where playerId = :playerId and location = :location"
            + craftovFilter + " order by storageId",
        PlayerItemEntity.class).setParameter("playerId", playerId)
        .setParameter("location", location);
    if (craftovId != null) query.setParameter("craftovId", craftovId);
    return query.setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
  }

  private static void lockPlayer(Session session, UUID playerId) {
    long lockKey = playerId.getMostSignificantBits() ^ playerId.getLeastSignificantBits();
    session.createNativeQuery("select pg_advisory_xact_lock(:lockKey)", Object.class)
        .setParameter("lockKey", lockKey).getSingleResult();
  }

  private static StoredItem toStored(PlayerItemEntity entity) {
    CraftovGameId craftovId = entity.craftovId() == null
        ? null : CraftovGameId.of(entity.craftovId());
    return new StoredItem(entity.storageId(), entity.instanceIdOrStorageId(),
        ItemId.of(entity.itemId()), entity.quantity(), entity.location(), craftovId,
        entity.itemData(), entity.loadoutSlot());
  }

  private static String settlementId(UUID playerId, CraftovGameId gameId) {
    return gameId.value() + ":" + playerId;
  }

  private static UUID stableId(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static RaidAdmissionResult.Rejected rejected(
      RaidAdmissionRejection reason, String detail) {
    return new RaidAdmissionResult.Rejected(reason, detail);
  }

  record AuditPayload(CraftovGameId gameId, String profileSignature,
                      long loadoutValue, String loadoutSignature) {}

  record LoadoutValuation(long value, @Nullable RaidAdmissionRejection rejection,
      @Nullable String detail) {

    static LoadoutValuation accepted(long value) {
      return new LoadoutValuation(value, null, null);
    }

    static LoadoutValuation rejected(RaidAdmissionRejection rejection, String detail) {
      return new LoadoutValuation(0, rejection, detail);
    }

    boolean valid() {
      return rejection == null;
    }

    RaidAdmissionResult.Rejected rejectionResult() {
      if (rejection == null || detail == null) {
        throw new IllegalStateException("Accepted valuation has no rejection result");
      }
      return HibernateRaidAdmissionService.rejected(rejection, detail);
    }
  }
}
