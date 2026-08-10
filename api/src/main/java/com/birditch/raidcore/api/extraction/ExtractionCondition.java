package com.birditch.raidcore.api.extraction;

import java.util.concurrent.CompletableFuture;

/// Gate that must be satisfied before a player may begin extracting.
public interface ExtractionCondition {

    /// Human-readable description used in "Extraction blocked" failure messages.
    String describe();

    boolean isSatisfied(ExtractionContext context);

    /// Performs any durable final authorization after the in-world countdown
    /// completes. Pure conditions reuse [#isSatisfied]; paid conditions can
    /// override this with asynchronous PostgreSQL work.
    default CompletableFuture<Boolean> authorize(ExtractionContext context) {
        return CompletableFuture.completedFuture(isSatisfied(context));
    }
}
