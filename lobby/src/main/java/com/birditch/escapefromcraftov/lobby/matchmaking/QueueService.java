package com.birditch.escapefromcraftov.lobby.matchmaking;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
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
import java.util.Collection;
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
import java.util.concurrent.CompletableFuture;
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
    return queue(playerId, mapId, RaidMode.NORMAL, RaidDifficulty.NORMAL, playerId);
  }

  @Override
  public Result<MatchTicket> queue(UUID playerId, CraftovMapId mapId, UUID teamKey) {
    return queue(playerId, mapId, RaidMode.NORMAL, RaidDifficulty.NORMAL, teamKey);
  }

  @Override
  public Result<MatchTicket> queue(UUID playerId, CraftovMapId mapId, RaidMode mode,
      RaidDifficulty difficulty, UUID teamKey) {
    Result<List<MatchTicket>> result = queueParty(
        List.of(playerId), mapId, mode, difficulty, teamKey);
    return result.map(List::getFirst);
  }

  @Override
  public Result<List<MatchTicket>> queueParty(List<UUID> playerIds, CraftovMapId mapId,
      RaidMode mode, RaidDifficulty difficulty, UUID teamKey) {
    checkMainThread();
    Preconditions.checkNotNull(playerIds, "playerIds");
    Preconditions.checkNotNull(mapId, "mapId");
    Preconditions.checkNotNull(mode, "mode");
    Preconditions.checkNotNull(difficulty, "difficulty");
    Preconditions.checkNotNull(teamKey, "teamKey");
    List<UUID> members = playerIds.stream().distinct().toList();
    if (members.size() != playerIds.size() || members.isEmpty() || members.size() > 3) {
      return Result.fail(MatchmakingFailures.PARTY_SIZE_INVALID);
    }
    if (members.stream().anyMatch(playerId -> !deps.dataGate().isReady(playerId))) {
      return Result.fail(MatchmakingFailures.PLAYER_DATA_NOT_READY);
    }
    if (members.stream().anyMatch(tickets::containsKey)
        || members.stream().anyMatch(inFlight::contains)
        || tickets.values().stream().anyMatch(ticket -> ticket.teamKey().equals(teamKey))) {
      return Result.fail(MatchmakingFailures.ALREADY_QUEUED);
    }
    var queuedAt = deps.timeSource().now();
    List<MatchTicket> created = members.stream()
        .map(playerId -> new MatchTicket(
            playerId, mapId, queuedAt, teamKey, mode, difficulty))
        .toList();
    created.forEach(ticket -> tickets.put(ticket.playerId(), ticket));
    return Result.ok(created);
  }

  @Override
  public boolean dequeue(UUID playerId) {
    return dequeueTeam(playerId) > 0;
  }

  @Override
  public int dequeueTeam(UUID playerId) {
    checkMainThread();
    Preconditions.checkNotNull(playerId, "playerId");
    MatchTicket ticket = tickets.get(playerId);
    if (ticket == null) {
      return 0;
    }
    List<UUID> members = tickets.values().stream()
        .filter(candidate -> candidate.teamKey().equals(ticket.teamKey()))
        .map(MatchTicket::playerId)
        .toList();
    members.forEach(tickets::remove);
    return members.size();
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
    Map<MatchPool, Integer> starvingPools = new LinkedHashMap<>();
    for (TicketGroup group : ticketGroups()) {
      if (!group.isStillQueuedIn(tickets) || group.members().stream()
          .map(MatchTicket::playerId).anyMatch(inFlight::contains)) {
        continue;
      }
      if (group.members().stream().map(MatchTicket::playerId)
          .anyMatch(playerId -> !deps.players().isOnline(playerId))) {
        dequeueTeam(group.first().playerId());
        continue;
      }
      MatchTicket first = group.first();
      Optional<InstancePicker.Candidate> pick =
          InstancePicker.pick(servers, first.mapId(), first.mode(), first.difficulty(),
              group.size(), reservationCounts());
      if (pick.isEmpty()) {
        starvingPools.merge(MatchPool.of(first), group.size(), Math::max);
        group.members().forEach(ticket -> notifyWaiting(ticket.playerId()));
        continue;
      }
      assign(group, pick.get());
    }
    starvingPools.forEach((pool, requiredSlots) ->
        publishProvisionHint(servers, pool, requiredSlots));
  }

  private List<TicketGroup> ticketGroups() {
    Map<UUID, List<MatchTicket>> byTeam = new LinkedHashMap<>();
    tickets.values().forEach(ticket ->
        byTeam.computeIfAbsent(ticket.teamKey(), _ -> new ArrayList<>()).add(ticket));
    return byTeam.values().stream().map(TicketGroup::new).toList();
  }

  private void assign(TicketGroup group, InstancePicker.Candidate candidate) {
    String reservationKey = InstancePicker.reservationKey(
        candidate.server().serverId(), candidate.instance().gameId());
    List<MatchAssignment> groupAssignments = group.members().stream()
        .map(ticket -> new MatchAssignment(
            ticket.playerId(),
            candidate.server().serverId(),
            candidate.server().proxyName(),
            candidate.instance().gameId(),
            candidate.instance().mapId(),
            ticket.teamKey(),
            ticket.mode(),
            ticket.difficulty()))
        .toList();
    long expiresAt = deps.timeSource().currentMillis()
        + Duration.ofSeconds(deps.config().reservationTtlSeconds()).toMillis();
    List<Long> instanceReservations =
        reservations.computeIfAbsent(reservationKey, _ -> new ArrayList<>());
    for (int i = 0; i < group.size(); i++) instanceReservations.add(expiresAt);
    groupAssignments.forEach(assignment -> inFlight.add(assignment.playerId()));
    Duration assignmentTtl = Duration.ofSeconds(deps.config().assignmentTtlSeconds());
    CompletableFuture<?>[] writes = groupAssignments.stream()
        .map(assignment -> deps.assignments().storePending(assignment, assignmentTtl))
        .toArray(CompletableFuture[]::new);
    CompletableFuture.allOf(writes).whenComplete((_, storeError) ->
        deps.scheduler().runSync(() -> afterStore(
            group, groupAssignments, reservationKey, expiresAt, storeError)));
  }

  /// Runs on the main thread after the pending key write and clears the
  /// in-flight marker. Failure releases the reservation and keeps the ticket
  /// so the next tick retries; success removes the ticket and proxies the
  /// player.
  private void afterStore(TicketGroup group, List<MatchAssignment> assignments,
      String reservationKey, long expiresAt,
      @Nullable Throwable storeError) {
    assignments.forEach(assignment -> inFlight.remove(assignment.playerId()));
    if (storeError != null) {
      releaseReservations(reservationKey, expiresAt, assignments.size());
      removePendingBestEffort(assignments);
      if (redisWarnings.tryUse("store")) {
        deps.logger().log(Level.WARNING,
            "写入匹配分配到 Redis 失败,该队伍完整保留在队列中等待下一轮。", storeError);
      }
      return;
    }
    // A leave/offline action may have removed the group while Redis writes
    // were in flight. Delete those pending keys and never proxy a partial team.
    if (!group.isStillQueuedIn(tickets) || assignments.stream()
        .map(MatchAssignment::playerId).anyMatch(playerId -> !deps.players().isOnline(playerId))) {
      if (group.isStillQueuedIn(tickets)) dequeueTeam(group.first().playerId());
      releaseReservations(reservationKey, expiresAt, assignments.size());
      removePendingBestEffort(assignments);
      return;
    }
    assignments.forEach(assignment -> tickets.remove(assignment.playerId()));
    assignments.forEach(assignment -> {
      deps.players().sendMessage(assignment.playerId(), "queue.match-found",
          Map.of("server", assignment.proxyName()));
      deps.players().connectToServer(assignment.playerId(), assignment.proxyName());
    });
  }

  private void removePendingBestEffort(Collection<MatchAssignment> assignments) {
    assignments.forEach(assignment -> deps.assignments().removePending(assignment.playerId())
        .exceptionally(error -> {
          deps.logger().log(Level.FINE, "清理未完成的匹配分配失败。", error);
          return null;
        }));
  }

  private void notifyWaiting(UUID playerId) {
    if (deps.settings().get(playerId, SettingKey.QUEUE_STATUS_MESSAGES)
        && waitingMessages.tryUse(playerId)) {
      deps.players().sendMessage(playerId, "queue.waiting", Map.of());
    }
  }

  /// Nudges the least-loaded expandable server to provision another instance;
  /// rate-limited per server so a starved queue does not flood the channel.
  private void publishProvisionHint(List<GameServerSnapshot> servers, MatchPool pool,
      int requiredSlots) {
    Optional<GameServerSnapshot> target = servers.stream()
        .filter(server -> server.instances().size() < server.maxInstances())
        .min(Comparator.comparingInt((GameServerSnapshot server) -> server.instances().size())
            .thenComparing(server -> server.serverId().value()));
    if (target.isEmpty()) {
      return;
    }
    ServerId serverId = target.get().serverId();
    String cooldownKey = serverId.value() + ":" + pool.mapId().value()
        + ":" + pool.mode().id() + ":" + pool.difficulty().id();
    if (!provisionHints.tryUse(cooldownKey)) {
      return;
    }
    String payload = deps.codec().toJson(new ProvisionRequest(
        serverId, pool.mapId(), pool.mode(), pool.difficulty(), requiredSlots));
    deps.broker().publish(NetworkKeys.PROVISION_CHANNEL, payload)
        .whenComplete((_, publishError) -> {
          if (publishError != null) {
            deps.logger().log(Level.FINE, "发布实例扩容提示失败。", publishError);
          }
        });
  }

  private void releaseReservations(String reservationKey, long expiresAt, int count) {
    List<Long> entries = reservations.get(reservationKey);
    if (entries == null) {
      return;
    }
    for (int i = 0; i < count; i++) entries.remove(Long.valueOf(expiresAt));
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

  private record MatchPool(CraftovMapId mapId, RaidMode mode, RaidDifficulty difficulty) {

    private static MatchPool of(MatchTicket ticket) {
      return new MatchPool(ticket.mapId(), ticket.mode(), ticket.difficulty());
    }
  }

  /// One indivisible queue unit. Every member is created by one atomic
  /// queueParty call and therefore shares the same map, ruleset and team key.
  private record TicketGroup(List<MatchTicket> members) {

    private TicketGroup {
      members = List.copyOf(members);
      Preconditions.checkArgument(!members.isEmpty(), "ticket group must not be empty");
      MatchTicket first = members.getFirst();
      Preconditions.checkArgument(members.stream().allMatch(ticket ->
              ticket.teamKey().equals(first.teamKey())
                  && ticket.mapId().equals(first.mapId())
                  && ticket.mode() == first.mode()
                  && ticket.difficulty() == first.difficulty()),
          "ticket group members must share team key and matchmaking pool");
    }

    private MatchTicket first() {
      return members.getFirst();
    }

    private int size() {
      return members.size();
    }

    private boolean isStillQueuedIn(Map<UUID, MatchTicket> queued) {
      return members.stream().allMatch(ticket -> queued.get(ticket.playerId()) == ticket);
    }
  }
}
