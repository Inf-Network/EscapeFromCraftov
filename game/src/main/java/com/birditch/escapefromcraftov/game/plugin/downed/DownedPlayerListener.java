package com.birditch.escapefromcraftov.game.plugin.downed;

import com.birditch.escapefromcraftov.api.combat.CombatService;
import com.birditch.escapefromcraftov.api.combat.DownedService;
import com.birditch.escapefromcraftov.api.combat.PlayerCombatState;
import com.birditch.escapefromcraftov.api.extraction.ExtractionService;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovTeamRules;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;

/**
 * Paper bridge for one-down-per-raid, bleed-out and hold-to-revive gameplay.
 *
 * <p>The lethal interception runs at {@link EventPriority#HIGHEST}, after
 * {@code GameCombatListener} has rejected cross-room damage and disabled
 * friendly fire. {@code ignoreCancelled=true} is intentional: a cancelled
 * teammate hit can neither down a player nor interrupt a rescue.</p>
 */
public final class DownedPlayerListener implements Listener, AutoCloseable {

  static final int MAXIMUM_DOWNS_PER_GAME = 1;
  private static final Duration BLEED_DURATION = Duration.ofSeconds(30);
  private static final Duration RESCUE_DURATION = Duration.ofSeconds(5);
  private static final Duration TICK = Duration.ofMillis(100);
  private static final double RESCUE_DISTANCE_SQUARED = 3 * 3;
  private static final int SLOWNESS_AMPLIFIER = 4;
  private static final int REVIVE_INVULNERABILITY_TICKS = 60;

  private final CraftovGameService games;
  private final CombatService combat;
  private final DownedService downed;
  private final ExtractionService extractions;
  private final InstanceWorldIndex worlds;
  private final Messages messages;
  private final Map<UUID, DownedState> downedPlayers = new HashMap<>();
  private final Map<PlayerGameKey, Integer> downCounts = new HashMap<>();
  private final Map<UUID, RescueState> rescuesByRescuer = new HashMap<>();
  private final Map<UUID, UUID> rescuersByTarget = new HashMap<>();
  private final ScheduledTask ticker;

  public DownedPlayerListener(CraftovGameService games, CombatService combat,
      DownedService downed, ExtractionService extractions, InstanceWorldIndex worlds,
      CraftovScheduler scheduler, Messages messages) {
    this.games = Preconditions.checkNotNull(games, "games");
    this.combat = Preconditions.checkNotNull(combat, "combat");
    this.downed = Preconditions.checkNotNull(downed, "downed");
    this.extractions = Preconditions.checkNotNull(extractions, "extractions");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
    Preconditions.checkNotNull(scheduler, "scheduler");
    this.messages = Preconditions.checkNotNull(messages, "messages");
    ticker = scheduler.runTimer(Duration.ZERO, TICK, this::tick);
  }

  /** Stops all timers and removes every visual or movement restriction. */
  @Override
  public void close() {
    ticker.cancel();
    for (UUID rescuerId : List.copyOf(rescuesByRescuer.keySet())) {
      cancelRescue(rescuerId, false);
    }
    for (UUID playerId : List.copyOf(downedPlayers.keySet())) {
      clearDowned(playerId, true);
    }
    downCounts.clear();
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  public void onAttack(EntityDamageByEntityEvent event) {
    Player attacker = playerAttacker(event.getDamager());
    if (attacker == null || combat.stateOf(attacker.getUniqueId()) != PlayerCombatState.DOWNED) {
      return;
    }
    event.setCancelled(true);
    attacker.sendActionBar(messages.raw("downed.action-blocked"));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onDamage(EntityDamageEvent event) {
    if (!(event.getEntity() instanceof Player victim) || event.getFinalDamage() <= 0) return;
    CraftovGame game = gameOf(victim).orElse(null);
    if (!activeParticipant(game, victim.getUniqueId())) return;

    UUID victimId = victim.getUniqueId();
    PlayerCombatState combatState = combat.stateOf(victimId);
    if (combatState == PlayerCombatState.DOWNED) {
      if (!hostileFollowUp(event, game, victimId)) {
        // Bleeding is its own 30-second clock. Fire, falling and teammate
        // contact must not silently bypass that clock.
        event.setCancelled(true);
        return;
      }
      event.setCancelled(true);
      finishAsDeath(victim);
      return;
    }

    boolean lethal = DownedFlowPolicy.isLethal(victim.getHealth(), event.getFinalDamage());
    int previousDowns = downCounts.getOrDefault(new PlayerGameKey(game.id(), victimId), 0);
    DownedFlowPolicy.LethalDecision decision = DownedFlowPolicy.lethalDecision(
        true, combatState, lethal, false, hasAliveTeammate(game, victimId),
        previousDowns, MAXIMUM_DOWNS_PER_GAME);
    if (decision != DownedFlowPolicy.LethalDecision.ENTER_DOWNED) return;

    event.setCancelled(true);
    enterDowned(victim, game);
  }

  /** Only real, uncancelled damage interrupts a rescuer. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onRescuerDamage(EntityDamageEvent event) {
    if (!(event.getEntity() instanceof Player player) || event.getFinalDamage() <= 0) return;
    cancelRescue(player.getUniqueId(), true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityInteract(PlayerInteractEntityEvent event) {
    Player rescuer = event.getPlayer();
    if (combat.stateOf(rescuer.getUniqueId()) == PlayerCombatState.DOWNED) {
      event.setCancelled(true);
      rescuer.sendActionBar(messages.raw("downed.action-blocked"));
      return;
    }
    if (!(event.getRightClicked() instanceof Player target)
        || combat.stateOf(target.getUniqueId()) != PlayerCombatState.DOWNED) return;
    event.setCancelled(true);
    if (event.getHand() != EquipmentSlot.HAND) return;
    beginRescue(rescuer, target);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent event) {
    if (!isDowned(event.getPlayer())) return;
    event.setCancelled(true);
    event.getPlayer().sendActionBar(messages.raw("downed.action-blocked"));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryOpen(InventoryOpenEvent event) {
    if (!(event.getPlayer() instanceof Player player) || !isDowned(player)) return;
    event.setCancelled(true);
    player.sendActionBar(messages.raw("downed.action-blocked"));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryClick(InventoryClickEvent event) {
    if (event.getWhoClicked() instanceof Player player && isDowned(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryDrag(InventoryDragEvent event) {
    if (event.getWhoClicked() instanceof Player player && isDowned(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onDrop(PlayerDropItemEvent event) {
    if (isDowned(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onConsume(PlayerItemConsumeEvent event) {
    if (isDowned(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPickup(EntityPickupItemEvent event) {
    if (event.getEntity() instanceof Player player && isDowned(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onSwapHands(PlayerSwapHandItemsEvent event) {
    if (isDowned(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onHeldSlot(PlayerItemHeldEvent event) {
    if (isDowned(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onSprint(PlayerToggleSprintEvent event) {
    if (event.isSprinting() && isDowned(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onSneak(PlayerToggleSneakEvent event) {
    if (!event.isSneaking()) cancelRescue(event.getPlayer().getUniqueId(), true);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onDeath(PlayerDeathEvent event) {
    UUID playerId = event.getEntity().getUniqueId();
    cancelRescue(playerId, false);
    cancelRescueForTarget(playerId, false);
    clearDowned(playerId, false);
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    cancelRescue(playerId, false);
    cancelRescueForTarget(playerId, false);
    clearDowned(playerId, true);
    downCounts.keySet().removeIf(key -> key.playerId().equals(playerId));
  }

  private void enterDowned(Player victim, CraftovGame game) {
    UUID victimId = victim.getUniqueId();
    if (downedPlayers.containsKey(victimId)) return;
    cancelRescue(victimId, true);
    cancelRescueForTarget(victimId, true);
    extractions.cancel(victimId);
    downed.down(victimId, game.id());
    downCounts.merge(new PlayerGameKey(game.id(), victimId), 1, Integer::sum);

    victim.setAbsorptionAmount(0);
    victim.setHealth(Math.min(1, maximumHealth(victim)));
    victim.setSprinting(false);
    PotionEffect previousSlowness = victim.getPotionEffect(PotionEffectType.SLOWNESS);
    victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
        (int) (BLEED_DURATION.toSeconds() * 20 + 20), SLOWNESS_AMPLIFIER,
        false, false, true));

    long now = System.nanoTime();
    BossBar bar = BossBar.bossBar(messages.raw("downed.bleed-bar",
        Placeholder.unparsed("seconds", Long.toString(BLEED_DURATION.toSeconds()))),
        1, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    downedPlayers.put(victimId, new DownedState(game.id(), now,
        now + BLEED_DURATION.toNanos(), bar, previousSlowness));
    victim.showBossBar(bar);
    victim.showTitle(Title.title(messages.raw("downed.entered-title"),
        messages.raw("downed.entered-subtitle")));
    victim.sendMessage(messages.raw("downed.entered"));
    notifyAliveTeammates(game, victim, "downed.teammate-downed");
  }

  private void beginRescue(Player rescuer, Player target) {
    CraftovGame game = gameOf(rescuer).orElse(null);
    if (!activeParticipant(game, rescuer.getUniqueId())
        || !activeParticipant(game, target.getUniqueId())
        || !sameGameWorld(rescuer, target)
        || !CraftovTeamRules.sameTeam(game, rescuer.getUniqueId(), target.getUniqueId())) {
      rescuer.sendActionBar(messages.raw("downed.rescue-not-allowed"));
      return;
    }
    if (!rescuer.isSneaking()) {
      rescuer.sendActionBar(messages.raw("downed.rescue-sneak"));
      return;
    }
    if (rescuer.getLocation().distanceSquared(target.getLocation()) > RESCUE_DISTANCE_SQUARED) {
      rescuer.sendActionBar(messages.raw("downed.rescue-too-far"));
      return;
    }
    UUID rescuerId = rescuer.getUniqueId();
    UUID targetId = target.getUniqueId();
    UUID currentRescuer = rescuersByTarget.get(targetId);
    if (currentRescuer != null && !currentRescuer.equals(rescuerId)) {
      rescuer.sendActionBar(messages.raw("downed.rescue-already"));
      return;
    }
    RescueState current = rescuesByRescuer.get(rescuerId);
    if (current != null && current.targetId().equals(targetId)) {
      rescuer.sendActionBar(messages.raw("downed.rescue-in-progress"));
      return;
    }
    cancelRescue(rescuerId, false);
    long now = System.nanoTime();
    BossBar bar = BossBar.bossBar(messages.raw("downed.rescue-bar",
        Placeholder.unparsed("player", target.getName())), 0,
        BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
    RescueState rescue = new RescueState(game.id(), targetId, now,
        RESCUE_DURATION.toNanos(), bar);
    rescuesByRescuer.put(rescuerId, rescue);
    rescuersByTarget.put(targetId, rescuerId);
    rescuer.showBossBar(bar);
    rescuer.sendMessage(messages.raw("downed.rescue-started",
        Placeholder.unparsed("player", target.getName())));
    target.sendMessage(messages.raw("downed.rescue-receiving",
        Placeholder.unparsed("player", rescuer.getName())));
  }

  private void tick() {
    long now = System.nanoTime();
    for (Map.Entry<UUID, DownedState> entry : List.copyOf(downedPlayers.entrySet())) {
      UUID playerId = entry.getKey();
      DownedState state = entry.getValue();
      Player player = Bukkit.getPlayer(playerId);
      CraftovGame game = games.find(state.gameId()).orElse(null);
      if (player == null || !player.isOnline() || !activeParticipant(game, playerId)
          || gameOf(player).map(found -> !found.id().equals(state.gameId())).orElse(true)) {
        clearDowned(playerId, true);
        downCounts.remove(new PlayerGameKey(state.gameId(), playerId));
        continue;
      }
      extractions.cancel(playerId);
      long remainingNanos = state.deadlineNanos() - now;
      if (remainingNanos <= 0) {
        finishAsDeath(player);
        continue;
      }
      float remaining = (float) Math.max(0, Math.min(1,
          remainingNanos / (double) BLEED_DURATION.toNanos()));
      state.bar().progress(remaining);
      long seconds = Math.max(1, (long) Math.ceil(remainingNanos / 1_000_000_000D));
      state.bar().name(messages.raw("downed.bleed-bar",
          Placeholder.unparsed("seconds", Long.toString(seconds))));
    }

    for (Map.Entry<UUID, RescueState> entry : List.copyOf(rescuesByRescuer.entrySet())) {
      tickRescue(entry.getKey(), entry.getValue(), now);
    }
    downCounts.keySet().removeIf(key -> games.find(key.gameId())
        .map(game -> game.state() != CraftovGameState.RUNNING).orElse(true));
  }

  private void tickRescue(UUID rescuerId, RescueState expected, long now) {
    if (rescuesByRescuer.get(rescuerId) != expected) return;
    Player rescuer = Bukkit.getPlayer(rescuerId);
    Player target = Bukkit.getPlayer(expected.targetId());
    CraftovGame game = games.find(expected.gameId()).orElse(null);
    boolean valid = rescuer != null && target != null
        && activeParticipant(game, rescuerId)
        && activeParticipant(game, expected.targetId())
        && CraftovTeamRules.sameTeam(game, rescuerId, expected.targetId())
        && DownedFlowPolicy.canContinueRescue(true, true,
            combat.stateOf(rescuerId) == PlayerCombatState.ALIVE,
            combat.stateOf(expected.targetId()) == PlayerCombatState.DOWNED,
            rescuer.isSneaking(), sameGameWorld(rescuer, target),
            sameGameWorld(rescuer, target)
                ? rescuer.getLocation().distanceSquared(target.getLocation())
                : Double.POSITIVE_INFINITY,
            RESCUE_DISTANCE_SQUARED);
    if (!valid) {
      cancelRescue(rescuerId, true);
      return;
    }
    float progress = DownedFlowPolicy.progress(expected.startedAtNanos(), now,
        expected.durationNanos());
    expected.bar().progress(progress);
    if (progress >= 1) completeRescue(rescuer, target, game);
  }

  private void completeRescue(Player rescuer, Player target, CraftovGame game) {
    UUID rescuerId = rescuer.getUniqueId();
    UUID targetId = target.getUniqueId();
    RescueState rescue = rescuesByRescuer.remove(rescuerId);
    if (rescue == null || !rescue.targetId().equals(targetId)) return;
    rescuersByTarget.remove(targetId, rescuerId);
    rescuer.hideBossBar(rescue.bar());
    clearDowned(targetId, false);
    downed.revive(targetId, rescuerId);
    target.setHealth(Math.max(1, maximumHealth(target) * 0.4));
    target.setNoDamageTicks(REVIVE_INVULNERABILITY_TICKS);
    target.setLastDamage(0);
    target.showTitle(Title.title(messages.raw("downed.revived-title"),
        messages.raw("downed.revived-subtitle",
            Placeholder.unparsed("player", rescuer.getName()))));
    target.sendMessage(messages.raw("downed.revived-target",
        Placeholder.unparsed("player", rescuer.getName())));
    rescuer.sendMessage(messages.raw("downed.revived-rescuer",
        Placeholder.unparsed("player", target.getName())));
    notifyAliveTeammates(game, target, "downed.teammate-revived");
  }

  private void finishAsDeath(Player player) {
    UUID playerId = player.getUniqueId();
    cancelRescue(playerId, false);
    cancelRescueForTarget(playerId, false);
    clearDowned(playerId, false);
    combat.setState(playerId, PlayerCombatState.DEAD);
    if (!player.isDead() && player.getHealth() > 0) player.setHealth(0);
  }

  private void cancelRescueForTarget(UUID targetId, boolean notify) {
    UUID rescuerId = rescuersByTarget.get(targetId);
    if (rescuerId != null) cancelRescue(rescuerId, notify);
  }

  private void cancelRescue(UUID rescuerId, boolean notify) {
    RescueState rescue = rescuesByRescuer.remove(rescuerId);
    if (rescue == null) return;
    rescuersByTarget.remove(rescue.targetId(), rescuerId);
    Player rescuer = Bukkit.getPlayer(rescuerId);
    if (rescuer != null) {
      rescuer.hideBossBar(rescue.bar());
      if (notify) rescuer.sendActionBar(messages.raw("downed.rescue-cancelled"));
    }
    if (notify) {
      Player target = Bukkit.getPlayer(rescue.targetId());
      if (target != null) target.sendActionBar(messages.raw("downed.rescue-cancelled-target"));
    }
  }

  private void clearDowned(UUID playerId, boolean resetCombat) {
    DownedState state = downedPlayers.remove(playerId);
    if (state == null) return;
    Player player = Bukkit.getPlayer(playerId);
    if (player != null) {
      player.hideBossBar(state.bar());
      PotionEffect current = player.getPotionEffect(PotionEffectType.SLOWNESS);
      if (current != null && current.getAmplifier() == SLOWNESS_AMPLIFIER) {
        player.removePotionEffect(PotionEffectType.SLOWNESS);
      }
      if (state.previousSlowness() != null) {
        player.addPotionEffect(state.previousSlowness());
      }
    }
    if (resetCombat && combat.stateOf(playerId) == PlayerCombatState.DOWNED) {
      combat.reset(playerId);
    }
  }

  private boolean hasAliveTeammate(CraftovGame game, UUID victimId) {
    CraftovPlayer victim = participant(game, victimId);
    if (victim == null || victim.teamId().isEmpty()) return false;
    int teamId = victim.teamId().getAsInt();
    for (CraftovPlayer candidate : game.players()) {
      if (candidate.playerId().equals(victimId) || candidate.extracted()
          || candidate.teamId().isEmpty() || candidate.teamId().getAsInt() != teamId
          || combat.stateOf(candidate.playerId()) != PlayerCombatState.ALIVE) continue;
      Player online = Bukkit.getPlayer(candidate.playerId());
      if (online != null && online.isOnline()
          && worlds.gameByWorld(online.getWorld().getName()).filter(game.id()::equals).isPresent()) {
        return true;
      }
    }
    return false;
  }

  private void notifyAliveTeammates(CraftovGame game, Player subject, String messageKey) {
    CraftovPlayer participant = participant(game, subject.getUniqueId());
    if (participant == null || participant.teamId().isEmpty()) return;
    int teamId = participant.teamId().getAsInt();
    for (CraftovPlayer candidate : game.players()) {
      if (candidate.playerId().equals(subject.getUniqueId()) || candidate.extracted()
          || candidate.teamId().isEmpty() || candidate.teamId().getAsInt() != teamId) continue;
      Player teammate = Bukkit.getPlayer(candidate.playerId());
      if (teammate != null && teammate.isOnline()) {
        teammate.sendMessage(messages.raw(messageKey,
            Placeholder.unparsed("player", subject.getName())));
      }
    }
  }

  private boolean hostileFollowUp(EntityDamageEvent event, CraftovGame game, UUID victimId) {
    if (!(event instanceof EntityDamageByEntityEvent byEntity)) return false;
    Player attacker = playerAttacker(byEntity.getDamager());
    if (attacker == null) return true;
    return activeParticipant(game, attacker.getUniqueId())
        && !CraftovTeamRules.sameTeam(game, attacker.getUniqueId(), victimId);
  }

  private Optional<CraftovGame> gameOf(Player player) {
    return worlds.gameByWorld(player.getWorld().getName()).flatMap(games::find);
  }

  private static boolean activeParticipant(@Nullable CraftovGame game, UUID playerId) {
    return game != null && game.state() == CraftovGameState.RUNNING
        && game.players().stream().anyMatch(player -> player.playerId().equals(playerId)
            && !player.extracted());
  }

  private static @Nullable CraftovPlayer participant(CraftovGame game, UUID playerId) {
    return game.players().stream().filter(player -> player.playerId().equals(playerId))
        .findFirst().orElse(null);
  }

  private static boolean sameGameWorld(Player first, Player second) {
    return first.getWorld().equals(second.getWorld());
  }

  private static boolean isDowned(Player player, CombatService combat) {
    return combat.stateOf(player.getUniqueId()) == PlayerCombatState.DOWNED;
  }

  private boolean isDowned(Player player) {
    return isDowned(player, combat);
  }

  private static double maximumHealth(Player player) {
    AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
    return attribute == null ? 20 : Math.max(1, attribute.getValue());
  }

  private static @Nullable Player playerAttacker(Entity entity) {
    if (entity instanceof Player player) return player;
    if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
      return player;
    }
    return null;
  }

  private record PlayerGameKey(CraftovGameId gameId, UUID playerId) {}

  private record DownedState(CraftovGameId gameId, long startedAtNanos, long deadlineNanos,
                             BossBar bar, @Nullable PotionEffect previousSlowness) {}

  private record RescueState(CraftovGameId gameId, UUID targetId, long startedAtNanos,
                             long durationNanos, BossBar bar) {}
}
