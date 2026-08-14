package com.birditch.escapefromcraftov.api.extraction;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.time.Duration;
import java.util.List;

/// A map location players extract from. Main-thread confined.
///
/// The point itself is only ever [ExtractionState#LOCKED] or
/// [ExtractionState#AVAILABLE]; the remaining states belong to [ExtractionSession].
public final class ExtractionPoint {

    private final ExtractionPointId id;
    private final String displayName;
    private final ExtractionType type;
    private final Duration duration;
    private final List<ExtractionCondition> conditions;
    private ExtractionState state;

    public ExtractionPoint(ExtractionPointId id, String displayName, ExtractionType type,
                           Duration duration, List<ExtractionCondition> conditions,
                           ExtractionState initialState) {
        Preconditions.checkNotNull(id, "id");
        Preconditions.checkNotNull(displayName, "displayName");
        Preconditions.checkArgument(!displayName.isBlank(), "displayName must not be blank");
        Preconditions.checkNotNull(type, "type");
        Preconditions.checkNotNull(duration, "duration");
        Preconditions.checkArgument(!duration.isNegative(), "duration must not be negative");
        Preconditions.checkNotNull(conditions, "conditions");
        Preconditions.checkNotNull(initialState, "initialState");
        Preconditions.checkArgument(
                initialState == ExtractionState.LOCKED || initialState == ExtractionState.AVAILABLE,
                "initialState must be LOCKED or AVAILABLE");
        this.id = id;
        this.displayName = displayName;
        this.type = type;
        this.duration = duration;
        this.conditions = List.copyOf(conditions);
        this.state = initialState;
    }

    public ExtractionPointId id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public ExtractionType type() {
        return type;
    }

    public Duration duration() {
        return duration;
    }

    public List<ExtractionCondition> conditions() {
        return conditions;
    }

    public ExtractionState state() {
        return state;
    }

    /// @throws IllegalStateException unless currently LOCKED
    public void unlock() {
        if (state != ExtractionState.LOCKED) {
            throw new IllegalStateException("Cannot unlock extraction point in state " + state);
        }
        state = ExtractionState.AVAILABLE;
    }

    /// @throws IllegalStateException unless currently AVAILABLE
    public void lock() {
        if (state != ExtractionState.AVAILABLE) {
            throw new IllegalStateException("Cannot lock extraction point in state " + state);
        }
        state = ExtractionState.LOCKED;
    }
}
