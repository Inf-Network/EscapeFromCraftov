package com.birditch.escapefromcraftov.api.report;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Durable, idempotent post-raid report store. */
public interface RaidReportService {

  /** Persists the report, or returns the first report already stored for this raid/player. */
  CompletableFuture<RaidResult> record(RaidResult result);

  /** Returns newest first. Implementations reject limits outside 1..100. */
  CompletableFuture<List<RaidResult>> recent(UUID playerId, int limit);
}
