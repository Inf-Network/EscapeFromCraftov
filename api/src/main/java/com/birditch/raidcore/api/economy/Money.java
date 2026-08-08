package com.birditch.raidcore.api.economy;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.Locale;

/// Non-negative amount in minor currency units. Never floating point.
public record Money(long units) {

    public static final Money ZERO = new Money(0L);

    public Money {
        Preconditions.checkArgument(units >= 0, "units must not be negative");
    }

    public static Money of(long units) {
        return new Money(units);
    }

    public Money plus(Money other) {
        Preconditions.checkNotNull(other, "other");
        return new Money(Math.addExact(units, other.units));
    }

    /// @throws IllegalArgumentException if the result would be negative
    public Money minus(Money other) {
        Preconditions.checkNotNull(other, "other");
        Preconditions.checkArgument(units >= other.units, "Resulting amount must not be negative");
        return new Money(units - other.units);
    }

    public boolean isZero() {
        return units == 0;
    }

    public boolean atLeast(Money other) {
        Preconditions.checkNotNull(other, "other");
        return units >= other.units;
    }

    /// Grouped decimal representation, e.g. `12,500`.
    public String format() {
        return String.format(Locale.ROOT, "%,d", units);
    }
}
