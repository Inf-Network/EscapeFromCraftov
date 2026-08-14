package com.birditch.escapefromcraftov.api.network;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/// Publish/subscribe messaging between servers.
///
/// Handlers run on a broker thread — callers must hop back to the main thread
/// via `CraftovScheduler.runSync` before touching game or domain state.
public interface MessageBroker {

    CompletableFuture<Void> publish(String channel, String payload);

    MessageSubscription subscribe(String channel, Consumer<String> handler);

    /// Unsubscribes every active subscription and releases broker resources.
    void close();
}
