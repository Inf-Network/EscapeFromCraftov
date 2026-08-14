package com.birditch.escapefromcraftov.api.extraction;

import java.util.concurrent.CompletableFuture;

/**
 * One condition admission held for the lifetime of an extraction attempt.
 *
 * <p>Most conditions use the no-op lifecycle supplied by
 * {@link ExtractionCondition#acquire(ExtractionContext)}. Stateful conditions,
 * such as an extraction with a limited number of seats, reserve capacity when
 * the permit is acquired, release it when the attempt is cancelled, and consume
 * it only after durable extraction settlement succeeds.</p>
 */
public interface ExtractionPermit {

    /** Performs the final condition check after the in-world countdown. */
    CompletableFuture<Boolean> authorize();

    /** Consumes a successfully settled permit. */
    default void commit() {
    }

    /** Releases a cancelled or failed permit. Implementations must be idempotent. */
    default void release() {
    }
}
