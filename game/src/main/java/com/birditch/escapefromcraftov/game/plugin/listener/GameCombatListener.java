package com.birditch.escapefromcraftov.game.plugin.listener;

import com.birditch.escapefromcraftov.api.combat.CombatService;
import com.birditch.escapefromcraftov.api.combat.PlayerCombatState;
import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.combat.PlayerDownedEvent;
import com.birditch.escapefromcraftov.api.event.combat.PlayerRevivedEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovTeamRules;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventoryService;
import com.birditch.escapefromcraftov.api.inventory.CraftovInventoryOutcome;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.report.RaidOutcome;
import com.birditch.escapefromcraftov.api.profile.ProgressionService;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.GameplaySettings;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import com.birditch.escapefromcraftov.game.plugin.ownership.LootOwnershipController;
import com.birditch.escapefromcraftov.game.plugin.secure.SecureContainerController;
import com.birditch.escapefromcraftov.game.plugin.report.RaidResultRuntime;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/// Enforces Minecraft-native PvP/PvE boundaries and gates death loss on the
/// PostgreSQL settlement transaction.
public final class GameCombatListener implements Listener {

  private final Plugin plugin;
  private final CraftovGameService games;
  private final CombatService combat;
  private final PlayerInventoryService inventories;
  private final ProgressionService progression;
  private final InstanceWorldIndex worlds;
  private final CraftovScheduler scheduler;
  private final Messages messages;
  private final GameplaySettings settings;
  private final SecureContainerController secureContainers;
  private final RaidResultRuntime raidReports;
  private final LootOwnershipController lootOwnership;
  private final Map<UUID, Attack> lastAttackers = new HashMap<>();
  private final Map<UUID, Integer> kills = new HashMap<>();
  private final FriendlyAttributionCandidates<EntityDamageByEntityEvent> friendlyCandidates =
      new FriendlyAttributionCandidates<>();

  public GameCombatListener(Plugin plugin, CraftovGameService games, CombatService combat,
      PlayerInventoryService inventories, ProgressionService progression,
      InstanceWorldIndex worlds, CraftovScheduler scheduler, Messages messages,
      GameplaySettings settings, SecureContainerController secureContainers,
      RaidResultRuntime raidReports, LootOwnershipController lootOwnership) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.games = Preconditions.checkNotNull(games, "games");
    this.combat = Preconditions.checkNotNull(combat, "combat");
    this.inventories = Preconditions.checkNotNull(inventories, "inventories");
    this.progression = Preconditions.checkNotNull(progression, "progression");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.messages = Preconditions.checkNotNull(messages, "messages");
    this.settings = Preconditions.checkNotNull(settings, "settings");
    this.secureContainers = Preconditions.checkNotNull(secureContainers, "secureContainers");
    this.raidReports = Preconditions.checkNotNull(raidReports, "raidReports");
    this.lootOwnership = Preconditions.checkNotNull(lootOwnership, "lootOwnership");
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onDamage(EntityDamageEvent event) {
    if (!(event.getEntity() instanceof Player victim)) return;
    CraftovGame game = gameOf(victim).orElse(null);
    if (game == null) return;
    if (game.state() != CraftovGameState.RUNNING || !participant(game, victim.getUniqueId())) {
      event.setCancelled(true);
      return;
    }
    if (event instanceof EntityDamageByEntityEvent byEntity) {
      Player attacker = playerAttacker(byEntity.getDamager());
      if (attacker == null) return;
      CraftovGame attackerGame = gameOf(attacker).orElse(null);
      if (attackerGame == null || !attackerGame.id().equals(game.id())
          || !participant(game, attacker.getUniqueId())) {
        event.setCancelled(true);
        return;
      }
      if (CraftovTeamRules.sameTeam(game, attacker.getUniqueId(), victim.getUniqueId())) {
        if (GameCombatAttributionPolicy.friendlyDamage(settings.friendlyFire())
            == GameCombatAttributionPolicy.FriendlyDamage.CANCEL_AND_KEEP_HOSTILE) {
          event.setCancelled(true);
          attacker.sendMessage(messages.raw("combat.friendly-fire-blocked"));
          // A cancelled hit is not effective evidence and must not erase the
          // last valid hostile attacker.
          return;
        }
        // Enabled friendly damage is evaluated again at MONITOR. Downed and
        // other HIGHEST rules may still cancel it. Only an ALIVE victim can
        // synchronously enter downed from this particular friendly event.
        if (combat.stateOf(victim.getUniqueId()) == PlayerCombatState.ALIVE) {
          friendlyCandidates.begin(victim.getUniqueId(), game.id(), byEntity);
        }
        return;
      }
      lastAttackers.put(victim.getUniqueId(), new Attack(attacker.getUniqueId(),
          game.id(), System.currentTimeMillis()));
    }
  }

  /** Always closes the event candidate; only an effective final hit clears evidence. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
  public void onEffectiveFriendlyDamage(EntityDamageByEntityEvent event) {
    if (!(event.getEntity() instanceof Player victim)) return;
    if (friendlyCandidates.finish(event, event.isCancelled(), event.getFinalDamage())) {
      lastAttackers.remove(victim.getUniqueId());
    }
  }

  /** Confirms that a cancelled lethal friendly event actually took the victim down. */
  @Subscribe
  public void onPlayerDowned(PlayerDownedEvent event) {
    if (friendlyCandidates.confirmsDowned(event.playerId(), event.gameId())) {
      lastAttackers.remove(event.playerId());
    }
  }

  /** A completed teammate rescue closes the old downing encounter's attribution window. */
  @Subscribe
  public void onPlayerRevived(PlayerRevivedEvent event) {
    lastAttackers.remove(event.playerId());
  }

  /**
   * HIGHEST is deliberate: the validated claim must exist before the death-crate
   * MONITOR handler captures drops, independent of listener registration order.
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onDeath(PlayerDeathEvent event) {
    Player victim = event.getEntity();
    CraftovGame game = gameOf(victim).orElse(null);
    if (game == null || !participant(game, victim.getUniqueId())) return;
    // PlayerDeathEvent's vanilla message is broadcast to the whole Paper
    // process and does not pass through AsyncChatEvent. EscapeFromCraftov emits the
    // room-scoped feed below instead.
    event.deathMessage(null);
    event.setKeepInventory(false);
    event.setKeepLevel(true);
    combat.setState(victim.getUniqueId(), PlayerCombatState.DEAD);
    UUID victimId = victim.getUniqueId();
    Attack attack = lastAttackers.remove(victimId);
    UUID attributedKiller = attack != null && attack.gameId().equals(game.id())
        && System.currentTimeMillis() - attack.atMillis()
        <= Duration.ofSeconds(settings.attributionSeconds()).toMillis()
        ? attack.attackerId() : null;
    // Publish the authoritative loot claim before any presentation or reward
    // side effect can fail; DeathCrate's MONITOR handler consumes this record.
    lootOwnership.recordPlayerDeath(game, victimId, attributedKiller);

    // Start the durable loss transaction before kill counters, reports, progression or messages.
    // None of those secondary effects may strand a dead participant with an unsettled loadout.
    List<ItemInstance> protectedItems = secureContainers.beginSettlement(game.id(), victimId);
    settleDeath(victim, game.id(), protectedItems, 0);

    if (attributedKiller != null) {
      int[] total = {kills.getOrDefault(attributedKiller, 0)};
      bestEffort("更新击杀计数", () ->
          total[0] = kills.merge(attributedKiller, 1, Integer::sum));
      bestEffort("记录击杀战报", () -> raidReports.recordKill(game.id(), attributedKiller));
      Player[] killer = {null};
      bestEffort("查找击杀玩家", () -> killer[0] = org.bukkit.Bukkit.getPlayer(attributedKiller));
      if (killer[0] != null) present("击杀确认提示", () -> killer[0].sendMessage(messages.raw(
          "combat.kill-confirmed", Placeholder.unparsed("victim", victim.getName()),
          Placeholder.unparsed("kills", Integer.toString(total[0])))));
      present("击杀播报", () -> broadcast(game, messages.raw("combat.kill-feed",
          Placeholder.unparsed("attacker", killer[0] == null ? "玩家" : killer[0].getName()),
          Placeholder.unparsed("victim", victim.getName()))));
      bestEffort("写入击杀经验", () -> awardProgression("kill", attributedKiller,
          game.id(), victimId, settings.killExperience(), "击败敌对玩家", 0));
    } else {
      present("死亡播报", () -> broadcast(game, messages.raw("combat.death-feed",
          Placeholder.unparsed("victim", victim.getName()))));
    }
    bestEffort("写入死亡参与经验", () -> awardProgression("death", victimId, game.id(),
        victimId, settings.deathExperience(), "行动参与", 0));
    bestEffort("声明死亡战报", () ->
        raidReports.declareOutcome(game.id(), victimId, RaidOutcome.KILLED));
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    UUID id = event.getPlayer().getUniqueId();
    lastAttackers.remove(id);
    friendlyCandidates.clear(id);
    kills.remove(id);
  }

  public int kills(UUID playerId) {
    return kills.getOrDefault(playerId, 0);
  }

  private void settleDeath(Player victim, CraftovGameId gameId,
      List<ItemInstance> protectedItems, int attempt) {
    UUID victimId = victim.getUniqueId();
    try {
      inventories.settleLoss(victimId, gameId, protectedItems).whenComplete((result, failure) ->
        scheduler.runSync(() -> {
          if (failure != null || result == null) {
            scheduler.runLater(Duration.ofSeconds(5),
                () -> settleDeath(victim, gameId, protectedItems, attempt + 1));
            safeLog(java.util.logging.Level.WARNING,
                "死亡损失结算失败，将自动重试:" + victimId, failure);
            if (attempt == 0 && victim.isOnline()) {
              present("死亡结算失败提示", () ->
                  victim.sendMessage(messages.raw("combat.settlement-failed")));
            }
            return;
          }
          try {
            critical("关闭死亡安全箱结算", () ->
                secureContainers.completeSettlement(gameId, victimId));
            if (result.outcome() == CraftovInventoryOutcome.LOST) critical(
                "写入死亡战报", () -> raidReports.complete(
                    gameId, victimId, RaidOutcome.KILLED, protectedItems));
            if (victim.isOnline()) present("死亡结算复活提示", () -> {
              victim.setInvulnerable(true);
              victim.spigot().respawn();
            });
          } finally {
            // Durable loss is already committed; presentation/report failures cannot freeze the
            // participant inside a completed raid.
            games.leave(victimId);
          }
        }));
    } catch (RuntimeException synchronousFailure) {
      scheduler.runLater(Duration.ofSeconds(5),
          () -> settleDeath(victim, gameId, protectedItems, attempt + 1));
      safeLog(java.util.logging.Level.WARNING,
          "死亡损失结算启动失败，将自动重试:" + victimId, synchronousFailure);
      if (attempt == 0 && victim.isOnline()) present("死亡结算失败提示", () ->
          victim.sendMessage(messages.raw("combat.settlement-failed")));
    }
  }

  private void present(String action, Runnable presentation) {
    try {
      presentation.run();
    } catch (RuntimeException failure) {
      safeLog(java.util.logging.Level.WARNING, action + "失败，但结算继续", failure);
    }
  }

  private void bestEffort(String action, Runnable operation) {
    try {
      operation.run();
    } catch (RuntimeException failure) {
      safeLog(java.util.logging.Level.WARNING, action + "失败，但死亡结算已启动", failure);
    }
  }

  private void critical(String action, Runnable operation) {
    try {
      operation.run();
    } catch (RuntimeException failure) {
      safeLog(java.util.logging.Level.SEVERE, action + "失败，但死亡结算继续", failure);
    }
  }

  private void safeLog(java.util.logging.Level level, String message, Throwable failure) {
    try {
      plugin.getLogger().log(level, message, failure);
    } catch (RuntimeException ignored) {
      // Logging is presentation and must never block a durable settlement terminal state.
    }
  }

  private Optional<CraftovGame> gameOf(Player player) {
    return worlds.gameByWorld(player.getWorld().getName()).flatMap(games::find);
  }

  private void awardProgression(String kind, UUID playerId, CraftovGameId gameId,
      UUID relatedPlayer, int experience, String reason, int attempt) {
    UUID transactionId = UUID.nameUUIDFromBytes(("craftov-progression:" + kind + ":"
        + gameId.value() + ":" + playerId + ":" + relatedPlayer)
        .getBytes(StandardCharsets.UTF_8));
    progression.award(transactionId, playerId, experience, reason)
        .whenComplete((result, failure) -> scheduler.runSync(() -> {
          if (failure != null || result == null || !result.isSuccess()) {
            scheduler.runLater(Duration.ofSeconds(5), () -> awardProgression(
                kind, playerId, gameId, relatedPlayer, experience, reason, attempt + 1));
            safeLog(java.util.logging.Level.WARNING,
                "行动经验写入失败，将在 5 秒后重试 (第 " + (attempt + 1) + " 次):"
                    + playerId, failure);
            return;
          }
          result.value().filter(update -> update.levelledUp()).ifPresent(update -> {
            Player online = org.bukkit.Bukkit.getPlayer(playerId);
            if (online != null) {
              present("行动升级提示", () -> online.showTitle(Title.title(
                  Component.text("等级提升", NamedTextColor.GOLD),
                  Component.text("当前等级 " + update.levelAfter(), NamedTextColor.YELLOW))));
            }
          });
        }));
  }

  private static boolean participant(CraftovGame game, UUID playerId) {
    return game.players().stream().anyMatch(player -> player.playerId().equals(playerId));
  }

  private static Player playerAttacker(Entity entity) {
    if (entity instanceof Player player) return player;
    if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
      return player;
    }
    return null;
  }

  private static void broadcast(CraftovGame game, net.kyori.adventure.text.Component message) {
    for (CraftovPlayer player : game.players()) {
      Player online = org.bukkit.Bukkit.getPlayer(player.playerId());
      if (online != null) online.sendMessage(message);
    }
  }

  private record Attack(UUID attackerId, CraftovGameId gameId, long atMillis) {}
}
