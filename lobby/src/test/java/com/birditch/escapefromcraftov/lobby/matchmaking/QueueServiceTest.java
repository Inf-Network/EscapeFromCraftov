package com.birditch.escapefromcraftov.lobby.matchmaking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.matchmaking.MatchTicket;
import com.birditch.escapefromcraftov.api.network.AssignmentStore;
import com.birditch.escapefromcraftov.api.network.GameServerSnapshot;
import com.birditch.escapefromcraftov.api.network.InstanceDirectory;
import com.birditch.escapefromcraftov.api.network.InstanceSnapshot;
import com.birditch.escapefromcraftov.api.network.MatchAssignment;
import com.birditch.escapefromcraftov.api.network.MessageBroker;
import com.birditch.escapefromcraftov.api.network.MessageSubscription;
import com.birditch.escapefromcraftov.api.network.NetworkKeys;
import com.birditch.escapefromcraftov.api.network.ProvisionRequest;
import com.birditch.escapefromcraftov.api.network.ServerId;
import com.birditch.escapefromcraftov.api.settings.PlayerSettingsService;
import com.birditch.escapefromcraftov.api.settings.SettingKey;
import com.birditch.escapefromcraftov.api.testing.ManualScheduler;
import com.birditch.escapefromcraftov.api.testing.MutableTimeSource;
import com.birditch.escapefromcraftov.lobby.LobbyConfig;
import com.birditch.escapefromcraftov.lobby.LobbyDataGate;
import com.birditch.escapefromcraftov.redis.JsonCodec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("QueueService")
class QueueServiceTest {

  private static final CraftovMapId MAP = CraftovMapId.of("debug_zone");
  private static final ServerId SERVER = ServerId.of("game-01");
  private static final String PROXY = "craftov-1";
  private static final Duration TICK = Duration.ofSeconds(1);

  private final ManualScheduler scheduler = new ManualScheduler();
  private final MutableTimeSource timeSource = new MutableTimeSource(0);
  private final FakeInstanceDirectory directory = new FakeInstanceDirectory();
  private final FakeAssignmentStore assignments = new FakeAssignmentStore();
  private final FakeMessageBroker broker = new FakeMessageBroker();
  private final FakePlayerGateway players = new FakePlayerGateway();
  private final FakeSettingsService settings = new FakeSettingsService();
  private final LobbyDataGate dataGate = new LobbyDataGate();
  private final JsonCodec codec = new JsonCodec();
  private final QueueService service = new QueueService(new QueueDeps(
      directory, assignments, broker, players, scheduler, timeSource, settings,
      dataGate, new LobbyConfig(1, 2, 30, 60, false, true, 20, 5, MAP, Set.of(MAP)), codec,
      silentLogger()));

  @BeforeEach
  void startService() {
    service.start();
  }

  /// Advances the wall clock and the scheduler in lockstep by one queue tick.
  private void tick() {
    timeSource.advance(TICK);
    scheduler.advanceBy(TICK);
  }

  private void ticks(int count) {
    for (int i = 0; i < count; i++) {
      tick();
    }
  }

  private UUID onlinePlayer() {
    UUID playerId = readyPlayer();
    players.online.add(playerId);
    return playerId;
  }

  private UUID readyPlayer() {
    UUID playerId = UUID.randomUUID();
    dataGate.markReady(playerId);
    return playerId;
  }

  private static GameServerSnapshot server(InstanceSnapshot... instances) {
    return new GameServerSnapshot(SERVER, PROXY, 1L, 4, List.of(instances));
  }

  private static InstanceSnapshot waitingInstance(String gameId, int playerCount, int maxPlayers) {
    return new InstanceSnapshot(
        CraftovGameId.of(gameId), MAP, CraftovGameState.WAITING, playerCount, maxPlayers, true);
  }

  private static InstanceSnapshot waitingInstance(String gameId, int playerCount, int maxPlayers,
      RaidMode mode, RaidDifficulty difficulty) {
    return new InstanceSnapshot(CraftovGameId.of(gameId), MAP, CraftovGameState.WAITING,
        playerCount, maxPlayers, true, mode, difficulty);
  }

  private static Logger silentLogger() {
    Logger logger = Logger.getAnonymousLogger();
    logger.setUseParentHandlers(false);
    return logger;
  }

  @Nested
  @DisplayName("assignment")
  class Assignment {

    @Test
    @DisplayName("a queued player is stored, messaged and proxied once the write completes")
    void happyPath() {
      UUID playerId = onlinePlayer();
      directory.respondWith(List.of(server(waitingInstance("aaaaaa", 3, 12))));
      assertTrue(service.queue(playerId, MAP).isSuccess());

      tick();

      assertEquals(1, assignments.stored.size());
      MatchAssignment assignment = assignments.stored.getFirst();
      assertEquals(playerId, assignment.playerId());
      assertEquals(SERVER, assignment.serverId());
      assertEquals(PROXY, assignment.proxyName());
      assertEquals(CraftovGameId.of("aaaaaa"), assignment.gameId());
      assertEquals(MAP, assignment.mapId());
      assertEquals(playerId, assignment.teamKey());
      assertEquals(Duration.ofSeconds(60), assignments.ttls.getFirst());
      assertEquals(1, service.queueSize());

      assignments.results.getFirst().complete(null);

      assertEquals(0, service.queueSize());
      assertEquals(List.of(new FakePlayerGateway.Sent(
          playerId, "queue.match-found", Map.of("server", PROXY))), players.sent);
      assertEquals(List.of(new FakePlayerGateway.Connection(playerId, PROXY)),
          players.connections);
    }

    @Test
    @DisplayName("an explicit future-party key is carried from ticket into assignment")
    void carriesExplicitTeamKey() {
      UUID playerId = onlinePlayer();
      UUID teamKey = UUID.fromString("00000000-0000-0000-0000-000000000099");
      directory.respondWith(List.of(server(waitingInstance("aaaaaa", 1, 12))));

      MatchTicket ticket = service.queue(playerId, MAP, teamKey)
          .orElseThrow(IllegalStateException::new);
      tick();

      assertEquals(teamKey, ticket.teamKey());
      assertEquals(teamKey, assignments.stored.getFirst().teamKey());
    }

    @Test
    @DisplayName("one solo remains in Lobby until a compatible second solo forms a cohort")
    void soloWaitsForMinimumCohort() {
      UUID first = onlinePlayer();
      directory.respondWith(List.of(server(waitingInstance("aaaaaa", 0, 12))));
      assertTrue(service.queue(first, MAP).isSuccess());

      tick();

      assertTrue(assignments.stored.isEmpty());
      assertEquals(1, service.queueSize());
      assertTrue(players.connections.isEmpty());

      UUID second = onlinePlayer();
      assertTrue(service.queue(second, MAP).isSuccess());
      tick();

      assertEquals(2, assignments.stored.size());
      assignments.results.get(0).complete(null);
      assertTrue(players.connections.isEmpty());
      assignments.results.get(1).complete(null);
      assertEquals(0, service.queueSize());
      assertEquals(Set.of(first, second), players.connections.stream()
          .map(FakePlayerGateway.Connection::playerId).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    @DisplayName("in-flight reservations never make a later solo start-ready")
    void reservationsDoNotSatisfyMinimumPlayers() {
      UUID first = onlinePlayer();
      UUID second = onlinePlayer();
      directory.respondWith(List.of(server(waitingInstance("aaaaaa", 0, 3))));
      service.queue(first, MAP);
      service.queue(second, MAP);
      tick();
      assertEquals(2, assignments.stored.size());

      UUID third = onlinePlayer();
      service.queue(third, MAP);
      tick();

      assertEquals(2, assignments.stored.size());
      assertTrue(service.ticket(third).isPresent());
      assertTrue(players.connections.isEmpty());
    }

    @Test
    @DisplayName("parties from different rule pools never satisfy each other's minimum")
    void differentPoolsNeverFormOneCohort() {
      UUID normal = onlinePlayer();
      UUID confidential = onlinePlayer();
      directory.respondWith(List.of(server(
          waitingInstance("normal", 0, 12, RaidMode.NORMAL, RaidDifficulty.NORMAL),
          waitingInstance("secret", 0, 12,
              RaidMode.CONFIDENTIAL, RaidDifficulty.NORMAL))));
      service.queue(normal, MAP, RaidMode.NORMAL, RaidDifficulty.NORMAL, normal);
      service.queue(confidential, MAP, RaidMode.CONFIDENTIAL,
          RaidDifficulty.NORMAL, confidential);

      tick();

      assertTrue(assignments.stored.isEmpty());
      assertEquals(2, service.queueSize());
    }

    @Test
    @DisplayName("cohort packing favours the oldest compatible complete parties")
    void cohortPackingPreservesFifoWhenPossible() {
      UUID oldestSolo = onlinePlayer();
      UUID newerLeader = onlinePlayer();
      UUID newerMember = onlinePlayer();
      UUID newestSolo = onlinePlayer();
      UUID newerParty = UUID.randomUUID();
      directory.respondWith(List.of(server(waitingInstance("aaaaaa", 0, 2))));
      service.queue(oldestSolo, MAP);
      service.queueParty(List.of(newerLeader, newerMember), MAP,
          RaidMode.NORMAL, RaidDifficulty.NORMAL, newerParty);
      service.queue(newestSolo, MAP);

      tick();

      assertEquals(List.of(oldestSolo, newestSolo), assignments.stored.stream()
          .map(MatchAssignment::playerId).toList());
      assertTrue(service.ticket(newerLeader).isPresent());
      assertTrue(service.ticket(newerMember).isPresent());
    }

    @Test
    @DisplayName("a complete party is reserved and assigned as one indivisible group")
    void assignsWholePartyTogether() {
      UUID leader = onlinePlayer();
      UUID second = onlinePlayer();
      UUID third = onlinePlayer();
      UUID teamKey = UUID.randomUUID();
      directory.respondWith(List.of(server(waitingInstance(
          "secure", 0, 3, RaidMode.CONFIDENTIAL, RaidDifficulty.HARD))));

      var queued = service.queueParty(List.of(leader, second, third), MAP,
          RaidMode.CONFIDENTIAL, RaidDifficulty.HARD, teamKey);
      assertTrue(queued.isSuccess());
      tick();

      assertEquals(3, assignments.stored.size());
      assertTrue(assignments.stored.stream().allMatch(assignment ->
          assignment.gameId().equals(CraftovGameId.of("secure"))
              && assignment.teamKey().equals(teamKey)
              && assignment.mode() == RaidMode.CONFIDENTIAL
              && assignment.difficulty() == RaidDifficulty.HARD));
      assignments.results.forEach(result -> result.complete(null));
      assertEquals(0, service.queueSize());
      assertEquals(3, players.connections.size());
    }

    @Test
    @DisplayName("a party is not partially queued when any member is not ready")
    void partyQueueIsAtomic() {
      UUID ready = readyPlayer();
      UUID notReady = UUID.randomUUID();

      var result = service.queueParty(List.of(ready, notReady), MAP,
          RaidMode.NORMAL, RaidDifficulty.NORMAL, UUID.randomUUID());

      assertTrue(result.error().isPresent());
      assertEquals(0, service.queueSize());
    }

    @Test
    @DisplayName("partial Redis write failure cleans every pending key and retries the whole party")
    void partialStoreFailureCleansWholeParty() {
      UUID leader = onlinePlayer();
      UUID member = onlinePlayer();
      directory.respondWith(List.of(server(waitingInstance("aaaaaa", 0, 2))));
      service.queueParty(List.of(leader, member), MAP, RaidMode.NORMAL,
          RaidDifficulty.NORMAL, UUID.randomUUID());
      tick();

      assignments.results.get(0).complete(null);
      assignments.results.get(1).completeExceptionally(new RuntimeException("redis down"));

      assertEquals(Set.of(leader, member), assignments.removed);
      assertEquals(2, service.queueSize());
      assertTrue(players.connections.isEmpty());
    }

    @Test
    @DisplayName("a member going offline during writes cancels every assignment")
    void offlineDuringStoreCancelsWholeParty() {
      UUID leader = onlinePlayer();
      UUID member = onlinePlayer();
      directory.respondWith(List.of(server(waitingInstance("aaaaaa", 0, 2))));
      service.queueParty(List.of(leader, member), MAP, RaidMode.NORMAL,
          RaidDifficulty.NORMAL, UUID.randomUUID());
      tick();

      players.online.remove(member);
      assignments.results.forEach(result -> result.complete(null));

      assertEquals(Set.of(leader, member), assignments.removed);
      assertEquals(0, service.queueSize());
      assertTrue(players.connections.isEmpty());
    }

    @Test
    @DisplayName("one solo going offline during cohort writes cancels the whole cohort")
    void offlineSoloDuringStoreCancelsCrossPartyCohort() {
      UUID first = onlinePlayer();
      UUID second = onlinePlayer();
      directory.respondWith(List.of(server(waitingInstance("aaaaaa", 0, 2))));
      service.queue(first, MAP);
      service.queue(second, MAP);
      tick();

      players.online.remove(second);
      assignments.results.forEach(result -> result.complete(null));

      assertEquals(Set.of(first, second), assignments.removed);
      assertTrue(service.ticket(first).isPresent());
      assertTrue(service.ticket(second).isEmpty());
      assertEquals(1, service.queueSize());
      assertTrue(players.connections.isEmpty());
    }

    @Test
    @DisplayName("an offline player is dropped from the queue without an assignment")
    void offlinePlayerDropped() {
      UUID playerId = readyPlayer();
      directory.respondWith(List.of(server(waitingInstance("aaaaaa", 0, 12))));
      assertTrue(service.queue(playerId, MAP).isSuccess());

      tick();

      assertEquals(0, service.queueSize());
      assertTrue(assignments.stored.isEmpty());
      assertTrue(players.sent.isEmpty());
      assertTrue(players.connections.isEmpty());
    }

    @Test
    @DisplayName("a player cannot queue before PostgreSQL data is ready")
    void rejectsPlayerDataNotReady() {
      UUID playerId = UUID.randomUUID();

      assertTrue(service.queue(playerId, MAP).error().isPresent());
      assertEquals(0, service.queueSize());
    }
  }

  @Nested
  @DisplayName("pending write retry")
  class PendingWriteRetry {

    @Test
    @DisplayName("a failed write releases the reservation and retries the same ticket first")
    void storeFailureRetriesInFifoOrder() {
      UUID first = onlinePlayer();
      UUID second = onlinePlayer();
      directory.respondWith(List.of(server(waitingInstance("aaaaaa", 1, 2))));
      assertTrue(service.queue(first, MAP).isSuccess());
      assertTrue(service.queue(second, MAP).isSuccess());

      tick();
      assertEquals(1, assignments.stored.size());
      assertEquals(first, assignments.stored.getFirst().playerId());

      assignments.results.getFirst().completeExceptionally(new RuntimeException("redis down"));
      assertEquals(2, service.queueSize());

      tick();
      assertEquals(2, assignments.stored.size());
      assertEquals(first, assignments.stored.get(1).playerId());

      assignments.results.get(1).complete(null);
      assertEquals(1, service.queueSize());
      assertTrue(service.ticket(second).isPresent());
      assertEquals(List.of(new FakePlayerGateway.Connection(first, PROXY)),
          players.connections);
    }

    @Test
    @DisplayName("extra ticks during a slow write never double-assign the ticket")
    void slowStoreIsNotDoubleAssigned() {
      UUID playerId = onlinePlayer();
      directory.respondWith(List.of(server(waitingInstance("aaaaaa", 1, 12))));
      assertTrue(service.queue(playerId, MAP).isSuccess());

      tick();
      assertEquals(1, assignments.stored.size());

      ticks(3);
      assertEquals(1, assignments.stored.size());
      assertEquals(1, service.queueSize());
      assertTrue(players.sent.isEmpty());

      assignments.results.getFirst().complete(null);
      assertEquals(0, service.queueSize());
      assertEquals(List.of(new FakePlayerGateway.Connection(playerId, PROXY)),
          players.connections);
    }
  }

  @Nested
  @DisplayName("reservations")
  class Reservations {

    @Test
    @DisplayName("reservations from prior assignments block overfilling until their TTL lapses")
    void reservationsPreventOverfill() {
      UUID first = onlinePlayer();
      UUID second = onlinePlayer();
      UUID third = onlinePlayer();
      UUID fourth = onlinePlayer();
      directory.respondWith(List.of(server(waitingInstance("aaaaaa", 0, 2))));
      assertTrue(service.queue(first, MAP).isSuccess());
      assertTrue(service.queue(second, MAP).isSuccess());
      assertTrue(service.queue(third, MAP).isSuccess());
      assertTrue(service.queue(fourth, MAP).isSuccess());

      tick();
      assertEquals(2, assignments.stored.size());
      assignments.results.get(0).complete(null);
      assignments.results.get(1).complete(null);
      assertEquals(2, service.queueSize());

      // The directory snapshot still advertises zero players; only the two
      // reservations (TTL 30s, taken at t=1s) keep the second cohort out.
      ticks(25);
      assertEquals(2, assignments.stored.size());
      assertTrue(service.ticket(third).isPresent());
      assertTrue(service.ticket(fourth).isPresent());

      // Past t=31s the reservations lapse and the second complete cohort is assigned.
      ticks(5);
      assertEquals(4, assignments.stored.size());
      assertEquals(third, assignments.stored.get(2).playerId());
      assertEquals(fourth, assignments.stored.get(3).playerId());
    }
  }

  @Nested
  @DisplayName("starvation")
  class Starvation {

    @Test
    @DisplayName("a starved queue publishes one provision hint per cooldown window")
    void provisionHintOncePerCooldown() {
      UUID playerId = onlinePlayer();
      directory.respondWith(List.of(server()));
      assertTrue(service.queue(playerId, MAP).isSuccess());

      ticks(10);
      assertEquals(1, broker.published.size());
      assertEquals(NetworkKeys.PROVISION_CHANNEL, broker.published.getFirst().channel());
      ProvisionRequest request =
          codec.fromJson(broker.published.getFirst().payload(), ProvisionRequest.class);
      assertEquals(SERVER, request.serverId());
      assertEquals(MAP, request.mapId());
      assertEquals(2, request.requiredSlots());

      tick();
      assertEquals(2, broker.published.size());
    }

    @Test
    @DisplayName("a party provision hint carries the slots required to keep it indivisible")
    void provisionHintCarriesWholePartySize() {
      UUID first = onlinePlayer();
      UUID second = onlinePlayer();
      UUID third = onlinePlayer();
      directory.respondWith(List.of(server(waitingInstance("small", 0, 2))));
      service.queueParty(List.of(first, second, third), MAP, RaidMode.NORMAL,
          RaidDifficulty.NORMAL, UUID.randomUUID());

      tick();

      ProvisionRequest request = codec.fromJson(
          broker.published.getFirst().payload(), ProvisionRequest.class);
      assertEquals(3, request.requiredSlots());
      assertTrue(assignments.stored.isEmpty());
    }

    @Test
    @DisplayName("queue.waiting is sent and rate-limited while the setting is on")
    void waitingMessageSentWhenEnabled() {
      UUID playerId = onlinePlayer();
      directory.respondWith(List.of(server()));
      assertTrue(service.queue(playerId, MAP).isSuccess());

      ticks(3);

      assertEquals(List.of(new FakePlayerGateway.Sent(playerId, "queue.waiting", Map.of())),
          players.sent);
    }

    @Test
    @DisplayName("queue.waiting respects QUEUE_STATUS_MESSAGES=false")
    void waitingMessageRespectsSetting() {
      UUID playerId = onlinePlayer();
      settings.put(playerId, SettingKey.QUEUE_STATUS_MESSAGES, false);
      directory.respondWith(List.of(server()));
      assertTrue(service.queue(playerId, MAP).isSuccess());

      ticks(3);

      assertTrue(players.sent.isEmpty());
    }
  }

  private static final class FakeInstanceDirectory implements InstanceDirectory {

    private List<GameServerSnapshot> servers = List.of();

    void respondWith(List<GameServerSnapshot> servers) {
      this.servers = List.copyOf(servers);
    }

    @Override
    public CompletableFuture<Void> publish(GameServerSnapshot snapshot, Duration ttl) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<GameServerSnapshot>> activeServers() {
      return CompletableFuture.completedFuture(servers);
    }
  }

  private static final class FakeAssignmentStore implements AssignmentStore {

    private final List<MatchAssignment> stored = new ArrayList<>();
    private final List<Duration> ttls = new ArrayList<>();
    private final List<CompletableFuture<Void>> results = new ArrayList<>();
    private final Set<UUID> removed = new HashSet<>();

    @Override
    public CompletableFuture<Void> storePending(MatchAssignment assignment, Duration ttl) {
      stored.add(assignment);
      ttls.add(ttl);
      CompletableFuture<Void> result = new CompletableFuture<>();
      results.add(result);
      return result;
    }

    @Override
    public CompletableFuture<Optional<MatchAssignment>> consumePending(UUID playerId) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<Void> removePending(UUID playerId) {
      removed.add(playerId);
      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class FakeMessageBroker implements MessageBroker {

    record Published(String channel, String payload) {}

    private final List<Published> published = new ArrayList<>();

    @Override
    public CompletableFuture<Void> publish(String channel, String payload) {
      published.add(new Published(channel, payload));
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public MessageSubscription subscribe(String channel, Consumer<String> handler) {
      throw new UnsupportedOperationException("QueueService never subscribes");
    }

    @Override
    public void close() {
      // Nothing to release: this fake holds no broker resources.
    }
  }

  private static final class FakePlayerGateway implements PlayerGateway {

    record Sent(UUID playerId, String messageKey, Map<String, String> placeholders) {}

    record Connection(UUID playerId, String proxyName) {}

    private final Set<UUID> online = new HashSet<>();
    private final List<Sent> sent = new ArrayList<>();
    private final List<Connection> connections = new ArrayList<>();

    @Override
    public boolean isOnline(UUID playerId) {
      return online.contains(playerId);
    }

    @Override
    public void sendMessage(UUID playerId, String messageKey, Map<String, String> placeholders) {
      if (online.contains(playerId)) {
        sent.add(new Sent(playerId, messageKey, Map.copyOf(placeholders)));
      }
    }

    @Override
    public void connectToServer(UUID playerId, String proxyName) {
      if (online.contains(playerId)) {
        connections.add(new Connection(playerId, proxyName));
      }
    }
  }

  private static final class FakeSettingsService implements PlayerSettingsService {

    private final Map<UUID, Map<SettingKey, Boolean>> values = new HashMap<>();

    void put(UUID playerId, SettingKey key, boolean value) {
      values.computeIfAbsent(playerId, _ -> new HashMap<>()).put(key, value);
    }

    @Override
    public CompletableFuture<Void> load(UUID playerId) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean get(UUID playerId, SettingKey key) {
      return values.getOrDefault(playerId, Map.of()).getOrDefault(key, key.defaultValue());
    }

    @Override
    public CompletableFuture<Void> set(UUID playerId, SettingKey key, boolean value) {
      put(playerId, key, value);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void unload(UUID playerId) {
      values.remove(playerId);
    }
  }
}
