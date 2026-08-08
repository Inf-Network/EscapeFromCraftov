package com.birditch.raidcore.game.plugin.instance;

import com.birditch.raidcore.api.event.Subscribe;
import com.birditch.raidcore.api.event.extraction.PlayerExtractedEvent;
import com.birditch.raidcore.api.event.game.PlayerJoinedRaidEvent;
import com.birditch.raidcore.api.event.game.PlayerLeftRaidEvent;
import com.birditch.raidcore.api.event.game.RaidCreatedEvent;
import com.birditch.raidcore.api.event.game.RaidEndedEvent;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/// Glues domain game events to Bukkit worlds: creates the instance world when
/// a raid is created, moves players between lobby and instance (via proxy when
/// enabled, else by teleport), keeps [InstanceWorldIndex] groups and pairwise
/// visibility in sync, and destroys the world when the raid ends.
///
/// Registered on the domain event bus by the application; all handlers run on
/// the main thread because domain publishing is main-thread only. The world
/// creation callback hops back to the main thread explicitly.
public final class InstanceWorldBridge {

  private static final String GAME_GROUP_PREFIX = "game:";
  private static final String LOBBY_GROUP = "lobby";

  private final BridgeDeps deps;

  public InstanceWorldBridge(BridgeDeps deps) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
  }

  @Subscribe
  public void onRaidCreated(RaidCreatedEvent event) {
    RaidGameId gameId = event.gameId();
    deps.worlds()
        .createInstanceWorld(gameId, event.mapId())
        .whenComplete(
            (world, error) ->
                deps.scheduler()
                    .runSync(
                        () -> {
                          if (error != null) {
                            deps.logger()
                                .log(
                                    Level.SEVERE,
                                    "World creation failed for game " + gameId.value(),
                                    error);
                            stopFailedGame(gameId);
                            return;
                          }
                          handleWorldReady(gameId, world);
                        }));
  }

  /// Returns every remaining player to the lobby, then tears the instance
  /// world down. Extracted players already went home when their
  /// [PlayerExtractedEvent] fired.
  @Subscribe
  public void onRaidEnded(RaidEndedEvent event) {
    for (UUID playerId : event.result().remainingPlayers()) {
      returnToLobby(playerId);
    }
    deps.worlds().destroyInstanceWorld(event.gameId());
    deps.index().remove(event.gameId());
  }

  @Subscribe
  public void onPlayerJoinedRaid(PlayerJoinedRaidEvent event) {
    deps.index().setGroup(event.playerId(), GAME_GROUP_PREFIX + event.gameId().value());
    Player player = Bukkit.getPlayer(event.playerId());
    if (player == null) {
      return;
    }
    if (deps.index().isReady(event.gameId())) {
      deps.index()
          .worldOf(event.gameId())
          .map(Bukkit::getWorld)
          .ifPresent(world -> player.teleport(world.getSpawnLocation()));
    }
    deps.visibility().refresh(player);
  }

  @Subscribe
  public void onPlayerLeftRaid(PlayerLeftRaidEvent event) {
    returnToLobby(event.playerId());
  }

  /// Extraction success is a raid exit like any other: the game manager has
  /// already removed the player from the game, so only the world-side return
  /// remains.
  @Subscribe
  public void onPlayerExtracted(PlayerExtractedEvent event) {
    returnToLobby(event.playerId());
  }

  private void handleWorldReady(RaidGameId gameId, World world) {
    if (deps.games().find(gameId).isEmpty()) {
      deps.logger()
          .warning("Game " + gameId.value() + " ended before its world finished; discarding it");
      deps.worlds().destroyInstanceWorld(gameId);
      return;
    }
    deps.index().register(gameId, world.getName());
    deps.index().markReady(gameId);
  }

  private void stopFailedGame(RaidGameId gameId) {
    deps.games()
        .stop(gameId)
        .error()
        .ifPresent(
            message ->
                deps.logger()
                    .warning(
                        "Could not stop game "
                            + gameId.value()
                            + " after world failure: "
                            + message));
  }

  private void returnToLobby(UUID playerId) {
    deps.index().setGroup(playerId, LOBBY_GROUP);
    Player player = Bukkit.getPlayer(playerId);
    if (player == null) {
      return;
    }
    if (deps.config().proxyEnabled()) {
      deps.bungee().send(player, deps.config().lobbyServerName());
    } else {
      player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
    }
    deps.visibility().refresh(player);
  }
}
