package com.birditch.escapefromcraftov.game.plugin.poi;

import com.birditch.escapefromcraftov.api.combat.CombatService;
import com.birditch.escapefromcraftov.api.combat.PlayerCombatState;
import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovStartedEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovPoi;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * One globally shared POI hotspot per running raid, with completion isolated by team.
 * A single bounded round-robin task samples real player locations; no movement event,
 * chunk load, entity, pathfinding or persistent write is involved.
 */
public final class PoiReconRuntime implements PoiReconLookup, AutoCloseable {

  static final int MAX_PLAYER_CHECKS_PER_TICK = 128;
  static final Duration SCAN_INTERVAL = Duration.ofMillis(250);
  static final int MAXIMUM_DISPLAY_NAME_CODE_POINTS =
      PoiReconLookup.View.MAXIMUM_DISPLAY_NAME_CODE_POINTS;

  private final CraftovGameService games;
  private final InstanceWorldIndex worlds;
  private final CraftovScheduler scheduler;
  private final Predicate<UUID> combatAlive;
  private final PlayerGateway players;
  private final Messages messages;
  private final Logger logger;
  private final Map<CraftovGameId, GameTarget> targets = new HashMap<>();
  private final Set<CompletionKey> completed = new HashSet<>();
  private final List<ScanCandidate> scanOrder = new ArrayList<>();
  private int scanCursor;
  private @Nullable ScheduledTask scanTask;
  private boolean closed;

  public PoiReconRuntime(CraftovGameService games, InstanceWorldIndex worlds,
      CraftovScheduler scheduler, CombatService combat, Messages messages, Logger logger) {
    this(games, worlds, scheduler, alivePredicate(combat),
        new BukkitPlayerGateway(), messages, logger);
  }

  PoiReconRuntime(CraftovGameService games, InstanceWorldIndex worlds,
      CraftovScheduler scheduler, Predicate<UUID> combatAlive, PlayerGateway players,
      Messages messages, Logger logger) {
    this.games = Preconditions.checkNotNull(games, "games");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.combatAlive = Preconditions.checkNotNull(combatAlive, "combatAlive");
    this.players = Preconditions.checkNotNull(players, "players");
    this.messages = Preconditions.checkNotNull(messages, "messages");
    this.logger = Preconditions.checkNotNull(logger, "logger");
  }

  /** Starts the one fixed-rate scanner; repeated calls never create another task. */
  public void start() {
    if (closed || scanTask != null) return;
    scanTask = scheduler.runTimer(Duration.ZERO, SCAN_INTERVAL, this::scanSafely);
  }

  @Subscribe
  public void onCraftovStarted(CraftovStartedEvent event) {
    if (closed || targets.containsKey(event.gameId())) return;
    CraftovGame game = games.find(event.gameId())
        .filter(candidate -> candidate.state() == CraftovGameState.RUNNING).orElse(null);
    if (game == null) return;
    CraftovPoi target = PoiReconPolicy.select(game.id(), game.mapRevision(), game.pois())
        .orElse(null);
    if (target == null) return;
    List<UUID> participants = game.players().stream().map(CraftovPlayer::playerId).toList();
    GameTarget created = new GameTarget(target, Set.copyOf(participants));
    targets.put(game.id(), created);
    participants.forEach(playerId -> scanOrder.add(new ScanCandidate(game.id(), playerId)));
  }

  @Subscribe
  public void onCraftovEnded(CraftovEndedEvent event) {
    clear(event.gameId());
  }

  @Override
  public Optional<View> view(CraftovGameId gameId, UUID playerId) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(playerId, "playerId");
    if (closed) return Optional.empty();
    try {
      GameTarget target = targets.get(gameId);
      CraftovGame game = games.find(gameId).orElse(null);
      EligibleMember eligible = eligible(game, target, playerId, true);
      if (eligible == null) return Optional.empty();
      PoiReconPolicy.Measurement measurement = PoiReconPolicy.measure(
          target.poi(), eligible.presence().x(), eligible.presence().z()).orElse(null);
      if (measurement == null) return Optional.empty();
      return Optional.of(new View(target.poi().id(), truncate(target.poi().displayName()),
          target.poi().tier(), completed.contains(new CompletionKey(gameId, eligible.teamKey())),
          measurement.direction().displayName(), measurement.distanceMeters(),
          measurement.distanceCapped()));
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  int activeGameCount() {
    return targets.size();
  }

  int completedTeamCount() {
    return completed.size();
  }

  private void scanSafely() {
    if (closed || scanOrder.isEmpty()) return;
    int checks = Math.min(MAX_PLAYER_CHECKS_PER_TICK, scanOrder.size());
    for (int checked = 0; checked < checks; checked++) {
      if (scanOrder.isEmpty()) return;
      int index = Math.floorMod(scanCursor++, scanOrder.size());
      ScanCandidate candidate = scanOrder.get(index);
      try {
        scan(candidate);
      } catch (RuntimeException failure) {
        logger.log(Level.WARNING, "POI 侦察扫描失败，已跳过本名玩家: "
            + candidate.playerId(), failure);
      }
    }
    if (scanCursor > 1_000_000_000) scanCursor %= scanOrder.size();
  }

  private void scan(ScanCandidate candidate) {
    GameTarget target = targets.get(candidate.gameId());
    CraftovGame game = games.find(candidate.gameId()).orElse(null);
    EligibleMember eligible = eligible(game, target, candidate.playerId(), true);
    if (eligible == null) return;
    CompletionKey key = new CompletionKey(candidate.gameId(), eligible.teamKey());
    if (completed.contains(key)) return;
    Presence presence = eligible.presence();
    if (!PoiReconPolicy.reached(target.poi(), presence.x(), presence.y(), presence.z(),
        presence.onGround())) return;
    // Main-thread first-wins: commit before any best-effort rendering or message send.
    if (!completed.add(key)) return;
    notifyTeam(game, target, eligible.teamKey());
  }

  private void notifyTeam(CraftovGame game, GameTarget target, TeamKey completedTeam) {
    Component message;
    String displayName = truncate(target.poi().displayName());
    try {
      message = messages.contains("poi.recon-completed")
          ? messages.raw("poi.recon-completed", Placeholder.unparsed("poi", displayName))
          : fallbackCompletionMessage(displayName);
    } catch (RuntimeException failure) {
      logger.log(Level.WARNING, "POI 侦察完成提示渲染失败，已使用内置中文提示", failure);
      message = fallbackCompletionMessage(displayName);
    }
    for (CraftovPlayer participant : game.players()) {
      if (!teamKey(participant).equals(completedTeam)) continue;
      EligibleMember eligible = eligible(game, target, participant.playerId(), true);
      if (eligible == null) continue;
      try {
        players.send(participant.playerId(), message);
      } catch (RuntimeException failure) {
        logNotificationFailure(participant.playerId(), failure);
      }
    }
  }

  private void logNotificationFailure(UUID playerId, RuntimeException failure) {
    logger.log(Level.WARNING, "POI 侦察完成提示发送失败，完成状态已保留: " + playerId, failure);
  }

  private static Component fallbackCompletionMessage(String displayName) {
    return Component.text("队伍侦察完成: ", NamedTextColor.GREEN)
        .append(Component.text(displayName, NamedTextColor.YELLOW));
  }

  private @Nullable EligibleMember eligible(@Nullable CraftovGame game,
      @Nullable GameTarget target, UUID playerId, boolean requireAlive) {
    if (game == null || target == null || game.state() != CraftovGameState.RUNNING) return null;
    if (!target.participants().contains(playerId)) return null;
    CraftovPlayer participant = game.players().stream()
        .filter(candidate -> candidate.playerId().equals(playerId)).findFirst().orElse(null);
    if (participant == null || participant.extracted()) return null;
    if (requireAlive) {
      try {
        if (!combatAlive.test(playerId)) return null;
      } catch (RuntimeException ignored) {
        return null;
      }
    }
    Presence presence;
    try {
      presence = players.snapshot(playerId).orElse(null);
    } catch (RuntimeException ignored) {
      return null;
    }
    if (presence == null || !presence.physicallyEligible()
        || !exactWorld(game.id(), presence.worldName())) return null;
    return new EligibleMember(participant, teamKey(participant), presence);
  }

  private boolean exactWorld(CraftovGameId gameId, String actualWorld) {
    return worlds.worldOf(gameId).filter(actualWorld::equals).isPresent()
        && worlds.gameByWorld(actualWorld).filter(gameId::equals).isPresent();
  }

  private void clear(CraftovGameId gameId) {
    if (targets.remove(gameId) == null) return;
    completed.removeIf(key -> key.gameId().equals(gameId));
    scanOrder.removeIf(candidate -> candidate.gameId().equals(gameId));
    scanCursor = scanOrder.isEmpty() ? 0 : Math.floorMod(scanCursor, scanOrder.size());
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    ScheduledTask task = scanTask;
    scanTask = null;
    if (task != null) task.cancel();
    targets.clear();
    completed.clear();
    scanOrder.clear();
    scanCursor = 0;
  }

  private static TeamKey teamKey(CraftovPlayer player) {
    return player.teamId().isPresent()
        ? TeamKey.team(player.teamId().getAsInt()) : TeamKey.solo(player.playerId());
  }

  private static String truncate(String value) {
    return PoiReconLookup.View.normalizedDisplayName(value);
  }

  private static Predicate<UUID> alivePredicate(CombatService combat) {
    CombatService checked = Preconditions.checkNotNull(combat, "combat");
    return playerId -> checked.stateOf(playerId) == PlayerCombatState.ALIVE;
  }

  interface PlayerGateway {
    Optional<Presence> snapshot(UUID playerId);

    void send(UUID playerId, Component message);
  }

  record Presence(
      String worldName,
      double x,
      double y,
      double z,
      boolean online,
      boolean onGround,
      boolean valid,
      boolean dead,
      GameMode gameMode) {

    Presence {
      Preconditions.checkNotNull(worldName, "worldName");
      Preconditions.checkNotNull(gameMode, "gameMode");
    }

    boolean physicallyEligible() {
      return online && valid && !dead && gameMode == GameMode.SURVIVAL
          && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
  }

  private static final class BukkitPlayerGateway implements PlayerGateway {
    @Override
    public Optional<Presence> snapshot(UUID playerId) {
      Player player = Bukkit.getPlayer(playerId);
      if (player == null) return Optional.empty();
      Location location = player.getLocation();
      if (location.getWorld() == null) return Optional.empty();
      return Optional.of(new Presence(location.getWorld().getName(), location.getX(),
          location.getY(), location.getZ(), player.isOnline(), player.isOnGround(),
          player.isValid(), player.isDead(), player.getGameMode()));
    }

    @Override
    public void send(UUID playerId, Component message) {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null && player.isOnline()) player.sendMessage(message);
    }
  }

  private record GameTarget(CraftovPoi poi, Set<UUID> participants) {
    GameTarget {
      Preconditions.checkNotNull(poi, "poi");
      participants = Set.copyOf(Preconditions.checkNotNull(participants, "participants"));
    }
  }

  private record ScanCandidate(CraftovGameId gameId, UUID playerId) {}

  private record CompletionKey(CraftovGameId gameId, TeamKey teamKey) {}

  private record EligibleMember(CraftovPlayer player, TeamKey teamKey, Presence presence) {}

  private record TeamKey(int teamId, @Nullable UUID soloPlayerId) {
    TeamKey {
      Preconditions.checkArgument(teamId >= 0, "teamId must not be negative");
      Preconditions.checkArgument((teamId == 0) == (soloPlayerId != null),
          "exactly one team identity must be present");
    }

    static TeamKey team(int teamId) {
      Preconditions.checkArgument(teamId >= 1, "teamId must be at least 1");
      return new TeamKey(teamId, null);
    }

    static TeamKey solo(UUID playerId) {
      return new TeamKey(0, Preconditions.checkNotNull(playerId, "playerId"));
    }
  }
}
