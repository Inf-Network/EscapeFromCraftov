package com.birditch.raidcore.paper;

import com.birditch.raidcore.api.combat.CombatService;
import com.birditch.raidcore.api.extraction.ExtractionService;
import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.profile.ProfileService;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.api.session.PlayerSession;
import com.birditch.raidcore.api.session.SessionService;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.service.ServiceRegistry;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

/// Ties player connections to the domain lifecycle: join creates a session and
/// loads the profile; quit unwinds extraction, game, combat, session and profile
/// state in that order.
public final class ConnectionListener implements Listener {

  private final SessionService sessions;
  private final ProfileService profiles;
  private final ExtractionService extraction;
  private final RaidGameService games;
  private final CombatService combat;
  private final RaidScheduler scheduler;
  private final Messages messages;
  private final Logger logger;

  public ConnectionListener(
      ServiceRegistry services, RaidScheduler scheduler, Messages messages, Logger logger) {
    Preconditions.checkNotNull(services, "services");
    this.sessions = services.require(SessionService.class);
    this.profiles = services.require(ProfileService.class);
    this.extraction = services.require(ExtractionService.class);
    this.games = services.require(RaidGameService.class);
    this.combat = services.require(CombatService.class);
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.messages = Preconditions.checkNotNull(messages, "messages");
    this.logger = Preconditions.checkNotNull(logger, "logger");
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    UUID playerId = player.getUniqueId();
    sessions.create(playerId, player.getName());
    profiles
        .load(playerId, player.getName())
        .whenComplete((_, error) -> scheduler.runSync(() -> completeProfileLoad(playerId, error)));
  }

  /// Runs on the main thread; the session may already be gone when the player
  /// quit while the profile was still loading.
  private void completeProfileLoad(UUID playerId, @Nullable Throwable error) {
    Optional<PlayerSession> session = sessions.find(playerId);
    if (session.isEmpty()) {
      return;
    }
    Player player = Bukkit.getPlayer(playerId);
    if (error == null) {
      session.get().markProfileReady();
      if (player != null) {
        player.sendMessage(messages.msg("join.profile-loaded"));
      }
      return;
    }
    session.get().markProfileFailed();
    logger.log(Level.SEVERE, "Profile load failed for player " + playerId, error);
    if (player != null) {
      player.sendMessage(messages.msg("join.profile-failed"));
    }
  }

  /// A failed leave [com.birditch.raidcore.common.result.Result] simply means
  /// the player was not in a game, so it is deliberately ignored.
  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    extraction.cancel(playerId);
    games.leave(playerId);
    combat.reset(playerId);
    sessions.end(playerId);
    profiles
        .unload(playerId)
        .whenComplete((_, error) -> {
          if (error != null) {
            logger.log(Level.SEVERE, "Profile unload failed for player " + playerId, error);
          }
        });
  }
}
