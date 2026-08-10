package com.birditch.raidcore.api.profile;

import com.birditch.raidcore.common.result.Result;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/// Durable idempotent player XP awards initiated on the main thread.
public interface ProgressionService {

    CompletableFuture<Result<ProgressionUpdate>> award(
            UUID transactionId, UUID playerId, long experience, String reason);
}
