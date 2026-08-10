package com.birditch.raidcore.game.plugin.listener;

import com.birditch.raidcore.api.combat.CombatService;
import com.birditch.raidcore.api.combat.PlayerCombatState;
import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.game.RaidPlayer;
import com.birditch.raidcore.api.game.RaidTeamRules;
import com.birditch.raidcore.api.inventory.PlayerInventoryService;
import com.birditch.raidcore.api.profile.ProgressionService;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.game.plugin.GameplaySettings;
import com.birditch.raidcore.game.plugin.instance.InstanceWorldIndex;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
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
  private final RaidGameService games;
  private final CombatService combat;
  private final PlayerInventoryService inventories;
  private final ProgressionService progression;
  private final InstanceWorldIndex worlds;
  private final RaidScheduler scheduler;
  private final Messages messages;
  private final GameplaySettings settings;
  private final Map<UUID, Attack> lastAttackers = new HashMap<>();
  private final Map<UUID, Integer> kills = new HashMap<>();

  public GameCombatListener(Plugin plugin, RaidGameService games, CombatService combat,
      PlayerInventoryService inventories, ProgressionService progression,
      InstanceWorldIndex worlds, RaidScheduler scheduler, Messages messages,
      GameplaySettings settings) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.games = Preconditions.checkNotNull(games, "games");
    this.combat = Preconditions.checkNotNull(combat, "combat");
    this.inventories = Preconditions.checkNotNull(inventories, "inventories");
    this.progression = Preconditions.checkNotNull(progression, "progression");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.messages = Preconditions.checkNotNull(messages, "messages");
    this.settings = Preconditions.checkNotNull(settings, "settings");
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onDamage(EntityDamageEvent event) {
    if (!(event.getEntity() instanceof Player victim)) return;
    RaidGame game = gameOf(victim).orElse(null);
    if (game == null) return;
    if (game.state() != RaidGameState.RUNNING || !participant(game, victim.getUniqueId())) {
      event.setCancelled(true);
      return;
    }
    if (event instanceof EntityDamageByEntityEvent byEntity) {
      Player attacker = playerAttacker(byEntity.getDamager());
      if (attacker == null) return;
      RaidGame attackerGame = gameOf(attacker).orElse(null);
      if (attackerGame == null || !attackerGame.id().equals(game.id())
          || !participant(game, attacker.getUniqueId())) {
        event.setCancelled(true);
        return;
      }
      if (!settings.friendlyFire() && RaidTeamRules.sameTeam(game,
          attacker.getUniqueId(), victim.getUniqueId())) {
        event.setCancelled(true);
        attacker.sendMessage(messages.raw("combat.friendly-fire-blocked"));
        return;
      }
      lastAttackers.put(victim.getUniqueId(), new Attack(attacker.getUniqueId(),
          System.currentTimeMillis()));
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDeath(PlayerDeathEvent event) {
    Player victim = event.getEntity();
    RaidGame game = gameOf(victim).orElse(null);
    if (game == null || !participant(game, victim.getUniqueId())) return;
    // PlayerDeathEvent's vanilla message is broadcast to the whole Paper
    // process and does not pass through AsyncChatEvent. RaidCore emits the
    // room-scoped feed below instead.
    event.deathMessage(null);
    event.setKeepInventory(false);
    event.setKeepLevel(true);
    combat.setState(victim.getUniqueId(), PlayerCombatState.DEAD);
    UUID victimId = victim.getUniqueId();
    Attack attack = lastAttackers.remove(victimId);
    if (attack != null && System.currentTimeMillis() - attack.atMillis()
        <= Duration.ofSeconds(settings.attributionSeconds()).toMillis()) {
      int total = kills.merge(attack.attackerId(), 1, Integer::sum);
      Player killer = org.bukkit.Bukkit.getPlayer(attack.attackerId());
      if (killer != null) killer.sendMessage(messages.raw("combat.kill-confirmed",
          Placeholder.unparsed("victim", victim.getName()),
          Placeholder.unparsed("kills", Integer.toString(total))));
      broadcast(game, messages.raw("combat.kill-feed",
          Placeholder.unparsed("attacker", killer == null ? "玩家" : killer.getName()),
          Placeholder.unparsed("victim", victim.getName())));
      awardProgression("kill", attack.attackerId(), game.id(), victimId,
          settings.killExperience(), "击败敌对玩家", 0);
    } else {
      broadcast(game, messages.raw("combat.death-feed",
          Placeholder.unparsed("victim", victim.getName())));
    }
    awardProgression("death", victimId, game.id(), victimId,
        settings.deathExperience(), "行动参与", 0);
    settleDeath(victim, game.id(), 0);
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    UUID id = event.getPlayer().getUniqueId();
    lastAttackers.remove(id);
    kills.remove(id);
  }

  public int kills(UUID playerId) {
    return kills.getOrDefault(playerId, 0);
  }

  private void settleDeath(Player victim, RaidGameId gameId, int attempt) {
    UUID victimId = victim.getUniqueId();
    inventories.settleDeath(victimId, gameId).whenComplete((_, failure) ->
        scheduler.runSync(() -> {
          if (failure != null) {
            org.bukkit.Bukkit.getLogger().log(java.util.logging.Level.WARNING,
                "死亡损失结算失败，将自动重试:" + victimId, failure);
            if (attempt == 0 && victim.isOnline()) {
              victim.sendMessage(messages.raw("combat.settlement-failed"));
            }
            scheduler.runLater(Duration.ofSeconds(5),
                () -> settleDeath(victim, gameId, attempt + 1));
            return;
          }
          if (victim.isOnline()) {
            victim.setInvulnerable(true);
            try {
              victim.spigot().respawn();
            } catch (RuntimeException ignored) {
              // A proxy or another plugin may own the respawn screen.
            }
          }
          games.leave(victimId);
        }));
  }

  private Optional<RaidGame> gameOf(Player player) {
    return worlds.gameByWorld(player.getWorld().getName()).flatMap(games::find);
  }

  private void awardProgression(String kind, UUID playerId, RaidGameId gameId,
      UUID relatedPlayer, int experience, String reason, int attempt) {
    UUID transactionId = UUID.nameUUIDFromBytes(("raid-progression:" + kind + ":"
        + gameId.value() + ":" + playerId + ":" + relatedPlayer)
        .getBytes(StandardCharsets.UTF_8));
    progression.award(transactionId, playerId, experience, reason)
        .whenComplete((result, failure) -> scheduler.runSync(() -> {
          if (failure != null || result == null || !result.isSuccess()) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                "行动经验写入失败，将在 5 秒后重试 (第 " + (attempt + 1) + " 次):"
                    + playerId, failure);
            scheduler.runLater(Duration.ofSeconds(5), () -> awardProgression(
                kind, playerId, gameId, relatedPlayer, experience, reason, attempt + 1));
            return;
          }
          result.value().filter(update -> update.levelledUp()).ifPresent(update -> {
            Player online = org.bukkit.Bukkit.getPlayer(playerId);
            if (online != null) {
              online.showTitle(Title.title(Component.text("等级提升", NamedTextColor.GOLD),
                  Component.text("当前等级 " + update.levelAfter(), NamedTextColor.YELLOW)));
            }
          });
        }));
  }

  private static boolean participant(RaidGame game, UUID playerId) {
    return game.players().stream().anyMatch(player -> player.playerId().equals(playerId));
  }

  private static Player playerAttacker(Entity entity) {
    if (entity instanceof Player player) return player;
    if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
      return player;
    }
    return null;
  }

  private static void broadcast(RaidGame game, net.kyori.adventure.text.Component message) {
    for (RaidPlayer player : game.players()) {
      Player online = org.bukkit.Bukkit.getPlayer(player.playerId());
      if (online != null) online.sendMessage(message);
    }
  }

  private record Attack(UUID attackerId, long atMillis) {}
}
