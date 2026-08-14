package com.birditch.escapefromcraftov.game.plugin.listener;

import com.birditch.escapefromcraftov.api.combat.CombatService;
import com.birditch.escapefromcraftov.api.extraction.ExtractionService;
import com.birditch.escapefromcraftov.api.extraction.ExtractionState;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventoryService;
import com.birditch.escapefromcraftov.api.profile.ProfileService;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.session.PlayerSession;
import com.birditch.escapefromcraftov.api.session.SessionService;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.core.service.ServiceRegistry;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import com.birditch.escapefromcraftov.game.plugin.isolation.VisibilityService;
import com.birditch.escapefromcraftov.game.plugin.network.PendingJoinHandler;
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
      CraftovScheduler scheduler,
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
  private final CraftovGameService games;
  private final CombatService combat;
  private final PlayerInventoryService inventories;
  private final CraftovScheduler scheduler;
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
    this.games = deps.services().require(CraftovGameService.class);
    this.combat = deps.services().require(CombatService.class);
    this.inventories = deps.services().require(PlayerInventoryService.class);
    this.scheduler = deps.scheduler();
    this.messages = deps.messages();
    this.pendingJoins = deps.pendingJoins();
    this.visibility = deps.visibility();
    this.index = deps.index();
    this.logger = deps.logger();
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    // A Game process can host several unrelated rooms. Bukkit's default join
    // announcement is server-wide and bypasses AsyncChatEvent, so never let it
    // leak a player connection into every room.
    event.joinMessage(null);
    Player player = event.getPlayer();
    UUID playerId = player.getUniqueId();
    pendingJoins.prepare(player);
    sessions.create(playerId, player.getName());
    index.setGroup(playerId, "pending:" + playerId);
    profiles
        .load(playerId, player.getName())
        .whenComplete((_, error) -> scheduler.runSync(() -> completeProfileLoad(playerId, error)));
    visibility.refresh(player);
    player.sendPlayerListHeaderAndFooter(messages.raw("tab.header"), messages.raw("tab.footer"));
  }

  /// Runs on the main thread; the session may already be gone when the player
  /// quit while the profile was still loading. In that case the quit handler's
  /// unload ran before the profile was cached, so unload again here to persist
  /// and evict the freshly cached profile.
  private void completeProfileLoad(UUID playerId, @Nullable Throwable error) {
    Optional<PlayerSession> session = sessions.find(playerId);
    if (session.isEmpty()) {
      profiles
          .unload(playerId)
          .whenComplete((_, unloadError) -> {
            if (unloadError != null) {
              logger.log(Level.SEVERE, "Profile unload failed for player " + playerId, unloadError);
            }
          });
      return;
    }
    Player player = Bukkit.getPlayer(playerId);
    if (error == null) {
      session.get().markProfileReady();
      if (player != null) {
        pendingJoins.handle(player);
      }
      return;
    }
    session.get().markProfileFailed();
    logger.log(Level.SEVERE, "Profile load failed for player " + playerId, error);
    if (player != null) {
      pendingJoins.profileFailed(player);
    }
  }

  /// A failed leave [com.birditch.escapefromcraftov.common.result.Result] simply means
  /// the player was not in a game, so it is deliberately ignored. Bukkit hides
  /// quitting players natively, so no visibility refresh is needed here — only
  /// the isolation group entry is dropped.
  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    // Room membership is removed below and the remaining Tab view is refreshed
    // explicitly. The vanilla quit announcement would otherwise be global.
    event.quitMessage(null);
    UUID playerId = event.getPlayer().getUniqueId();
    pendingJoins.release(playerId);
    boolean settlementPending = extraction.activeSession(playerId)
        .filter(session -> session.state() == ExtractionState.SETTLING).isPresent();
    if (!settlementPending) extraction.cancel(playerId);
    CraftovGameId gameId = sessions.find(playerId).flatMap(PlayerSession::currentGame).orElse(null);
    if (gameId == null) {
      finishQuit(playerId);
      return;
    }
    // The coordinator captured an immutable carried-item snapshot before the
    // quit event. Let its idempotent PostgreSQL retry finish; racing it with a
    // death settlement could turn an earned extraction into a loss.
    if (settlementPending) {
      finishQuit(playerId);
      return;
    }
    // Keep the room membership until the durable loss transaction completes.
    // The game manager ends a vacated RUNNING room after the final leave, so
    // removing first could unload its world while this settlement is in flight.
    settleQuitLoss(playerId, gameId, 0);
  }

  private void settleQuitLoss(UUID playerId, CraftovGameId gameId, int attempt) {
    inventories.settleDeath(playerId, gameId).whenComplete((_, error) ->
        scheduler.runSync(() -> {
          if (error != null) {
            logger.log(Level.WARNING,
                "玩家退出时死亡损失结算失败，将在 5 秒后自动重试 (第 " + (attempt + 1)
                    + " 次):" + playerId,
                error);
            scheduler.runLater(java.time.Duration.ofSeconds(5),
                () -> settleQuitLoss(playerId, gameId, attempt + 1));
            return;
          }
          games.leave(playerId);
          finishQuit(playerId);
        }));
  }

  private void finishQuit(UUID playerId) {
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
