package com.birditch.escapefromcraftov.api.extraction;

import java.util.concurrent.CompletableFuture;
import java.util.Optional;

/// Gate that must be satisfied before a player may begin extracting.
public interface ExtractionCondition {

    /// Human-readable description used in "Extraction blocked" failure messages.
    String describe();

    boolean isSatisfied(ExtractionContext context);

    /**
     * Atomically acquires this condition for one extraction attempt.
     *
     * <p>The compatibility implementation preserves the original stateless
     * behaviour: check {@link #isSatisfied(ExtractionContext)} now and run
     * {@link #authorize(ExtractionContext)} after the countdown. Conditions
     * that own finite capacity may override this method to return a reserving
     * permit.</p>
     */
    default Optional<ExtractionPermit> acquire(ExtractionContext context) {
        if (!isSatisfied(context)) {
            return Optional.empty();
        }
        return Optional.of(new ExtractionPermit() {
            @Override
            public CompletableFuture<Boolean> authorize() {
                return ExtractionCondition.this.authorize(context);
            }
        });
    }

    /// Performs any durable final authorization after the in-world countdown
    /// completes. Pure conditions reuse [#isSatisfied]; paid conditions can
    /// override this with asynchronous PostgreSQL work.
    default CompletableFuture<Boolean> authorize(ExtractionContext context) {
        return CompletableFuture.completedFuture(isSatisfied(context));
    }
}
