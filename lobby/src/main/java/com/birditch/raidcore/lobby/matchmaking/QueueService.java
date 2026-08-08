package com.birditch.raidcore.lobby.matchmaking;

import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.matchmaking.MatchTicket;
import com.birditch.raidcore.api.matchmaking.MatchmakingService;
import com.birditch.raidcore.api.network.GameServerSnapshot;
import com.birditch.raidcore.api.network.MatchAssignment;
import com.birditch.raidcore.api.network.NetworkKeys;
import com.birditch.raidcore.api.network.ServerId;
import com.birditch.raidcore.api.scheduler.ScheduledTask;
import com.birditch.raidcore.api.settings.SettingKey;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.cooldown.Cooldown;
import com.birditch.raidcore.common.result.Result;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/// FIFO matchmaking queue driven by a main-thread timer.
///
/// Every tick reads the Redis instance directory off-thread, hops back to the
/// main thread and assigns the oldest tickets to the fullest joinable
/// instances (see [InstancePicker]). A successful assignment is persisted as a
/// pending key before the player is proxied; a failed persist keeps the ticket
/// for the next tick. Slot reservations with a TTL bridge the gap between two
/// directory snapshots so one instance is never oversold.
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
  public Result<MatchTicket> queue(UUID playerId, RaidMapId mapId) {
    checkMainThread();
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(mapId, "mapId");
    if (tickets.containsKey(playerId)) {
      return Result.fail("Already in queue");
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
    boolean starving = false;
    for (MatchTicket ticket : List.copyOf(tickets.values())) {
      if (!tickets.containsKey(ticket.playerId())) {
        continue;
      }
      Player player = deps.plugin().getServer().getPlayer(ticket.playerId());
      if (player == null) {
        tickets.remove(ticket.playerId());
        continue;
      }
      Optional<InstancePicker.Candidate> pick = InstancePicker.pick(servers, reservationCounts());
      if (pick.isEmpty()) {
        starving = true;
        notifyWaiting(player);
        continue;
      }
      assign(ticket, pick.get());
    }
    if (starving) {
      publishProvisionHint(servers);
    }
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
    deps.assignments()
        .storePending(assignment, Duration.ofSeconds(deps.config().assignmentTtlSeconds()))
        .whenComplete((_, storeError) -> deps.scheduler().runSync(
            () -> afterStore(assignment, reservationKey, expiresAt, storeError)));
  }

  /// Runs on the main thread after the pending key write. Failure releases the
  /// reservation and keeps the ticket so the next tick retries; success removes
  /// the ticket and proxies the player.
  private void afterStore(MatchAssignment assignment, String reservationKey, long expiresAt,
      @Nullable Throwable storeError) {
    if (storeError != null) {
      releaseReservation(reservationKey, expiresAt);
      if (redisWarnings.tryUse("store")) {
        deps.logger().log(Level.WARNING,
            "写入匹配分配到 Redis 失败,该玩家保留在队列中等待下一轮。", storeError);
      }
      return;
    }
    tickets.remove(assignment.playerId());
    Player player = deps.plugin().getServer().getPlayer(assignment.playerId());
    if (player == null) {
      return;
    }
    player.sendMessage(deps.messages().raw("queue.match-found",
        Placeholder.unparsed("server", assignment.proxyName())));
    deps.bungee().send(player, assignment.proxyName());
  }

  private void notifyWaiting(Player player) {
    UUID playerId = player.getUniqueId();
    if (deps.settings().get(playerId, SettingKey.QUEUE_STATUS_MESSAGES)
        && waitingMessages.tryUse(playerId)) {
      player.sendMessage(deps.messages().raw("queue.waiting"));
    }
  }

  /// Nudges the least-loaded expandable server to provision another instance;
  /// rate-limited per server so a starved queue does not flood the channel.
  private void publishProvisionHint(List<GameServerSnapshot> servers) {
    Optional<ServerId> target = servers.stream()
        .filter(server -> server.instances().size() < server.maxInstances())
        .map(GameServerSnapshot::serverId)
        .min(Comparator.comparing(ServerId::value));
    if (target.isEmpty() || !provisionHints.tryUse(target.get().value())) {
      return;
    }
    deps.broker().publish(NetworkKeys.PROVISION_CHANNEL, target.get().value())
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
