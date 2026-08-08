package com.birditch.raidcore.common.random;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.ArrayList;
import java.util.List;

/// Immutable weighted random selector using cumulative-weight sampling; each
/// entry is chosen with probability `weight / totalWeight()`.
public final class WeightedRandom<T> {

    private final List<Entry<T>> entries;
    private final int totalWeight;

    private WeightedRandom(List<Entry<T>> entries, int totalWeight) {
        this.entries = entries;
        this.totalWeight = totalWeight;
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /// Selection cost is linear in [#size()].
    public T pick(RandomProvider random) {
        Preconditions.checkNotNull(random, "random");
        int roll = random.nextInt(totalWeight);
        for (Entry<T> entry : entries) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry.value();
            }
        }
        throw new IllegalStateException("Cumulative weight scan failed to select an entry");
    }

    public int totalWeight() {
        return totalWeight;
    }

    public int size() {
        return entries.size();
    }

    private record Entry<T>(T value, int weight) {
    }

    /// Accumulates weighted entries; not thread-safe.
    public static final class Builder<T> {

        private final List<Entry<T>> entries = new ArrayList<>();

        private Builder() {
        }

        /// @throws IllegalArgumentException if `weight` is not positive
        public Builder<T> add(T value, int weight) {
            Preconditions.checkNotNull(value, "value");
            Preconditions.checkArgument(weight > 0, "weight must be greater than 0");
            entries.add(new Entry<>(value, weight));
            return this;
        }

        /// @throws IllegalStateException if no entry was added
        public WeightedRandom<T> build() {
            Preconditions.checkState(!entries.isEmpty(), "WeightedRandom requires at least one entry");
            int total = 0;
            for (Entry<T> entry : entries) {
                total = Math.addExact(total, entry.weight());
            }
            return new WeightedRandom<>(List.copyOf(entries), total);
        }
    }
}
