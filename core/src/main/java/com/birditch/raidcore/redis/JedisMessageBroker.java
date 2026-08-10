package com.birditch.raidcore.redis;

import com.birditch.raidcore.api.network.MessageBroker;
import com.birditch.raidcore.api.network.MessageSubscription;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import redis.clients.jedis.JedisPubSub;

/// Redis pub/sub broker. Each subscription runs a dedicated virtual thread that
/// blocks in `SUBSCRIBE` on its own pooled connection and reconnects after a
/// short delay when the connection drops, so a Redis blip never permanently
/// silences a channel. Handlers run on that subscriber thread — callers must hop
/// to the main thread before touching game state.
public final class JedisMessageBroker implements MessageBroker {

  private static final long RETRY_DELAY_MILLIS = 2_000L;

  private final RedisConnector redis;
  private final Logger logger;
  private final Set<ChannelSubscription> subscriptions = ConcurrentHashMap.newKeySet();

  public JedisMessageBroker(RedisConnector redis, Logger logger) {
    this.redis = Preconditions.checkNotNull(redis, "redis");
    this.logger = Preconditions.checkNotNull(logger, "logger");
  }

  @Override
  public CompletableFuture<Void> publish(String channel, String payload) {
    Preconditions.checkNotNull(channel, "channel");
    Preconditions.checkNotNull(payload, "payload");
    CompletableFuture<Void> future = new CompletableFuture<>();
    Thread.startVirtualThread(() -> {
      try {
        redis.client().publish(channel, payload);
        future.complete(null);
      } catch (RuntimeException e) {
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  @Override
  public MessageSubscription subscribe(String channel, Consumer<String> handler) {
    Preconditions.checkNotNull(channel, "channel");
    Preconditions.checkNotNull(handler, "handler");
    ChannelSubscription subscription = new ChannelSubscription(channel, handler);
    subscriptions.add(subscription);
    subscription.start();
    return subscription;
  }

  @Override
  public void close() {
    for (ChannelSubscription subscription : List.copyOf(subscriptions)) {
      try {
        subscription.unsubscribe();
      } catch (RuntimeException e) {
        logger.log(Level.WARNING, "关闭 Redis 订阅时出错, 频道: " + subscription.channel, e);
      }
    }
  }

  private final class ChannelSubscription implements MessageSubscription {

    private final String channel;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final JedisPubSub pubSub;
    private volatile @Nullable Thread subscriberThread;

    ChannelSubscription(String channel, Consumer<String> handler) {
      this.channel = channel;
      this.pubSub = new JedisPubSub() {
        @Override
        public void onMessage(String messageChannel, String message) {
          try {
            handler.accept(message);
          } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Message handler failed on channel " + messageChannel, e);
          }
        }
      };
    }

    void start() {
      subscriberThread = Thread.ofVirtual().name("raidcore-redis-sub-" + channel).start(this::run);
    }

    private void run() {
      try {
        while (active.get()) {
          listenOnce();
        }
      } finally {
        subscriptions.remove(this);
      }
    }

    /// One blocking `SUBSCRIBE` pass; returns normally only after an unsubscribe.
    /// A lost connection is logged and retried after [#RETRY_DELAY_MILLIS].
    private void listenOnce() {
      try {
        redis.client().subscribe(pubSub, channel);
        active.set(false);
      } catch (RuntimeException e) {
        if (!active.get()) {
          return;
        }
        logger.log(Level.WARNING,
            "Redis subscription lost on channel " + channel + "; retrying", e);
        pause();
      }
    }

    private void pause() {
      try {
        Thread.sleep(RETRY_DELAY_MILLIS);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        active.set(false);
      }
    }

    @Override
    public void unsubscribe() {
      if (!active.compareAndSet(true, false)) {
        return;
      }
      /// Guarded so a broken connection can never skip the thread interrupt and
      /// set removal below.
      try {
        if (pubSub.isSubscribed()) {
          pubSub.unsubscribe();
        }
      } catch (RuntimeException e) {
        logger.log(Level.WARNING, "取消订阅 Redis 频道失败: " + channel, e);
      }
      Thread thread = subscriberThread;
      if (thread != null) {
        thread.interrupt();
      }
      subscriptions.remove(this);
    }

    @Override
    public boolean active() {
      return active.get();
    }
  }
}
