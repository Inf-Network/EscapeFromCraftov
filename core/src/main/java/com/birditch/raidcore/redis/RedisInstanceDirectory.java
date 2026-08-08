package com.birditch.raidcore.redis;

import com.birditch.raidcore.api.RaidException;
import com.birditch.raidcore.api.network.GameServerSnapshot;
import com.birditch.raidcore.api.network.InstanceDirectory;
import com.birditch.raidcore.api.network.NetworkKeys;
import com.birditch.raidcore.common.check.Preconditions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

/// Publishes and reads game-server snapshots under [NetworkKeys#SERVER_KEY_PATTERN].
/// Each snapshot is a TTL heartbeat: a crashed server simply expires out of the
/// directory. Malformed snapshots are skipped with a warning — rate-limited to
/// one per key per [#WARN_INTERVAL_MILLIS] — and never propagated.
public final class RedisInstanceDirectory implements InstanceDirectory {

  private static final int SCAN_BATCH_SIZE = 100;
  private static final long WARN_INTERVAL_MILLIS = 30_000L;

  private final RedisConnector redis;
  private final JsonCodec codec;
  private final ExecutorService ioExecutor;
  private final Logger logger;
  private final Map<String, AtomicLong> warnedAtByKey = new ConcurrentHashMap<>();

  public RedisInstanceDirectory(
      RedisConnector redis, JsonCodec codec, ExecutorService ioExecutor, Logger logger) {
    this.redis = Preconditions.checkNotNull(redis, "redis");
    this.codec = Preconditions.checkNotNull(codec, "codec");
    this.ioExecutor = Preconditions.checkNotNull(ioExecutor, "ioExecutor");
    this.logger = Preconditions.checkNotNull(logger, "logger");
  }

  @Override
  public CompletableFuture<Void> publish(GameServerSnapshot snapshot, Duration ttl) {
    Preconditions.checkNotNull(snapshot, "snapshot");
    Preconditions.checkNotNull(ttl, "ttl");
    Preconditions.checkArgument(ttl.toMillis() > 0, "ttl must be positive");
    return CompletableFuture.runAsync(() -> {
      try (Jedis jedis = redis.resource()) {
        jedis.set(NetworkKeys.serverKey(snapshot.serverId()), codec.toJson(snapshot),
            SetParams.setParams().px(ttl.toMillis()));
      }
    }, ioExecutor);
  }

  @Override
  public CompletableFuture<List<GameServerSnapshot>> activeServers() {
    return CompletableFuture.supplyAsync(this::fetchActiveServers, ioExecutor);
  }

  private List<GameServerSnapshot> fetchActiveServers() {
    List<GameServerSnapshot> servers = new ArrayList<>();
    try (Jedis jedis = redis.resource()) {
      for (String key : scanServerKeys(jedis)) {
        String json = jedis.get(key);
        if (json == null) {
          continue;
        }
        try {
          servers.add(codec.fromJson(json, GameServerSnapshot.class));
        } catch (RaidException e) {
          if (shouldWarn(key)) {
            logger.log(Level.WARNING, "Skipping malformed server snapshot at " + key, e);
          }
        }
      }
    }
    return List.copyOf(servers);
  }

  /// Lock-free per-key throttle: allows at most one warning per key per
  /// [#WARN_INTERVAL_MILLIS]. Runs on IO threads, hence [AtomicLong] instead of
  /// the main-thread-only `Cooldown`.
  private boolean shouldWarn(String key) {
    AtomicLong warnedAt = warnedAtByKey.computeIfAbsent(key, _ -> new AtomicLong());
    long now = System.currentTimeMillis();
    long previous = warnedAt.get();
    return now - previous >= WARN_INTERVAL_MILLIS && warnedAt.compareAndSet(previous, now);
  }

  private static List<String> scanServerKeys(Jedis jedis) {
    List<String> keys = new ArrayList<>();
    ScanParams params =
        new ScanParams().match(NetworkKeys.SERVER_KEY_PATTERN).count(SCAN_BATCH_SIZE);
    String cursor = ScanParams.SCAN_POINTER_START;
    do {
      ScanResult<String> result = jedis.scan(cursor, params);
      keys.addAll(result.getResult());
      cursor = result.getCursor();
    } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
    return keys;
  }
}
