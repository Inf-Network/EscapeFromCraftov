package com.birditch.raidcore.common.random;

import com.birditch.raidcore.common.check.Preconditions;

/// Source of randomness; inject instead of using static RNG access so tests
/// can substitute a seeded implementation.
public interface RandomProvider {

    /// Uniform value in `[0, bound)`.
    int nextInt(int bound);

    /// Uniform value in `[0.0, 1.0)`.
    double nextDouble();

    /// Uniform value in `[minInclusive, maxInclusive]`.
    default int between(int minInclusive, int maxInclusive) {
        Preconditions.checkArgument(minInclusive <= maxInclusive,
                "minInclusive must not be greater than maxInclusive");
        return minInclusive + nextInt(maxInclusive - minInclusive + 1);
    }
}
