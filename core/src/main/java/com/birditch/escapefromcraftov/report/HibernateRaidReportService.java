package com.birditch.escapefromcraftov.report;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.report.RaidReportService;
import com.birditch.escapefromcraftov.api.report.RaidResult;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.core.database.Database;
import com.birditch.escapefromcraftov.core.database.entity.RaidResultEntity;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/** PostgreSQL-backed battle reports with a deterministic per-raid idempotency key. */
public final class HibernateRaidReportService implements RaidReportService {

  private final Database database;
  private final ExecutorService executor;

  public HibernateRaidReportService(Database database, ExecutorService executor) {
    this.database = Preconditions.checkNotNull(database, "database");
    this.executor = Preconditions.checkNotNull(executor, "executor");
  }

  @Override
  public CompletableFuture<RaidResult> record(RaidResult result) {
    Preconditions.checkNotNull(result, "result");
    return CompletableFuture.supplyAsync(() -> database.transaction(session -> {
      RaidResultEntity existing = session.find(RaidResultEntity.class, result.resultId());
      if (existing != null) {
        // First terminal path wins. Returning the durable row also makes a retry
        // after process restart safe when its wall-clock endedAt differs.
        return fromEntity(existing);
      }
      session.persist(toEntity(result));
      session.flush();
      return result;
    }), executor);
  }

  @Override
  public CompletableFuture<List<RaidResult>> recent(UUID playerId, int limit) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkArgument(limit >= 1 && limit <= 100, "limit must be between 1 and 100");
    return CompletableFuture.supplyAsync(() -> database.transaction(session ->
        session.createQuery("from RaidResultEntity where playerId = :playerId "
                + "order by endedAt desc, resultId desc", RaidResultEntity.class)
            .setParameter("playerId", playerId)
            .setMaxResults(limit)
            .getResultList().stream().map(HibernateRaidReportService::fromEntity).toList()),
        executor);
  }

  private static RaidResultEntity toEntity(RaidResult result) {
    return new RaidResultEntity(result.resultId(), result.gameId().value(), result.playerId(),
        result.mapId().value(), result.mode(), result.difficulty(), result.outcome(),
        result.startedAt(), result.endedAt(), result.kills(), result.pveKills(),
        result.extractedItemCount(),
        result.extractedValue(), result.experience(), result.earnings());
  }

  static RaidResult fromEntity(RaidResultEntity entity) {
    return new RaidResult(entity.resultId(), CraftovGameId.of(entity.gameId()), entity.playerId(),
        CraftovMapId.of(entity.mapId()), entity.mode(), entity.difficulty(), entity.outcome(),
        entity.startedAt(), entity.endedAt(), entity.kills(), entity.pveKills(),
        entity.extractedItemCount(),
        entity.extractedValue(), entity.experience(), entity.earnings());
  }
}
