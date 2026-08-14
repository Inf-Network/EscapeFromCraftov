package com.birditch.escapefromcraftov.lobby.matchmaking;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import com.birditch.escapefromcraftov.api.matchmaking.MatchTicket;
import com.birditch.escapefromcraftov.api.matchmaking.MatchmakingFailures;
import com.birditch.escapefromcraftov.api.matchmaking.MatchmakingService;
import com.birditch.escapefromcraftov.api.matchmaking.SquadFillMode;
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
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.jspecify.annotations.Nullable;

/// FIFO matchmaking queue driven by a main-thread timer.
///
/// Every tick reads the Redis instance directory off-thread, hops back to the
/// main thread and groups complete parties from the same matchmaking pool into
/// a cohort. A cohort is only assigned when it brings one `WAITING` instance to
/// the configured minimum player count. Every pending key in the cohort must be
/// persisted before anybody is proxied; a failed persist keeps the online
/// parties queued for the next tick. While those writes are in flight the
/// tickets are marked and skipped, so a slow write is never double-assigned.
/// Slot reservations with a TTL bridge the gap between two directory snapshots
/// so one instance is never oversold.
///
/// Main-thread confined; Redis failures degrade to rate-limited warnings and
/// matchmaking resumes as soon as Redis returns.
public final class QueueService implements MatchmakingService {

  private static final int AUTO_FILL_SQUAD_SIZE = 3;
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
    return queue(playerId, mapId, mode, difficulty, teamKey, null);
  }

  @Override
  public Result<MatchTicket> queue(UUID playerId, CraftovMapId mapId, RaidMode mode,
      RaidDifficulty difficulty, UUID teamKey, @Nullable RaidTierId tierId) {
    return queue(playerId, mapId, mode, difficulty, teamKey, tierId, SquadFillMode.NO_FILL);
  }

  @Override
  public Result<MatchTicket> queue(UUID playerId, CraftovMapId mapId, RaidMode mode,
      RaidDifficulty difficulty, UUID teamKey, @Nullable RaidTierId tierId,
      SquadFillMode fillMode) {
    Result<List<MatchTicket>> result = queueParty(
        List.of(playerId), mapId, mode, difficulty, teamKey, tierId, fillMode);
    return result.map(List::getFirst);
  }

  @Override
  public Result<List<MatchTicket>> queueParty(List<UUID> playerIds, CraftovMapId mapId,
      RaidMode mode, RaidDifficulty difficulty, UUID teamKey) {
    return queueParty(playerIds, mapId, mode, difficulty, teamKey, null);
  }

  @Override
  public Result<List<MatchTicket>> queueParty(List<UUID> playerIds, CraftovMapId mapId,
      RaidMode mode, RaidDifficulty difficulty, UUID teamKey,
      @Nullable RaidTierId tierId) {
    return queueParty(playerIds, mapId, mode, difficulty, teamKey, tierId,
        SquadFillMode.NO_FILL);
  }

  @Override
  public Result<List<MatchTicket>> queueParty(List<UUID> playerIds, CraftovMapId mapId,
      RaidMode mode, RaidDifficulty difficulty, UUID teamKey,
      @Nullable RaidTierId tierId, SquadFillMode fillMode) {
    checkMainThread();
    Preconditions.checkNotNull(playerIds, "playerIds");
    Preconditions.checkNotNull(mapId, "mapId");
    Preconditions.checkNotNull(mode, "mode");
    Preconditions.checkNotNull(difficulty, "difficulty");
    Preconditions.checkNotNull(teamKey, "teamKey");
    Preconditions.checkNotNull(fillMode, "fillMode");
    List<UUID> members = playerIds.stream().distinct().toList();
    if (members.size() != playerIds.size() || members.isEmpty() || members.size() > 3) {
      return Result.fail(MatchmakingFailures.PARTY_SIZE_INVALID);
    }
    if (members.stream().anyMatch(playerId -> !deps.dataGate().isReady(playerId))) {
      return Result.fail(MatchmakingFailures.PLAYER_DATA_NOT_READY);
    }
    if (members.stream().anyMatch(deps.dataGate()::isMatchTransferLocked)) {
      return Result.fail(MatchmakingFailures.ALREADY_QUEUED);
    }
    if (members.stream().anyMatch(tickets::containsKey)
        || members.stream().anyMatch(inFlight::contains)
        || tickets.values().stream().anyMatch(ticket -> ticket.teamKey().equals(teamKey))) {
      return Result.fail(MatchmakingFailures.ALREADY_QUEUED);
    }
    // A shared or explicit team key denotes an authored party. Parties are
    // atomic and never absorb strangers, even if a stale caller requests fill.
    boolean explicitParty = members.size() > 1 || !teamKey.equals(members.getFirst());
    SquadFillMode effectiveFill = explicitParty ? SquadFillMode.NO_FILL : fillMode;
    var queuedAt = deps.timeSource().now();
    List<MatchTicket> created = members.stream()
        .map(playerId -> new MatchTicket(
            playerId, mapId, queuedAt, teamKey, mode, difficulty, tierId, effectiveFill))
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
    List<TicketGroup> groups = ticketGroups();
    for (TicketGroup group : groups) {
      if (group.isStillQueuedIn(tickets) && group.members().stream()
          .map(MatchTicket::playerId).anyMatch(playerId -> !deps.players().isOnline(playerId))) {
        dequeueTeam(group.first().playerId());
      }
    }
    Map<MatchPool, List<TicketGroup>> groupsByPool = new LinkedHashMap<>();
    for (TicketGroup group : groups) {
      if (group.isStillQueuedIn(tickets)) {
        groupsByPool.computeIfAbsent(MatchPool.of(group.first()), _ -> new ArrayList<>())
            .add(group);
      }
    }
    Map<MatchPool, Integer> starvingPools = new LinkedHashMap<>();
    groupsByPool.forEach((pool, poolGroups) ->
        processPool(servers, pool, poolGroups, starvingPools));
    starvingPools.forEach((pool, requiredSlots) ->
        publishProvisionHint(servers, pool, requiredSlots));
  }

  private void processPool(List<GameServerSnapshot> servers, MatchPool pool,
      List<TicketGroup> poolGroups, Map<MatchPool, Integer> starvingPools) {
    while (true) {
      List<TicketGroup> available = poolGroups.stream()
          .filter(group -> group.isStillQueuedIn(tickets))
          .filter(group -> group.members().stream().map(MatchTicket::playerId)
              .noneMatch(inFlight::contains))
          .toList();
      if (available.isEmpty()) {
        return;
      }
      Optional<CohortPlan> plan = planCohort(servers, pool, available);
      if (plan.isPresent()) {
        assign(plan.get().cohort(), plan.get().candidate());
        continue;
      }
      available.stream().flatMap(group -> group.members().stream())
          .forEach(ticket -> notifyWaiting(ticket.playerId()));
      int requiredSlots = Math.max(
          deps.config().queueMinimumPlayers(),
          available.stream().mapToInt(TicketGroup::size).max().orElse(1));
      if (InstancePicker.pick(servers, pool.mapId(), pool.mode(), pool.difficulty(),
          pool.tierId(), requiredSlots, reservationCounts()).isEmpty()) {
        starvingPools.merge(pool, requiredSlots, Math::max);
      }
      return;
    }
  }

  /// Finds complete parties that can bring one joinable instance to the
  /// minimum player count. The smallest viable seed is selected first, then
  /// the same cohort absorbs additional FIFO parties while capacity remains.
  private Optional<CohortPlan> planCohort(List<GameServerSnapshot> servers,
      MatchPool pool, List<TicketGroup> groups) {
    int queuedSlots = groups.stream().mapToInt(TicketGroup::size).sum();
    int largestInstance = servers.stream().flatMap(server -> server.instances().stream())
        .mapToInt(instance -> instance.maxPlayers()).max().orElse(0);
    int maximumSeedSize = Math.min(queuedSlots, largestInstance);
    if (maximumSeedSize == 0) {
      return Optional.empty();
    }
    Map<Integer, List<TicketGroup>> choices = cohortChoices(groups, maximumSeedSize);
    Map<String, Integer> reserved = reservationCounts();
    for (int size = 1; size <= maximumSeedSize; size++) {
      List<TicketGroup> seed = choices.get(size);
      if (seed == null) {
        continue;
      }
      Optional<InstancePicker.Candidate> picked = InstancePicker.pick(
          serversMeetingMinimum(servers, size), pool.mapId(), pool.mode(), pool.difficulty(),
          pool.tierId(), size, reserved);
      if (picked.isEmpty()) {
        continue;
      }
      InstancePicker.Candidate candidate = picked.get();
      int load = candidate.instance().playerCount() + reserved.getOrDefault(
          InstancePicker.reservationKey(
              candidate.server().serverId(), candidate.instance().gameId(),
              candidate.instance().tierId()), 0);
      List<TicketGroup> cohortGroups = new ArrayList<>(seed);
      int remaining = candidate.instance().maxPlayers() - load - size;
      for (TicketGroup group : groups) {
        if (!cohortGroups.contains(group) && group.size() <= remaining) {
          cohortGroups.add(group);
          remaining -= group.size();
        }
      }
      return Optional.of(new CohortPlan(new TicketCohort(cohortGroups), candidate));
    }
    return Optional.empty();
  }

  /// Reservations protect capacity but cannot satisfy the start threshold:
  /// an in-flight cohort may still fail. Only players already present in the
  /// directory snapshot plus this complete cohort are counted as start-ready.
  private List<GameServerSnapshot> serversMeetingMinimum(
      List<GameServerSnapshot> servers, int cohortSize) {
    return servers.stream().map(server -> new GameServerSnapshot(
        server.serverId(), server.proxyName(), server.updatedAtMillis(), server.maxInstances(),
        server.instances().stream().filter(instance ->
            instance.playerCount() + cohortSize >= deps.config().queueMinimumPlayers()).toList(),
        server.capabilities(), server.actionTierCatalog()))
        .toList();
  }

  /// Dynamic programming over small party sizes. For each exact size the
  /// combination containing the earliest possible queue groups is retained.
  private Map<Integer, List<TicketGroup>> cohortChoices(
      List<TicketGroup> groups, int maximumSize) {
    Map<Integer, List<TicketGroup>> choices = new LinkedHashMap<>();
    choices.put(0, List.of());
    for (TicketGroup group : groups) {
      List<Map.Entry<Integer, List<TicketGroup>>> previous =
          new ArrayList<>(choices.entrySet());
      for (Map.Entry<Integer, List<TicketGroup>> entry : previous) {
        int combinedSize = entry.getKey() + group.size();
        if (combinedSize > maximumSize) {
          continue;
        }
        List<TicketGroup> combined = new ArrayList<>(entry.getValue());
        combined.add(group);
        List<TicketGroup> existing = choices.get(combinedSize);
        if (existing == null || isEarlierCombination(combined, existing, groups)) {
          choices.put(combinedSize, List.copyOf(combined));
        }
      }
    }
    return choices;
  }

  private boolean isEarlierCombination(List<TicketGroup> candidate,
      List<TicketGroup> existing, List<TicketGroup> queueOrder) {
    for (TicketGroup group : queueOrder) {
      boolean candidateContains = candidate.contains(group);
      boolean existingContains = existing.contains(group);
      if (candidateContains != existingContains) {
        return candidateContains;
      }
    }
    return false;
  }

  private List<TicketGroup> ticketGroups() {
    Map<UUID, List<MatchTicket>> byTeam = new LinkedHashMap<>();
    tickets.values().forEach(ticket ->
        byTeam.computeIfAbsent(ticket.teamKey(), _ -> new ArrayList<>()).add(ticket));
    return byTeam.values().stream().map(TicketGroup::new).toList();
  }

  private void assign(TicketCohort cohort, InstancePicker.Candidate candidate) {
    String reservationKey = InstancePicker.reservationKey(
        candidate.server().serverId(), candidate.instance().gameId(),
        candidate.instance().tierId());
    List<MatchTicket> members = cohort.members();
    List<MatchAssignment> cohortAssignments = new ArrayList<>(members.size());
    UUID fillTeamKey = null;
    int fillTeamMembers = AUTO_FILL_SQUAD_SIZE;
    for (MatchTicket ticket : members) {
      UUID assignmentTeamKey = ticket.teamKey();
      if (ticket.fillMode() == SquadFillMode.AUTO_FILL) {
        if (fillTeamMembers == AUTO_FILL_SQUAD_SIZE) {
          fillTeamKey = ticket.playerId();
          fillTeamMembers = 0;
        }
        assignmentTeamKey = Preconditions.checkNotNull(fillTeamKey, "fillTeamKey");
        fillTeamMembers++;
      }
      cohortAssignments.add(new MatchAssignment(
            ticket.playerId(),
            candidate.server().serverId(),
            candidate.server().proxyName(),
            candidate.instance().gameId(),
            candidate.instance().mapId(),
            assignmentTeamKey,
            ticket.mode(),
            ticket.difficulty(),
            ticket.tierId()));
    }
    long expiresAt = deps.timeSource().currentMillis()
        + Duration.ofSeconds(deps.config().reservationTtlSeconds()).toMillis();
    List<Long> instanceReservations =
        reservations.computeIfAbsent(reservationKey, _ -> new ArrayList<>());
    for (int i = 0; i < cohort.size(); i++) instanceReservations.add(expiresAt);
    cohortAssignments.forEach(assignment -> inFlight.add(assignment.playerId()));
    Duration assignmentTtl = Duration.ofSeconds(deps.config().assignmentTtlSeconds());
    CompletableFuture<?>[] writes = cohortAssignments.stream()
        .map(assignment -> deps.assignments().storePending(assignment, assignmentTtl))
        .toArray(CompletableFuture[]::new);
    CompletableFuture.allOf(writes).whenComplete((_, storeError) ->
        deps.scheduler().runSync(() -> afterStore(
            cohort, cohortAssignments, reservationKey, expiresAt, storeError)));
  }

  /// Runs on the main thread after the pending key write and clears the
  /// in-flight marker. Failure releases the reservation and keeps the ticket
  /// so the next tick retries; success removes the ticket and proxies the
  /// player.
  private void afterStore(TicketCohort cohort, List<MatchAssignment> assignments,
      String reservationKey, long expiresAt,
      @Nullable Throwable storeError) {
    assignments.forEach(assignment -> inFlight.remove(assignment.playerId()));
    if (storeError != null) {
      releaseReservations(reservationKey, expiresAt, assignments.size());
      removePendingBestEffort(assignments);
      removeOfflineGroups(cohort);
      if (redisWarnings.tryUse("store")) {
        deps.logger().log(Level.WARNING,
            "写入匹配分配到 Redis 失败,在线队伍完整保留在队列中等待下一轮。", storeError);
      }
      return;
    }
    // A leave/offline action may have removed the group while Redis writes
    // were in flight. Delete every pending key and never proxy a partial cohort.
    if (!cohort.isStillQueuedIn(tickets) || assignments.stream()
        .map(MatchAssignment::playerId).anyMatch(playerId -> !deps.players().isOnline(playerId))) {
      releaseReservations(reservationKey, expiresAt, assignments.size());
      removePendingBestEffort(assignments);
      removeOfflineGroups(cohort);
      return;
    }
    List<UUID> transferringPlayers = assignments.stream()
        .map(MatchAssignment::playerId).toList();
    long transferGeneration = deps.dataGate().beginMatchTransfer(transferringPlayers);
    deps.scheduler().runLater(
        Duration.ofSeconds(deps.config().assignmentTtlSeconds()),
        () -> deps.dataGate().finishMatchTransfer(transferringPlayers, transferGeneration));
    assignments.forEach(assignment -> tickets.remove(assignment.playerId()));
    assignments.forEach(assignment -> {
      deps.players().sendMessage(assignment.playerId(), "queue.match-found",
          Map.of("server", assignment.proxyName()));
      deps.players().connectToServer(assignment.playerId(), assignment.proxyName());
    });
  }

  private void removeOfflineGroups(TicketCohort cohort) {
    for (TicketGroup group : cohort.groups()) {
      boolean offline = group.members().stream().map(MatchTicket::playerId)
          .anyMatch(playerId -> !deps.players().isOnline(playerId));
      if (offline && group.isStillQueuedIn(tickets)) {
        dequeueTeam(group.first().playerId());
      }
    }
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
        .filter(server -> pool.tierId() == null || supportsTier(server, pool))
        .min(Comparator.comparingInt((GameServerSnapshot server) -> server.instances().size())
            .thenComparing(server -> server.serverId().value()));
    if (target.isEmpty()) {
      return;
    }
    ServerId serverId = target.get().serverId();
    String cooldownKey = serverId.value() + ":" + pool.mapId().value()
        + ":" + pool.mode().id() + ":" + pool.difficulty().id()
        + (pool.tierId() == null ? ":tier=null" : ":tier=" + pool.tierId().value());
    if (!provisionHints.tryUse(cooldownKey)) {
      return;
    }
    String payload = deps.codec().toJson(new ProvisionRequest(
        serverId, pool.mapId(), pool.mode(), pool.difficulty(), requiredSlots, pool.tierId()));
    deps.broker().publish(NetworkKeys.PROVISION_CHANNEL, payload)
        .whenComplete((_, publishError) -> {
          if (publishError != null) {
            deps.logger().log(Level.FINE, "发布实例扩容提示失败。", publishError);
          }
        });
  }

  private static boolean supportsTier(GameServerSnapshot server, MatchPool pool) {
    RaidTierId tierId = Preconditions.checkNotNull(pool.tierId(), "tierId");
    return InstancePicker.advertisesTier(server, pool.mapId(), tierId);
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

  private record MatchPool(CraftovMapId mapId, RaidMode mode, RaidDifficulty difficulty,
                           @Nullable RaidTierId tierId) {

    private static MatchPool of(MatchTicket ticket) {
      return new MatchPool(ticket.mapId(), ticket.mode(), ticket.difficulty(), ticket.tierId());
    }
  }

  private record CohortPlan(
      TicketCohort cohort, InstancePicker.Candidate candidate) {}

  /// One atomic cross-party assignment batch. Its groups always share one
  /// matchmaking pool, while every group remains an indivisible party.
  private record TicketCohort(List<TicketGroup> groups) {

    private TicketCohort {
      groups = List.copyOf(groups);
      Preconditions.checkArgument(!groups.isEmpty(), "ticket cohort must not be empty");
      MatchTicket first = groups.getFirst().first();
      Preconditions.checkArgument(groups.stream().map(TicketGroup::first).allMatch(ticket ->
              ticket.mapId().equals(first.mapId())
                  && ticket.mode() == first.mode()
                  && ticket.difficulty() == first.difficulty()
                  && Objects.equals(ticket.tierId(), first.tierId())),
          "ticket cohort groups must share one matchmaking pool");
    }

    private List<MatchTicket> members() {
      return groups.stream().flatMap(group -> group.members().stream()).toList();
    }

    private int size() {
      return groups.stream().mapToInt(TicketGroup::size).sum();
    }

    private boolean isStillQueuedIn(Map<UUID, MatchTicket> queued) {
      return groups.stream().allMatch(group -> group.isStillQueuedIn(queued));
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
                  && ticket.difficulty() == first.difficulty()
                  && Objects.equals(ticket.tierId(), first.tierId())
                  && ticket.fillMode() == first.fillMode()),
          "ticket group members must share team key and matchmaking pool");
      Preconditions.checkArgument(first.fillMode() == SquadFillMode.NO_FILL
              || members.size() == 1,
          "auto-fill ticket groups must contain one solo player");
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
