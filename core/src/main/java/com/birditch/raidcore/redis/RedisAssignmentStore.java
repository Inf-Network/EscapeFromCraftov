package com.birditch.raidcore.redis;

import com.birditch.raidcore.api.network.AssignmentStore;
import com.birditch.raidcore.api.network.MatchAssignment;
import com.birditch.raidcore.api.network.NetworkKeys;
import com.birditch.raidcore.common.check.Preconditions;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

/// Stores pending match assignments keyed by player. Consumption uses `GETDEL`,
/// so exactly one game server can claim an assignment even when a player
/// reconnects rapidly or two servers race.
public final class RedisAssignmentStore implements AssignmentStore {

  private final RedisConnector redis;
  private final JsonCodec codec;
  private final ExecutorService ioExecutor;

  public RedisAssignmentStore(RedisConnector redis, JsonCodec codec, ExecutorService ioExecutor) {
    this.redis = Preconditions.checkNotNull(redis, "redis");
    this.codec = Preconditions.checkNotNull(codec, "codec");
    this.ioExecutor = Preconditions.checkNotNull(ioExecutor, "ioExecutor");
  }

  @Override
  public CompletableFuture<Void> storePending(MatchAssignment assignment, Duration ttl) {
    Preconditions.checkNotNull(assignment, "assignment");
    Preconditions.checkNotNull(ttl, "ttl");
    Preconditions.checkArgument(ttl.toMillis() > 0, "ttl must be positive");
    return CompletableFuture.runAsync(() -> {
      try (Jedis jedis = redis.resource()) {
        jedis.set(NetworkKeys.pendingKey(assignment.playerId()), codec.toJson(assignment),
            SetParams.setParams().px(ttl.toMillis()));
      }
    }, ioExecutor);
  }

  @Override
  public CompletableFuture<Optional<MatchAssignment>> consumePending(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return CompletableFuture.supplyAsync(() -> {
      String json;
      try (Jedis jedis = redis.resource()) {
        json = jedis.getDel(NetworkKeys.pendingKey(playerId));
      }
      return json == null
          ? Optional.empty()
          : Optional.of(codec.fromJson(json, MatchAssignment.class));
    }, ioExecutor);
  }
}
