package com.birditch.escapefromcraftov.lobby.matchmaking;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.matchmaking.MatchTicket;
import com.birditch.escapefromcraftov.api.matchmaking.MatchmakingFailures;
import com.birditch.escapefromcraftov.api.matchmaking.MatchmakingService;
import com.birditch.escapefromcraftov.api.network.GameServerSnapshot;
import com.birditch.escapefromcraftov.api.network.MatchAssignment;
import com.birditch.escapefromcraftov.api.network.NetworkKeys;
import com.birditch.escapefromcraftov.api.network.ProvisionRequest;
import com.birditch.escapefromcraftov.api.network.ServerId;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.api.settings.SettingKey;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.cooldown.Cooldown;
import com.birditch.escapefromcraftov.common.result.Result;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.jspecify.annotations.Nullable;

/// FIFO matchmaking queue driven by a main-thread timer.
///
/// Every tick reads the Redis instance directory off-thread, hops back to the
/// main thread and assigns the oldest tickets to the fullest joinable
/// instances (see [InstancePicker]). A successful assignment is persisted as a
/// pending key before the player is proxied; a failed persist keeps the ticket
/// for the next tick. While that write is in flight the ticket is marked and
/// skipped, so a slow write is never double-assigned. Slot reservations with a
/// TTL bridge the gap between two directory snapshots so one instance is never
/// oversold.
///
/// Main-thread confined; Redis failures degrade to rate-limited warnings and
/// matchmaking resumes as soon as Redis returns.
public final class QueueService implements MatchmakingService {

  private static final Duration PROVISION_HINT_COOLDOWN = Duration.ofSeconds(10);
  private static final Duration WAITING_MESSAGE_COOLDOWN = Duration.ofSeconds(15);
  private static final Duration REDIS_WARNING_COOLDOWN = Duration.ofSeconds(30);

  private final QueueDeps deps;
  private final SequencedMap<UUID, MatchTicket> tickets = new LinkedHashMap<>();
  private final Map<String, List<Long>> reservations = new HashMap<>();
  private final Set<UUID> inFlight = new HashSet<>();
  private final Cooldown<String> provisionHints;
  private final Cooldown<UUID> waitingMessages;
  private final Cooldown<String> redisWarnings;
  private @Nullable ScheduledTask tickTask;

  public QueueService(QueueDeps deps) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
    this.provisionHints = new Cooldown<>(PROVISION_HINT_COOLDOWN, deps.timeSource());
    this.waitingMessages = new Cooldown<>(WAITING_MESSAGE_COOLDOWN, deps.timeSource());
    this.redisWarnings = new Cooldown<>(REDIS_WARNING_COOLDOWN, deps.timeSource());
  }

  public void start() {
    checkMainThread();
    Preconditions.checkState(tickTask == null, "QueueService already started");
    Duration tick = Duration.ofSeconds(deps.config().queueTickSeconds());
    tickTask = deps.scheduler().runTimer(tick, tick, this::tick);
  }

  public void stop() {
    ScheduledTask task = tickTask;
    if (task != null) {
      task.cancel();
      tickTask = null;
    }
  }

  @Override
  public Result<MatchTicket> queue(UUID playerId, CraftovMapId mapId) {
    checkMainThread();
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(mapId, "mapId");
    if (!deps.dataGate().isReady(playerId)) {
      return Result.fail(MatchmakingFailures.PLAYER_DATA_NOT_READY);
    }
    if (tickets.containsKey(playerId)) {
      return Result.fail(MatchmakingFailures.ALREADY_QUEUED);
    }
    MatchTicket ticket = new MatchTicket(playerId, mapId, deps.timeSource().now());
    tickets.put(playerId, ticket);
    return Result.ok(ticket);
  }

  @Override
  public boolean dequeue(UUID playerId) {
    checkMainThread();
    Preconditions.checkNotNull(playerId, "playerId");
    return tickets.remove(playerId) != null;
  }

  @Override
  public Optional<MatchTicket> ticket(UUID playerId) {
    checkMainThread();
    Preconditions.checkNotNull(playerId, "playerId");
    return Optional.ofNullable(tickets.get(playerId));
  }

  @Override
  public int queueSize() {
    checkMainThread();
    return tickets.size();
  }

  private void tick() {
    if (tickets.isEmpty()) {
      return;
    }
    deps.directory().activeServers().whenComplete((servers, error) ->
        deps.scheduler().runSync(() -> process(servers, error)));
  }

  private void process(@Nullable List<GameServerSnapshot> servers, @Nullable Throwable error) {
    if (error != null || servers == null) {
      if (redisWarnings.tryUse("directory")) {
        deps.logger().log(Level.WARNING,
            "读取 Redis 实例目录失败,匹配已暂停;Redis 恢复后会自动继续。", error);
      }
      return;
    }
    pruneReservations();
    Set<CraftovMapId> starvingMaps = new HashSet<>();
    for (MatchTicket ticket : List.copyOf(tickets.values())) {
      UUID playerId = ticket.playerId();
      if (!tickets.containsKey(playerId) || inFlight.contains(playerId)) {
        continue;
      }
      if (!deps.players().isOnline(playerId)) {
        tickets.remove(playerId);
        continue;
      }
      Optional<InstancePicker.Candidate> pick =
          InstancePicker.pick(servers, ticket.mapId(), reservationCounts());
      if (pick.isEmpty()) {
        starvingMaps.add(ticket.mapId());
        notifyWaiting(playerId);
        continue;
      }
      assign(ticket, pick.get());
    }
    starvingMaps.forEach(mapId -> publishProvisionHint(servers, mapId));
  }

  private void assign(MatchTicket ticket, InstancePicker.Candidate candidate) {
    String reservationKey = InstancePicker.reservationKey(
        candidate.server().serverId(), candidate.instance().gameId());
    MatchAssignment assignment = new MatchAssignment(
        ticket.playerId(),
        candidate.server().serverId(),
        candidate.server().proxyName(),
        candidate.instance().gameId(),
        candidate.instance().mapId());
    long expiresAt = deps.timeSource().currentMillis()
        + Duration.ofSeconds(deps.config().reservationTtlSeconds()).toMillis();
    reservations.computeIfAbsent(reservationKey, _ -> new ArrayList<>()).add(expiresAt);
    inFlight.add(ticket.playerId());
    deps.assignments()
        .storePending(assignment, Duration.ofSeconds(deps.config().assignmentTtlSeconds()))
        .whenComplete((_, storeError) -> deps.scheduler().runSync(
            () -> afterStore(assignment, reservationKey, expiresAt, storeError)));
  }

  /// Runs on the main thread after the pending key write and clears the
  /// in-flight marker. Failure releases the reservation and keeps the ticket
  /// so the next tick retries; success removes the ticket and proxies the
  /// player.
  private void afterStore(MatchAssignment assignment, String reservationKey, long expiresAt,
      @Nullable Throwable storeError) {
    inFlight.remove(assignment.playerId());
    if (storeError != null) {
      releaseReservation(reservationKey, expiresAt);
      if (redisWarnings.tryUse("store")) {
        deps.logger().log(Level.WARNING,
            "写入匹配分配到 Redis 失败,该玩家保留在队列中等待下一轮。", storeError);
      }
      return;
    }
    tickets.remove(assignment.playerId());
    deps.players().sendMessage(assignment.playerId(), "queue.match-found",
        Map.of("server", assignment.proxyName()));
    deps.players().connectToServer(assignment.playerId(), assignment.proxyName());
  }

  private void notifyWaiting(UUID playerId) {
    if (deps.settings().get(playerId, SettingKey.QUEUE_STATUS_MESSAGES)
        && waitingMessages.tryUse(playerId)) {
      deps.players().sendMessage(playerId, "queue.waiting", Map.of());
    }
  }

  /// Nudges the least-loaded expandable server to provision another instance;
  /// rate-limited per server so a starved queue does not flood the channel.
  private void publishProvisionHint(List<GameServerSnapshot> servers, CraftovMapId mapId) {
    Optional<GameServerSnapshot> target = servers.stream()
        .filter(server -> server.instances().size() < server.maxInstances())
        .min(Comparator.comparingInt((GameServerSnapshot server) -> server.instances().size())
            .thenComparing(server -> server.serverId().value()));
    if (target.isEmpty()) {
      return;
    }
    ServerId serverId = target.get().serverId();
    String cooldownKey = serverId.value() + ":" + mapId.value();
    if (!provisionHints.tryUse(cooldownKey)) {
      return;
    }
    String payload = deps.codec().toJson(new ProvisionRequest(serverId, mapId));
    deps.broker().publish(NetworkKeys.PROVISION_CHANNEL, payload)
        .whenComplete((_, publishError) -> {
          if (publishError != null) {
            deps.logger().log(Level.FINE, "发布实例扩容提示失败。", publishError);
          }
        });
  }

  private void releaseReservation(String reservationKey, long expiresAt) {
    List<Long> entries = reservations.get(reservationKey);
    if (entries == null) {
      return;
    }
    entries.remove(Long.valueOf(expiresAt));
    if (entries.isEmpty()) {
      reservations.remove(reservationKey);
    }
  }

  private void pruneReservations() {
    long now = deps.timeSource().currentMillis();
    reservations.values().forEach(entries -> entries.removeIf(expiry -> expiry <= now));
    reservations.values().removeIf(List::isEmpty);
  }

  private Map<String, Integer> reservationCounts() {
    Map<String, Integer> counts = new HashMap<>();
    reservations.forEach((key, entries) -> counts.put(key, entries.size()));
    return counts;
  }

  private void checkMainThread() {
    if (!deps.scheduler().isMainThread()) {
      throw new IllegalStateException("QueueService must be called from the main thread");
    }
  }
}
