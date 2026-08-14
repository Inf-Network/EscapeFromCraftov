package com.birditch.escapefromcraftov.api.event;

import java.util.function.Consumer;

/// In-process domain event bus.
///
/// Dispatch is exact-class: a listener subscribed to type `T` receives only
/// events whose runtime class is exactly `T`, never subtypes.
public interface EventBus {

    <T extends CraftovEvent> EventSubscription subscribe(Class<T> eventType, Consumer<T> listener);

    /// Scans `listener` for public [Subscribe] methods taking exactly one
    /// parameter of a [CraftovEvent] subtype and subscribes each of them.
    ///
    /// @throws IllegalArgumentException if no valid `@Subscribe` method is found
    ///         or an annotated method has an invalid signature
    /// @return a subscription that unsubscribes all scanned methods at once
    EventSubscription register(Object listener);

    /// Publishes `event` to all listeners of its exact class. Main-thread only.
    void publish(CraftovEvent event);
}
