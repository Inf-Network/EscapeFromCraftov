package com.birditch.raidcore.profile;

import com.birditch.raidcore.api.profile.ProgressionUpdate;
import com.birditch.raidcore.common.result.Result;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/// Asynchronous persistence boundary for player XP awards.
public interface ProgressionRepository {

  CompletableFuture<Result<ProgressionUpdate>> award(UUID transactionId, UUID playerId,
      long experience, String reason, Instant timestamp);
}
