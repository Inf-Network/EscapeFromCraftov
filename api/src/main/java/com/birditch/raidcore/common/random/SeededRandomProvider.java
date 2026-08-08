package com.birditch.raidcore.common.random;

import java.util.Random;

/// Deterministic [RandomProvider] wrapping a seeded [Random]; intended for
/// tests and reproducible simulations.
public final class SeededRandomProvider implements RandomProvider {

    private final Random random;

    public SeededRandomProvider(long seed) {
        this.random = new Random(seed);
    }

    @Override
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    @Override
    public double nextDouble() {
        return random.nextDouble();
    }
}
