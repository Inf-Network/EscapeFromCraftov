package com.birditch.raidcore.game.plugin.listener;

import com.birditch.raidcore.api.combat.CombatService;
import com.birditch.raidcore.api.extraction.ExtractionService;
import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.profile.ProfileService;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.api.session.PlayerSession;
import com.birditch.raidcore.api.session.SessionService;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.service.ServiceRegistry;
import com.birditch.raidcore.game.plugin.instance.InstanceWorldIndex;
import com.birditch.raidcore.game.plugin.isolation.VisibilityService;
import com.birditch.raidcore.game.plugin.network.PendingJoinHandler;
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

/// Ties player connections to the domain lifecycle: join creates a session,
/// loads the profile, consumes any pending match assignment, refreshes
/// visibility and applies the tab header/footer; quit unwinds extraction, game,
/// combat, session, profile and isolation-group state in that order.
public final class GameConnectionListener implements Listener {

  /// Constructor dependencies, bundled so the listener keeps a
  /// single-parameter constructor.
  public record Deps(
      ServiceRegistry services,
      RaidScheduler scheduler,
      Messages messages,
      PendingJoinHandler pendingJoins,
      VisibilityService visibility,
      InstanceWorldIndex index,
      Logger logger) {

    public Deps {
      Preconditions.checkNotNull(services, "services");
      Preconditions.checkNotNull(scheduler, "scheduler");
      Preconditions.checkNotNull(messages, "messages");
      Preconditions.checkNotNull(pendingJoins, "pendingJoins");
      Preconditions.checkNotNull(visibility, "visibility");
      Preconditions.checkNotNull(index, "index");
      Preconditions.checkNotNull(logger, "logger");
    }
  }

  private final SessionService sessions;
  private final ProfileService profiles;
  private final ExtractionService extraction;
  private final RaidGameService games;
  private final CombatService combat;
  private final RaidScheduler scheduler;
  private final Messages messages;
  private final PendingJoinHandler pendingJoins;
  private final VisibilityService visibility;
  private final InstanceWorldIndex index;
  private final Logger logger;

  public GameConnectionListener(Deps deps) {
    Preconditions.checkNotNull(deps, "deps");
    this.sessions = deps.services().require(SessionService.class);
    this.profiles = deps.services().require(ProfileService.class);
    this.extraction = deps.services().require(ExtractionService.class);
    this.games = deps.services().require(RaidGameService.class);
    this.combat = deps.services().require(CombatService.class);
    this.scheduler = deps.scheduler();
    this.messages = deps.messages();
    this.pendingJoins = deps.pendingJoins();
    this.visibility = deps.visibility();
    this.index = deps.index();
    this.logger = deps.logger();
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    UUID playerId = player.getUniqueId();
    sessions.create(playerId, player.getName());
    profiles
        .load(playerId, player.getName())
        .whenComplete((_, error) -> scheduler.runSync(() -> completeProfileLoad(playerId, error)));
    pendingJoins.handle(player);
    visibility.refresh(player);
    player.sendPlayerListHeaderAndFooter(messages.raw("tab.header"), messages.raw("tab.footer"));
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
        player.sendMessage(messages.raw("join.profile-loaded"));
      }
      return;
    }
    session.get().markProfileFailed();
    logger.log(Level.SEVERE, "Profile load failed for player " + playerId, error);
    if (player != null) {
      player.sendMessage(messages.raw("join.profile-failed"));
    }
  }

  /// A failed leave [com.birditch.raidcore.common.result.Result] simply means
  /// the player was not in a game, so it is deliberately ignored. Bukkit hides
  /// quitting players natively, so no visibility refresh is needed here — only
  /// the isolation group entry is dropped.
  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    extraction.cancel(playerId);
    games.leave(playerId);
    combat.reset(playerId);
    sessions.end(playerId);
    index.clearGroup(playerId);
    profiles
        .unload(playerId)
        .whenComplete((_, error) -> {
          if (error != null) {
            logger.log(Level.SEVERE, "Profile unload failed for player " + playerId, error);
          }
        });
  }
}
