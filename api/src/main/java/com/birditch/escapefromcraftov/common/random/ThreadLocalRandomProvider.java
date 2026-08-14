package com.birditch.escapefromcraftov.common.random;

import java.util.concurrent.ThreadLocalRandom;

/// Production [RandomProvider] delegating to [ThreadLocalRandom#current()].
public final class ThreadLocalRandomProvider implements RandomProvider {

    @Override
    public int nextInt(int bound) {
        return ThreadLocalRandom.current().nextInt(bound);
    }

    @Override
    public double nextDouble() {
        return ThreadLocalRandom.current().nextDouble();
    }
}
