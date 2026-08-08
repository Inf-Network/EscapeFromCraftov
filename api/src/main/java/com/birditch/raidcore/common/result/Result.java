package com.birditch.raidcore.common.result;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.Optional;
import java.util.function.Function;

/// Outcome of an operation that either succeeded with a value or failed with a
/// human-readable message.
///
/// Instances are immutable. Consume via the accessor methods or by pattern
/// matching over the sealed hierarchy; failures propagate unchanged through
/// [#map(Function)].
public sealed interface Result<T> permits Result.Success, Result.Failure {

    /// Successful outcome carrying the produced value.
    ///
    /// The component is named `result` because a component named `value` would
    /// generate an accessor `T value()` clashing with [Result#value()].
    record Success<T>(T result) implements Result<T> {
        public Success {
            Preconditions.checkNotNull(result, "value");
        }
    }

    /// Failed outcome carrying a human-readable message.
    record Failure<T>(String message) implements Result<T> {
        public Failure {
            Preconditions.checkNotNull(message, "message");
        }
    }

    static <T> Result<T> ok(T value) {
        return new Success<>(value);
    }

    static <T> Result<T> fail(String message) {
        return new Failure<>(message);
    }

    default boolean isSuccess() {
        return this instanceof Success<T>;
    }

    default Optional<T> value() {
        return switch (this) {
            case Success<T>(T result) -> Optional.of(result);
            case Failure<T> _ -> Optional.empty();
        };
    }

    default Optional<String> error() {
        return switch (this) {
            case Success<T> _ -> Optional.empty();
            case Failure<T>(String message) -> Optional.of(message);
        };
    }

    /// Transforms the success value; a failure is returned unchanged.
    default <U> Result<U> map(Function<T, U> mapper) {
        Preconditions.checkNotNull(mapper, "mapper");
        return switch (this) {
            case Success<T>(T result) -> new Success<>(mapper.apply(result));
            case Failure<T>(String message) -> new Failure<>(message);
        };
    }

    /// Returns the success value or throws the exception built from the
    /// failure message.
    default T orElseThrow(Function<String, ? extends RuntimeException> factory) {
        Preconditions.checkNotNull(factory, "factory");
        return switch (this) {
            case Success<T>(T result) -> result;
            case Failure<T>(String message) -> throw factory.apply(message);
        };
    }
}
