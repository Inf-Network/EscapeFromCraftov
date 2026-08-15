package com.birditch.escapefromcraftov.game.plugin.ai;

import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovStartedEvent;
import com.birditch.escapefromcraftov.api.combat.CombatService;
import com.birditch.escapefromcraftov.api.combat.PlayerCombatState;
import com.birditch.escapefromcraftov.api.game.CraftovAiZone;
import com.birditch.escapefromcraftov.api.game.CraftovAiWaypoint;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapEvent;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.loot.LootRollContext;
import com.birditch.escapefromcraftov.api.loot.LootService;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.RaidActionRuntimePolicy;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import com.birditch.escapefromcraftov.game.plugin.mapevent.MapEventBossPolicy;
import com.birditch.escapefromcraftov.game.plugin.mapevent.MapEventRuntime;
import com.birditch.escapefromcraftov.game.plugin.mapevent.MapEventTracker;
import com.birditch.escapefromcraftov.game.plugin.ownership.LootClaim;
import com.birditch.escapefromcraftov.game.plugin.ownership.LootOwnershipController;
import com.birditch.escapefromcraftov.game.plugin.report.RaidResultRuntime;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
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
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Runs the authored AI zones of every live instance world.
 *
 * <p>All state and Bukkit access are main-thread confined. Each spawned mob is
 * persistent and carries its game/zone owner in PDC, so a death can only refill
 * the zone that owned it and cleanup never reaches another instance. Tactical
 * decisions and new paths use fixed per-tick budgets; all distances and delays
 * below are Craftov server tuning, not official values from another game.</p>
 */
public final class CraftovAiRuntime implements Listener, AutoCloseable {

  private static final Duration WORLD_READY_RETRY = Duration.ofSeconds(1);
  private static final Duration FAILED_SPAWN_RETRY = Duration.ofSeconds(5);
  private static final Duration TACTICS_TICK = Duration.ofMillis(100);
  private static final Duration PATROL_PAUSE = Duration.ofSeconds(4);
  private static final int MAXIMUM_AGENT_DECISIONS_PER_TICK = 8;
  private static final int MAXIMUM_PATHS_PER_TICK = 4;
  private static final int MAXIMUM_PROJECTILE_PRUNE_CHECKS_PER_TICK = 16;
  private static final double HOME_REACHED_DISTANCE_SQUARED = 2.5 * 2.5;
  private static final double PATROL_WAYPOINT_REACHED_DISTANCE_SQUARED = 1.75 * 1.75;

  private final CraftovGameService games;
  private final InstanceWorldIndex worlds;
  private final CraftovScheduler scheduler;
  private final LootService loot;
  private final CraftovItemStacks itemStacks;
  private final RaidResultRuntime raidReports;
  private final LootOwnershipController lootOwnership;
  private final Predicate<UUID> combatAlive;
  private final @Nullable MapEventRuntime mapEvents;
  private final @Nullable Messages messages;
  private final Logger logger;
  private final NamespacedKey gameKey;
  private final NamespacedKey zoneKey;
  private final NamespacedKey teamKey;
  private final NamespacedKey eventKey;
  private final NamespacedKey eventHealthMultiplierKey;
  private final NamespacedKey eventDamageMultiplierKey;
  private final NamespacedKey projectileDamageMultiplierKey;
  private final NamespacedKey projectileBaseDamageKey;
  private final Map<CraftovGameId, GameRuntime> runtimes = new HashMap<>();
  private final Map<CraftovGameId, ScheduledTask> pendingStarts = new HashMap<>();
  private final Map<UUID, AgentHandle> agents = new LinkedHashMap<>();
  private final ArrayDeque<RefillRequest> refillQueue = new ArrayDeque<>();
  private final AiProjectileRegistry projectileRegistry = new AiProjectileRegistry();
  private final ScheduledTask tacticsTask;
  private int tacticsCursor;
  private boolean closed;

  public CraftovAiRuntime(Plugin plugin, CraftovGameService games, InstanceWorldIndex worlds,
      CraftovScheduler scheduler, LootService loot, CraftovItemStacks itemStacks,
      RaidResultRuntime raidReports, LootOwnershipController lootOwnership, Logger logger) {
    this(plugin, games, worlds, scheduler, loot, itemStacks, raidReports, lootOwnership,
        _ -> true, null, null, logger);
  }

  /**
   * Runtime constructor used by production wiring. The legacy overload remains
   * source-compatible, but only this overload can reject domain DOWNED/DEAD players.
   */
  public CraftovAiRuntime(Plugin plugin, CraftovGameService games, InstanceWorldIndex worlds,
      CraftovScheduler scheduler, LootService loot, CraftovItemStacks itemStacks,
      RaidResultRuntime raidReports, LootOwnershipController lootOwnership,
      CombatService combat, Logger logger) {
    this(plugin, games, worlds, scheduler, loot, itemStacks, raidReports, lootOwnership,
        aliveLookup(combat), null, null, logger);
  }

  public CraftovAiRuntime(Plugin plugin, CraftovGameService games, InstanceWorldIndex worlds,
      CraftovScheduler scheduler, LootService loot, CraftovItemStacks itemStacks,
      RaidResultRuntime raidReports, LootOwnershipController lootOwnership,
      CombatService combat, MapEventRuntime mapEvents, Messages messages, Logger logger) {
    this(plugin, games, worlds, scheduler, loot, itemStacks, raidReports, lootOwnership,
        aliveLookup(combat), Preconditions.checkNotNull(mapEvents, "mapEvents"),
        Preconditions.checkNotNull(messages, "messages"), logger);
  }

  private CraftovAiRuntime(Plugin plugin, CraftovGameService games, InstanceWorldIndex worlds,
      CraftovScheduler scheduler, LootService loot, CraftovItemStacks itemStacks,
      RaidResultRuntime raidReports, LootOwnershipController lootOwnership,
      Predicate<UUID> combatAlive, @Nullable MapEventRuntime mapEvents,
      @Nullable Messages messages, Logger logger) {
    Preconditions.checkNotNull(plugin, "plugin");
    this.games = Preconditions.checkNotNull(games, "games");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.loot = Preconditions.checkNotNull(loot, "loot");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    this.raidReports = Preconditions.checkNotNull(raidReports, "raidReports");
    this.lootOwnership = Preconditions.checkNotNull(lootOwnership, "lootOwnership");
    this.combatAlive = Preconditions.checkNotNull(combatAlive, "combatAlive");
    this.mapEvents = mapEvents;
    this.messages = messages;
    this.logger = Preconditions.checkNotNull(logger, "logger");
    gameKey = new NamespacedKey(plugin, "ai_game");
    zoneKey = new NamespacedKey(plugin, "ai_zone");
    teamKey = new NamespacedKey(plugin, "ai_team");
    eventKey = new NamespacedKey(plugin, "ai_event");
    eventHealthMultiplierKey = new NamespacedKey(plugin, "ai_event_health_multiplier");
    eventDamageMultiplierKey = new NamespacedKey(plugin, "ai_event_damage_multiplier");
    projectileDamageMultiplierKey = new NamespacedKey(plugin, "ai_projectile_damage_multiplier");
    projectileBaseDamageKey = new NamespacedKey(plugin, "ai_projectile_base_damage");
    tacticsTask = scheduler.runTimer(Duration.ZERO, TACTICS_TICK, this::tickTactics);
  }

  private static Predicate<UUID> aliveLookup(CombatService combat) {
    CombatService checked = Preconditions.checkNotNull(combat, "combat");
    return playerId -> checked.stateOf(playerId) == PlayerCombatState.ALIVE;
  }

  @Subscribe
  public void onCraftovStarted(CraftovStartedEvent event) {
    activate(event.gameId());
  }

  @Subscribe
  public void onCraftovEnded(CraftovEndedEvent event) {
    stop(event.gameId());
    lootOwnership.clearGame(event.gameId());
  }

  /** Vanilla target selection is never allowed to escape the owning room. */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onAiTarget(EntityTargetLivingEntityEvent event) {
    if (!(event.getEntity() instanceof Mob mob)) return;
    AgentHandle handle = ownedAgent(mob);
    LivingEntity target = event.getTarget();
    if (target == null) return;
    if (handle == null) {
      if (taggedAi(mob)) {
        event.setTarget(null);
        event.setCancelled(true);
        mob.setTarget(null);
      }
      return;
    }
    if (!(target instanceof Player player) || !eligibleCombatTarget(handle, player)) {
      event.setTarget(null);
      event.setCancelled(true);
      if (target.getUniqueId().equals(handle.agent().targetId)) {
        handle.agent().targetId = null;
      }
      if (mob.getTarget() == target) mob.setTarget(null);
      if (!insideLeash(handle.zone().definition(), mob.getLocation())) {
        transition(handle.agent(), mob, AiTacticsPolicy.State.RETURN, System.nanoTime());
      }
      return;
    }
    alert(handle, player, System.nanoTime(), true);
  }

  /** Captures durable ownership before the shooter can die or unload. */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onAiProjectileLaunch(ProjectileLaunchEvent event) {
    Projectile projectile = event.getEntity();
    if (!(projectile.getShooter() instanceof Mob shooter)) return;
    AgentHandle handle = ownedAgent(shooter);
    if (handle == null) {
      if (taggedAi(shooter)) event.setCancelled(true);
      return;
    }
    CraftovGame game = games.find(handle.runtime().gameId())
        .filter(candidate -> candidate.state() == CraftovGameState.RUNNING).orElse(null);
    if (game == null || runtimes.get(handle.runtime().gameId()) != handle.runtime()
        || !insideLeash(handle.zone().definition(), shooter.getLocation())) {
      event.setCancelled(true);
      return;
    }
    double effectiveDifficulty = handle.zone().definition().difficulty()
        * handle.runtime().difficultyMultiplier();
    double damageMultiplier = AiRuntimePolicy.difficulty(effectiveDifficulty).damageMultiplier();
    if (handle.eventId() != null) {
      java.util.OptionalDouble combined = MapEventBossPolicy.projectileMultiplier(
          damageMultiplier, ownedBossEvent(handle, shooter));
      if (combined.isEmpty()) {
        event.setCancelled(true);
        return;
      }
      damageMultiplier = combined.getAsDouble();
    }
    PersistentDataContainer data = projectile.getPersistentDataContainer();
    data.set(gameKey, PersistentDataType.STRING, handle.runtime().gameId().value());
    data.set(zoneKey, PersistentDataType.STRING, handle.zone().definition().id());
    data.set(teamKey, PersistentDataType.STRING, handle.zone().definition().team());
    data.set(projectileDamageMultiplierKey, PersistentDataType.DOUBLE, damageMultiplier);
    data.remove(projectileBaseDamageKey);
    projectileRegistry.track(handle.runtime().gameId().value(), projectile.getUniqueId());
  }

  /** A later cancellation must not leave a historical registry entry behind. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
  public void onAiProjectileLaunchFinal(ProjectileLaunchEvent event) {
    if (!event.isCancelled()) return;
    Projectile projectile = event.getEntity();
    untrackProjectile(projectile);
    clearProjectileOwnership(projectile);
  }

  /** Impact ends registry lifetime; PDC remains until damage routing has completed. */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onAiProjectileHit(ProjectileHitEvent event) {
    untrackProjectile(event.getEntity());
  }

  /** Projectiles and melee from owned AI are fail-closed for every invalid target. */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onAiDamage(EntityDamageByEntityEvent event) {
    if (event.getDamager() instanceof Projectile projectile && taggedAi(projectile)) {
      handleAiProjectileDamage(event, projectile);
      return;
    }
    Mob source = sourceMob(event.getDamager());
    if (source == null) return;
    AgentHandle handle = ownedAgent(source);
    if (handle == null) {
      if (taggedAi(source)) event.setCancelled(true);
      return;
    }
    if (event.getEntity() instanceof Player player
        && eligibleCombatTarget(handle, player)) {
      if (handle.eventId() != null) {
        CraftovMapEvent boss = ownedBossEvent(handle, source);
        java.util.OptionalDouble scaled = MapEventBossPolicy.scaleDamage(event.getDamage(), boss);
        if (scaled.isEmpty()) {
          event.setCancelled(true);
          return;
        }
        event.setDamage(scaled.getAsDouble());
      }
      return;
    }
    event.setCancelled(true);
    if (source.getTarget() != null && source.getTarget().getUniqueId()
        .equals(event.getEntity().getUniqueId())) {
      source.setTarget(null);
      handle.agent().targetId = null;
    }
    if (!insideLeash(handle.zone().definition(), source.getLocation())) {
      transition(handle.agent(), source, AiTacticsPolicy.State.RETURN, System.nanoTime());
    }
  }

  private void handleAiProjectileDamage(EntityDamageByEntityEvent event, Projectile projectile) {
    PersistentDataContainer data = projectile.getPersistentDataContainer();
    AiProjectilePolicy.Ownership ownership = AiProjectilePolicy.ownership(
        data.get(gameKey, PersistentDataType.STRING),
        data.get(zoneKey, PersistentDataType.STRING),
        data.get(projectileDamageMultiplierKey, PersistentDataType.DOUBLE)).orElse(null);
    GameRuntime runtime = ownership == null ? null : runtimeByValue(ownership.gameId());
    ZoneRuntime zone = runtime == null || ownership == null
        ? null : runtime.zones().get(ownership.zoneId());
    Player target = event.getEntity() instanceof Player player ? player : null;
    boolean runtimeActive = runtime != null && runtimes.get(runtime.gameId()) == runtime
        && games.find(runtime.gameId())
            .filter(candidate -> candidate.state() == CraftovGameState.RUNNING).isPresent();
    boolean sameInstanceWorld = runtime != null && target != null
        && runtime.worldName().equals(projectile.getWorld().getName())
        && runtime.worldName().equals(target.getWorld().getName());
    boolean targetEligible = runtimeActive && target != null && eligibleTarget(runtime, target);
    boolean projectileInsideLeash = zone != null
        && insideLeash(zone.definition(), projectile.getLocation());
    boolean targetInsideLeash = zone != null && target != null
        && insideLeash(zone.definition(), target.getLocation());
    if (ownership == null || zone == null || !AiProjectilePolicy.eligible(
        new AiProjectilePolicy.HitFacts(runtimeActive, sameInstanceWorld, targetEligible,
            projectileInsideLeash, targetInsideLeash))) {
      event.setCancelled(true);
      return;
    }
    Double storedBaseDamage = data.get(projectileBaseDamageKey, PersistentDataType.DOUBLE);
    AiProjectilePolicy.ScaledDamage scaled = AiProjectilePolicy.scale(
        event.getDamage(), storedBaseDamage, ownership.damageMultiplier()).orElse(null);
    if (scaled == null) {
      event.setCancelled(true);
      return;
    }
    if (storedBaseDamage == null) {
      data.set(projectileBaseDamageKey, PersistentDataType.DOUBLE, scaled.baseDamage());
    }
    event.setDamage(scaled.damage());
  }

  /**
   * Only an effective, uncancelled hit by an eligible participant raises an alert.
   * MONITOR ordering prevents this plugin's isolation/friendly-fire cancellations
   * from becoming tactical information.
   */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onAiDamaged(EntityDamageByEntityEvent event) {
    if (event.getFinalDamage() <= 0) return;
    AgentHandle handle = ownedAgent(event.getEntity());
    if (handle == null) return;
    Player attacker = playerAttacker(event.getDamager());
    if (attacker == null || !eligibleTarget(handle.runtime(), attacker)) return;
    long now = System.nanoTime();
    if (eligibleCombatTarget(handle, attacker)) {
      alert(handle, attacker, now, true);
    } else {
      retreatAfterBoundaryHit(handle, attacker, now);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onAiDeath(EntityDeathEvent event) {
    PersistentDataContainer data = event.getEntity().getPersistentDataContainer();
    String rawGame = data.get(gameKey, PersistentDataType.STRING);
    String rawZone = data.get(zoneKey, PersistentDataType.STRING);
    if (rawGame == null || rawZone == null) return;
    // Authored AI must never leak vanilla equipment or mob drops into the
    // durable economy. Preserve only already-tagged Craftov items contributed
    // by another compatible handler, then append this runtime's reward.
    event.getDrops().removeIf(stack -> !itemStacks.isCraftovItem(stack));
    event.setDroppedExp(0);
    AgentHandle handle = agents.get(event.getEntity().getUniqueId());
    GameRuntime runtime = handle == null ? null : handle.runtime();
    if (runtime == null || !runtime.worldName().equals(event.getEntity().getWorld().getName())) {
      return;
    }
    ZoneRuntime zone = handle.zone();
    if (!runtime.gameId().value().equals(rawGame)
        || !zone.definition().id().equals(rawZone)
        || zone.agents().get(event.getEntity().getUniqueId()) != handle.agent()) return;
    agents.remove(event.getEntity().getUniqueId());
    zone.agents().remove(event.getEntity().getUniqueId());
    if (handle.eventId() != null) {
      CraftovMapEvent boss = ownedBossEvent(handle, event.getEntity());
      MapEventRuntime eventRuntime = mapEvents;
      Player killer = event.getEntity().getKiller();
      if (boss == null || eventRuntime == null) {
        if (eventRuntime != null) loseBoss(handle);
        return;
      }
      if (eventRuntime.complete(runtime.gameId(), handle.eventId(),
          event.getEntity().getUniqueId(), killer == null ? null : killer.getUniqueId())
          .isEmpty()) return;
      announce(runtime, "map-event.completed", boss);
      reward(event, runtime, zone);
      return;
    }
    scheduleRefill(runtime, zone, Duration.ofSeconds(zone.definition().respawnSeconds()));
    reward(event, runtime, zone);
  }

  private void reward(EntityDeathEvent event, GameRuntime runtime, ZoneRuntime zone) {
    Player killer = event.getEntity().getKiller();
    if (killer == null || worlds.gameByWorld(killer.getWorld().getName())
        .filter(runtime.gameId()::equals).isEmpty()) {
      return;
    }
    CraftovGame game = games.find(runtime.gameId())
        .filter(candidate -> candidate.state() == CraftovGameState.RUNNING)
        .orElse(null);
    if (game == null || game.players().stream()
        .noneMatch(player -> player.playerId().equals(killer.getUniqueId()))) {
      return;
    }
    LootClaim claim = lootOwnership.claimForKiller(game, killer.getUniqueId());
    double effectiveDifficulty = zone.definition().difficulty() * runtime.difficultyMultiplier();
    AiRuntimePolicy.LootProfile profile = AiRuntimePolicy.loot(
        effectiveDifficulty, runtime.mode(), runtime.difficulty(), runtime.actionProfile(),
        RaidActionRuntimePolicy.sourceSeed(runtime.gameId(),
            "ai-drop:" + event.getEntity().getUniqueId()));
    raidReports.recordPveKill(runtime.gameId(), killer.getUniqueId());
    try {
      LootRollContext context = new LootRollContext(killer.getUniqueId(), runtime.gameId());
      List<ItemStack> rewards = new ArrayList<>();
      for (int roll = 0; roll < profile.rolls(); roll++) {
        for (ItemInstance instance : loot.roll(profile.tableId(), context)) {
          rewards.addAll(itemStacks.fromInstance(instance));
        }
      }
      event.getDrops().addAll(rewards);
    } catch (RuntimeException failure) {
      logger.log(Level.WARNING, "生成 AI 击杀物资失败: " + runtime.gameId().value() + "/"
          + zone.definition().id() + "/T" + profile.tier(), failure);
    } finally {
      // ItemSpawnEvent registers these PDC claims on the actual spawned Item
      // entities; marking the death list alone is not treated as runtime state.
      event.getDrops().stream().filter(itemStacks::isCraftovItem)
          .forEach(stack -> lootOwnership.protectDrop(stack, claim));
    }
  }

  private void announce(GameRuntime runtime, String key, CraftovMapEvent event) {
    Messages configured = messages;
    if (configured == null) return;
    CraftovGame game = games.find(runtime.gameId())
        .filter(candidate -> candidate.state() == CraftovGameState.RUNNING).orElse(null);
    if (game == null) return;
    Component message;
    try {
      message = configured.raw(key, Placeholder.unparsed("event", event.displayName()));
    } catch (RuntimeException failure) {
      logger.log(Level.WARNING, "渲染地图事件消息失败: " + key, failure);
      return;
    }
    for (CraftovPlayer participant : game.players()) {
      if (participant.extracted()) continue;
      Player online = Bukkit.getPlayer(participant.playerId());
      if (online != null && online.isOnline()
          && runtime.worldName().equals(online.getWorld().getName())) {
        try {
          online.sendMessage(message);
        } catch (RuntimeException failure) {
          logger.log(Level.FINE, "发送地图事件消息失败: " + participant.playerId(), failure);
        }
      }
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    tacticsTask.cancel();
    for (ScheduledTask task : pendingStarts.values()) task.cancel();
    pendingStarts.clear();
    for (CraftovGameId gameId : List.copyOf(runtimes.keySet())) stop(gameId);
  }

  private void activate(CraftovGameId gameId) {
    if (closed || runtimes.containsKey(gameId)) return;
    CraftovGame game = games.find(gameId)
        .filter(candidate -> candidate.state() == CraftovGameState.RUNNING)
        .orElse(null);
    String worldName = worlds.isReady(gameId) ? worlds.worldOf(gameId).orElse(null) : null;
    World world = worldName == null ? null : Bukkit.getWorld(worldName);
    if (game == null) {
      cancelPendingStart(gameId);
      return;
    }
    if (world == null) {
      scheduleStartRetry(gameId);
      return;
    }
    cancelPendingStart(gameId);
    List<CraftovMapEvent> eventDefinitions = mapEvents == null ? List.of()
        : game.mapEvents().stream().filter(CraftovMapEvent::enabled).toList();
    if (game.aiZones().stream().anyMatch(zone -> !AiPatrolPolicy.validRoute(zone))) {
      logger.severe("AI 运行时拒绝无效巡逻路线地图: " + gameId.value());
      return;
    }
    java.util.Set<String> eventZoneIds = eventDefinitions.stream()
        .map(CraftovMapEvent::aiZoneId).collect(java.util.stream.Collectors.toUnmodifiableSet());
    List<AiRuntimePolicy.ZoneCapacity> capacities = new ArrayList<>();
    game.aiZones().stream().filter(zone -> !eventZoneIds.contains(zone.id()))
        .map(zone -> new AiRuntimePolicy.ZoneCapacity(zone.id(), zone.maximum()))
        .forEach(capacities::add);
    eventDefinitions.stream()
        .map(definition -> new AiRuntimePolicy.ZoneCapacity("event-" + definition.id(), 1))
        .forEach(capacities::add);
    AiRuntimePolicy.CapacityCheck capacity = AiRuntimePolicy.capacity(capacities);
    if (!capacity.allowed()) {
      logger.severe("AI 运行时拒绝超限地图 " + gameId.value() + ": " + capacity.reason()
          + "（每区上限 " + AiRuntimePolicy.MAXIMUM_ZONE_POPULATION + "，每局上限 "
          + AiRuntimePolicy.MAXIMUM_GAME_POPULATION + "）");
      return;
    }
    RaidActionProfile actionProfile = game.actionProfile().orElse(null);
    GameRuntime runtime = new GameRuntime(
        gameId, worldName,
        RaidActionRuntimePolicy.aiMultiplier(game.mode(), game.difficulty(), actionProfile),
        game.mode(), game.difficulty(), actionProfile,
        new LinkedHashMap<>());
    runtimes.put(gameId, runtime);
    for (CraftovAiZone definition : game.aiZones()) {
      boolean eventOnly = eventZoneIds.contains(definition.id());
      long seed = seed(gameId, definition.id());
      int target = eventOnly ? 0 : AiRuntimePolicy.targetPopulation(
          definition.minimum(), definition.maximum(), seed);
      ZoneRuntime zone = new ZoneRuntime(definition, target, eventOnly, new LinkedHashMap<>());
      runtime.zones().put(definition.id(), zone);
      if (!eventOnly) enqueueRefill(runtime, zone);
    }
    logger.info("AI 区域运行时已启动: " + gameId.value() + "，区域 "
        + runtime.zones().size() + " 个，目标敌人 " + runtime.zones().values().stream()
            .mapToInt(ZoneRuntime::targetPopulation).sum() + " 名");
  }

  private void scheduleStartRetry(CraftovGameId gameId) {
    if (pendingStarts.containsKey(gameId)) return;
    ScheduledTask task = scheduler.runLater(WORLD_READY_RETRY, () -> {
      pendingStarts.remove(gameId);
      activate(gameId);
    });
    pendingStarts.put(gameId, task);
  }

  private void cancelPendingStart(CraftovGameId gameId) {
    ScheduledTask task = pendingStarts.remove(gameId);
    if (task != null) task.cancel();
  }

  private void enqueueRefill(GameRuntime runtime, ZoneRuntime zone) {
    if (closed || runtimes.get(runtime.gameId()) != runtime || zone.refillQueued
        || zone.eventOnly() || zone.targetPopulation() <= zone.agents().size()) return;
    zone.refillQueued = true;
    refillQueue.addLast(new RefillRequest(runtime, zone));
  }

  private void processRefillQueue() {
    int attempt = 0;
    MapEventRuntime eventRuntime = mapEvents;
    while (eventRuntime != null && attempt < AiRuntimePolicy.MAXIMUM_SPAWN_ATTEMPTS_PER_TICK) {
      MapEventTracker.Activation activation = eventRuntime.claimNextActivation().orElse(null);
      if (activation == null) break;
      processEventActivation(eventRuntime, activation);
      attempt++;
    }
    for (;
        attempt < AiRuntimePolicy.MAXIMUM_SPAWN_ATTEMPTS_PER_TICK
            && !refillQueue.isEmpty(); attempt++) {
      RefillRequest request = refillQueue.removeFirst();
      GameRuntime runtime = request.runtime();
      ZoneRuntime zone = request.zone();
      zone.refillQueued = false;
      CraftovGame game = games.find(runtime.gameId())
          .filter(candidate -> candidate.state() == CraftovGameState.RUNNING).orElse(null);
      if (game == null || runtimes.get(runtime.gameId()) != runtime
          || zone.targetPopulation() <= zone.agents().size()) continue;
      World world = Bukkit.getWorld(runtime.worldName());
      Mob mob = world == null ? null : spawn(runtime, zone, world, null).mob();
      if (mob == null) {
        logger.warning("AI 区域没有已加载的安全生成点，将稍后重试: "
            + runtime.gameId().value() + "/" + zone.definition().id() + " ("
            + zone.agents().size() + "/" + zone.targetPopulation() + ")");
        scheduleRefill(runtime, zone, FAILED_SPAWN_RETRY);
        continue;
      }
      long spawnOrdinal = zone.nextAgentOrdinal++;
      AgentRuntime agent = new AgentRuntime(mob.getUniqueId(), mob.getLocation(),
          System.nanoTime(), zone.definition(), spawnOrdinal);
      zone.agents().put(mob.getUniqueId(), agent);
      agents.put(mob.getUniqueId(), new AgentHandle(runtime, zone, agent, null));
      enqueueRefill(runtime, zone);
    }
  }

  private void processEventActivation(MapEventRuntime eventRuntime,
      MapEventTracker.Activation activation) {
    CraftovMapEvent definition = activation.event();
    GameRuntime runtime = runtimes.get(activation.gameId());
    ZoneRuntime zone = runtime == null ? null : runtime.zones().get(definition.aiZoneId());
    CraftovGame game = games.find(activation.gameId())
        .filter(candidate -> candidate.state() == CraftovGameState.RUNNING).orElse(null);
    if (game == null) {
      eventRuntime.fail(activation);
      return;
    }
    if (runtime == null) {
      if (!eventRuntime.retryUnloaded(activation)) {
        logger.warning("Boss 事件等待实例运行时超时: " + activation.gameId().value() + "/"
            + definition.id());
      }
      return;
    }
    if (activation.attempt() == 1) announce(runtime, "map-event.activated", definition);
    if (zone == null || !zone.eventOnly() || !MapEventBossPolicy.valid(definition)) {
      if (eventRuntime.fail(activation) && runtime != null) {
        announce(runtime, "map-event.failed", definition);
      }
      return;
    }
    World world = Bukkit.getWorld(runtime.worldName());
    if (world == null) {
      retryEvent(eventRuntime, runtime, activation);
      return;
    }
    SpawnAttempt result = spawn(runtime, zone, world, definition);
    if (result.status() == CraftovAiSpawnResolver.Status.UNLOADED) {
      retryEvent(eventRuntime, runtime, activation);
      return;
    }
    Mob mob = result.mob();
    if (mob == null) {
      if (eventRuntime.fail(activation)) announce(runtime, "map-event.failed", definition);
      return;
    }
    if (!eventRuntime.spawned(activation, mob.getUniqueId())) {
      mob.remove();
      return;
    }
    long spawnOrdinal = zone.nextAgentOrdinal++;
    AgentRuntime agent = new AgentRuntime(mob.getUniqueId(), mob.getLocation(), System.nanoTime(),
        zone.definition(), spawnOrdinal);
    zone.agents().put(mob.getUniqueId(), agent);
    agents.put(mob.getUniqueId(), new AgentHandle(runtime, zone, agent, definition.id()));
    announce(runtime, "map-event.spawned", definition);
  }

  private void retryEvent(MapEventRuntime eventRuntime, GameRuntime runtime,
      MapEventTracker.Activation activation) {
    if (!eventRuntime.retryUnloaded(activation)) {
      announce(runtime, "map-event.failed", activation.event());
    } else if (activation.attempt() == 1) {
      logger.info("Boss 事件等待已加载区块: " + runtime.gameId().value() + "/"
          + activation.event().id());
    }
  }

  private SpawnAttempt spawn(GameRuntime runtime, ZoneRuntime zone, World world,
      @Nullable CraftovMapEvent event) {
    long sequence = zone.nextSequence++;
    long seed = seed(runtime.gameId(), zone.definition().id()) ^ (sequence * 0x9e3779b97f4a7c15L);
    CraftovAiSpawnResolver.Resolution resolution =
        CraftovAiSpawnResolver.resolveResult(world, zone.definition(), seed);
    Location location = resolution.location();
    if (location == null) return new SpawnAttempt(resolution.status(), null);
    EntityType type = entityType(zone.definition().aiType());
    Entity spawned = null;
    try {
      spawned = world.spawnEntity(location, type, CreatureSpawnEvent.SpawnReason.CUSTOM);
      if (!(spawned instanceof Mob mob)) {
        spawned.remove();
        return new SpawnAttempt(CraftovAiSpawnResolver.Status.NO_SAFE_POSITION, null);
      }
      own(mob, runtime.gameId(), zone.definition(), event);
      configure(mob, zone.definition(), runtime.difficultyMultiplier());
      if (event != null) configureBoss(mob, event);
      mob.setAware(false);
      return new SpawnAttempt(CraftovAiSpawnResolver.Status.FOUND, mob);
    } catch (RuntimeException exception) {
      if (spawned != null) spawned.remove();
      logger.log(Level.WARNING, "生成 AI 失败: " + runtime.gameId().value() + "/"
          + zone.definition().id() + "/" + type, exception);
      return new SpawnAttempt(CraftovAiSpawnResolver.Status.NO_SAFE_POSITION, null);
    }
  }

  private void own(Mob mob, CraftovGameId gameId, CraftovAiZone zone,
      @Nullable CraftovMapEvent event) {
    PersistentDataContainer data = mob.getPersistentDataContainer();
    data.set(gameKey, PersistentDataType.STRING, gameId.value());
    data.set(zoneKey, PersistentDataType.STRING, zone.id());
    data.set(teamKey, PersistentDataType.STRING, zone.team());
    if (event == null) {
      data.remove(eventKey);
      data.remove(eventHealthMultiplierKey);
      data.remove(eventDamageMultiplierKey);
    } else {
      if (!MapEventBossPolicy.valid(event)) {
        throw new IllegalArgumentException("invalid boss event multipliers: " + event.id());
      }
      data.set(eventKey, PersistentDataType.STRING, event.id());
      data.set(eventHealthMultiplierKey, PersistentDataType.DOUBLE, event.healthMultiplier());
      data.set(eventDamageMultiplierKey, PersistentDataType.DOUBLE, event.damageMultiplier());
    }
    mob.addScoreboardTag("craftov_ai");
    mob.setPersistent(true);
    mob.setRemoveWhenFarAway(false);
    mob.setCanPickupItems(false);
  }

  private static void configureBoss(Mob mob, CraftovMapEvent event) {
    if (!MapEventBossPolicy.valid(event)) {
      throw new IllegalArgumentException("invalid boss event multipliers: " + event.id());
    }
    scale(mob, Attribute.MAX_HEALTH, event.healthMultiplier());
    AttributeInstance health = mob.getAttribute(Attribute.MAX_HEALTH);
    if (health != null) mob.setHealth(health.getValue());
    mob.customName(Component.text(event.displayName(), NamedTextColor.DARK_RED));
    mob.setCustomNameVisible(true);
  }

  private static void configure(Mob mob, CraftovAiZone zone, double raidDifficultyMultiplier) {
    double effectiveDifficulty = zone.difficulty() * raidDifficultyMultiplier;
    AiRuntimePolicy.DifficultyProfile profile = AiRuntimePolicy.difficulty(effectiveDifficulty);
    scale(mob, Attribute.MAX_HEALTH, profile.healthMultiplier());
    scale(mob, Attribute.ATTACK_DAMAGE, profile.damageMultiplier());
    scale(mob, Attribute.MOVEMENT_SPEED, profile.movementMultiplier());
    AttributeInstance health = mob.getAttribute(Attribute.MAX_HEALTH);
    if (health != null) mob.setHealth(health.getValue());
    int stars = Math.clamp((int) Math.ceil(effectiveDifficulty), 1, 5);
    mob.customName(Component.text("敌对 AI · " + zone.id() + " " + "★".repeat(stars),
        profile.equipmentTier() >= 2 ? NamedTextColor.RED : NamedTextColor.GOLD));
    mob.setCustomNameVisible(profile.equipmentTier() >= 2);
    equip(mob, profile.equipmentTier());
  }

  private static void scale(Mob mob, Attribute attribute, double multiplier) {
    AttributeInstance instance = mob.getAttribute(attribute);
    if (instance == null) return;
    instance.setBaseValue(Math.max(0.01, instance.getBaseValue() * multiplier));
  }

  private static void equip(Mob mob, int tier) {
    EntityEquipment equipment = mob.getEquipment();
    if (equipment == null || tier <= 0) return;
    if (!(mob instanceof Pillager) && !(mob instanceof Skeleton)) {
      equipment.setItemInMainHand(new ItemStack(switch (tier) {
        case 1 -> Material.STONE_SWORD;
        case 2 -> Material.IRON_SWORD;
        default -> Material.DIAMOND_SWORD;
      }));
      equipment.setItemInMainHandDropChance(0);
    }
    equipment.setHelmet(new ItemStack(switch (tier) {
      case 1 -> Material.LEATHER_HELMET;
      case 2 -> Material.IRON_HELMET;
      default -> Material.DIAMOND_HELMET;
    }));
    equipment.setHelmetDropChance(0);
    if (tier >= 2) {
      equipment.setChestplate(new ItemStack(tier == 2
          ? Material.IRON_CHESTPLATE : Material.DIAMOND_CHESTPLATE));
      equipment.setChestplateDropChance(0);
    }
  }

  private static EntityType entityType(String raw) {
    String normalized = raw.strip().toLowerCase(Locale.ROOT).replace('-', '_');
    return switch (normalized) {
      case "scav", "scavenger", "raider", "soldier", "guard" -> EntityType.PILLAGER;
      case "husk" -> EntityType.HUSK;
      case "drowned" -> EntityType.DROWNED;
      case "skeleton" -> EntityType.SKELETON;
      case "stray" -> EntityType.STRAY;
      case "vindicator" -> EntityType.VINDICATOR;
      case "zombie" -> EntityType.ZOMBIE;
      default -> safeEntityType(normalized);
    };
  }

  private static EntityType safeEntityType(String normalized) {
    try {
      EntityType type = EntityType.valueOf(normalized.toUpperCase(Locale.ROOT));
      Class<? extends Entity> entityClass = type.getEntityClass();
      return entityClass != null && Mob.class.isAssignableFrom(entityClass)
          ? type : EntityType.ZOMBIE;
    } catch (IllegalArgumentException ignored) {
      return EntityType.ZOMBIE;
    }
  }

  private void tickTactics() {
    if (closed) return;
    processRefillQueue();
    projectileRegistry.prune(MAXIMUM_PROJECTILE_PRUNE_CHECKS_PER_TICK, projectileId -> {
      Entity entity = Bukkit.getEntity(projectileId);
      return entity instanceof Projectile && entity.isValid();
    });
    if (agents.isEmpty()) return;
    long now = System.nanoTime();
    List<AgentHandle> snapshot = List.copyOf(agents.values());
    Map<CraftovGameId, List<Player>> targetsByGame = new HashMap<>();
    PathBudget paths = new PathBudget(MAXIMUM_PATHS_PER_TICK);
    int decisions = Math.min(MAXIMUM_AGENT_DECISIONS_PER_TICK, snapshot.size());
    for (int processed = 0; processed < decisions; processed++) {
      int index = Math.floorMod(tacticsCursor++, snapshot.size());
      AgentHandle handle = snapshot.get(index);
      if (agents.get(handle.agent().entityId()) != handle) continue;
      List<Player> targets = targetsByGame.computeIfAbsent(handle.runtime().gameId(),
          _ -> eligibleTargets(handle.runtime()));
      tickAgent(handle, targets, now, paths);
    }
    if (tacticsCursor > 1_000_000_000) tacticsCursor %= snapshot.size();
  }

  private void tickAgent(AgentHandle handle, List<Player> targets, long now, PathBudget paths) {
    Entity entity = Bukkit.getEntity(handle.agent().entityId());
    World runtimeWorld = Bukkit.getWorld(handle.runtime().worldName());
    if (!(entity instanceof Mob mob)) {
      // A persistent entity may simply be serialized with an unloaded chunk.
      // Keep its slot reserved; replacing it would duplicate the AI on reload.
      if (runtimeWorld != null && AiEntityPresencePolicy.reserveIdentity(
          false, false, chunkLoaded(runtimeWorld, handle.agent().lastPosition))) return;
      detach(handle, true);
      return;
    }
    handle.agent().lastPosition = Point.of(mob.getLocation());
    if (!entity.isValid() && runtimeWorld != null && AiEntityPresencePolicy.reserveIdentity(
        true, false, chunkLoaded(runtimeWorld, handle.agent().lastPosition))) return;
    if (!entity.isValid() || !handle.runtime().worldName().equals(entity.getWorld().getName())
        || !ownedBy(entity, handle.runtime().gameId())
        || !handle.zone().definition().id().equals(entity.getPersistentDataContainer()
            .get(zoneKey, PersistentDataType.STRING))
        || (handle.eventId() != null && ownedBossEvent(handle, entity) == null)) {
      if (handle.eventId() != null && entity.isValid()) entity.remove();
      detach(handle, true);
      return;
    }
    if (mob.isDead()) return;

    AgentRuntime agent = handle.agent();
    CraftovAiZone zone = handle.zone().definition();
    Player current = target(agent.targetId, targets);
    boolean targetInsideLeash = current != null && insideLeash(zone, current.getLocation());
    boolean currentVisible = current != null && mob.hasLineOfSight(current);
    if (currentVisible && targetInsideLeash) {
      agent.lastKnown = Point.of(current.getLocation());
      agent.lastSeenAtNanos = now;
    }
    Player detected = nearestVisible(mob, zone, targets,
        AiTacticsPolicy.DEFAULT.detectionDistance());
    boolean participantNearby = targets.stream().anyMatch(player ->
        insideLeash(zone, player.getLocation())
            && distanceSquared(mob.getLocation(), player.getLocation())
                <= square(AiTacticsPolicy.DEFAULT.wakeDistance()));
    boolean actorInsideLeash = insideLeash(zone, mob.getLocation());
    boolean homeReached = agent.home.distanceSquared(mob.getLocation())
        <= HOME_REACHED_DISTANCE_SQUARED;
    Duration stateAge = elapsed(now, agent.stateSinceNanos);
    Duration sinceLastSeen = elapsed(now, agent.lastSeenAtNanos);
    AiTacticsPolicy.State next = AiTacticsPolicy.next(new AiTacticsPolicy.Input(
        agent.state, participantNearby, detected != null, current != null, currentVisible,
        targetInsideLeash, actorInsideLeash, homeReached, stateAge, sinceLastSeen),
        AiTacticsPolicy.DEFAULT);

    if (next == AiTacticsPolicy.State.ALERT) {
      Player target = detected != null ? detected : current;
      if (target != null) {
        alert(handle, target, now, true);
      } else {
        transition(agent, mob, AiTacticsPolicy.State.SEARCH, now);
      }
    } else if (next != agent.state) {
      transition(agent, mob, next, now);
    }
    applyBehavior(handle, mob, targets, now, paths);
  }

  private void applyBehavior(AgentHandle handle, Mob mob, List<Player> targets,
      long now, PathBudget paths) {
    AgentRuntime agent = handle.agent();
    switch (agent.state) {
      case DORMANT -> aware(mob, false);
      case PATROL -> {
        aware(mob, true);
        patrol(handle, mob, now, paths);
      }
      case ALERT, CHASE -> {
        aware(mob, true);
        Player target = target(agent.targetId, targets);
        if (target != null && mob.getTarget() != target) mob.setTarget(target);
      }
      case SEARCH -> {
        aware(mob, true);
        if (agent.lastKnown != null && !mob.getPathfinder().hasPath()) {
          moveTo(handle, mob, agent.lastKnown, 1.0, now, paths);
        }
      }
      case RETURN -> {
        aware(mob, true);
        if (!mob.getPathfinder().hasPath()) {
          moveTo(handle, mob, agent.home, 1.1, now, paths);
        }
      }
    }
  }

  private void patrol(AgentHandle handle, Mob mob, long now, PathBudget paths) {
    AgentRuntime agent = handle.agent();
    if (mob.getPathfinder().hasPath() || now < agent.nextPatrolAtNanos) return;
    CraftovAiZone zone = handle.zone().definition();
    if (!zone.patrolWaypoints().isEmpty()) {
      patrolAuthoredRoute(handle, mob, now, paths);
      return;
    }
    long randomSeed = seed(handle.runtime().gameId(), zone.id())
        ^ (++agent.patrolSequence * 0x9e3779b97f4a7c15L);
    SplittableRandom random = new SplittableRandom(randomSeed);
    Point destination = new Point(coordinate(random, zone.minX(), zone.maxX()) + 0.5,
        agent.home.y(), coordinate(random, zone.minZ(), zone.maxZ()) + 0.5);
    MoveAttempt result = moveTo(handle, mob, destination, 0.8, now, paths);
    if (result != MoveAttempt.BUDGET_EXHAUSTED) {
      agent.nextPatrolAtNanos = now + PATROL_PAUSE.toNanos();
    }
  }

  private void patrolAuthoredRoute(AgentHandle handle, Mob mob, long now,
      PathBudget paths) {
    AgentRuntime agent = handle.agent();
    List<CraftovAiWaypoint> route = handle.zone().definition().patrolWaypoints();
    if (agent.patrolWaypointIndex < 0 || agent.patrolWaypointIndex >= route.size()) return;
    CraftovAiWaypoint waypoint = route.get(agent.patrolWaypointIndex);
    Point destination = new Point(waypoint.x() + 0.5, waypoint.y(), waypoint.z() + 0.5);
    if (destination.distanceSquared(mob.getLocation())
        <= PATROL_WAYPOINT_REACHED_DISTANCE_SQUARED) {
      agent.patrolWaypointIndex = AiPatrolPolicy.cursorAfter(agent.patrolWaypointIndex,
          route.size(), AiPatrolPolicy.Progress.ARRIVED);
      agent.nextPatrolAtNanos = now + PATROL_PAUSE.toNanos();
      return;
    }
    MoveAttempt result = moveTo(handle, mob, destination, 0.8, now, paths);
    agent.patrolWaypointIndex = AiPatrolPolicy.cursorAfter(agent.patrolWaypointIndex,
        route.size(), patrolProgress(result));
    if (result != MoveAttempt.BUDGET_EXHAUSTED) {
      agent.nextPatrolAtNanos = now + PATROL_PAUSE.toNanos();
    }
  }

  private static AiPatrolPolicy.Progress patrolProgress(MoveAttempt result) {
    return switch (result) {
      case STARTED -> AiPatrolPolicy.Progress.PATH_STARTED;
      case FAILED -> AiPatrolPolicy.Progress.PATH_FAILED;
      case UNLOADED -> AiPatrolPolicy.Progress.UNLOADED;
      case DEFERRED -> AiPatrolPolicy.Progress.DEFERRED;
      case BUDGET_EXHAUSTED -> AiPatrolPolicy.Progress.BUDGET_EXHAUSTED;
    };
  }

  private MoveAttempt moveTo(AgentHandle handle, Mob mob, Point destination,
      double speed, long now, PathBudget paths) {
    AgentRuntime agent = handle.agent();
    if (now < agent.nextPathAttemptAtNanos) return MoveAttempt.DEFERRED;
    World world = mob.getWorld();
    if (!chunkLoaded(world, destination)) {
      agent.nextPathAttemptAtNanos = now
          + AiTacticsPolicy.DEFAULT.pathFailureBackoff().toNanos();
      return MoveAttempt.UNLOADED;
    }
    if (!paths.acquire()) return MoveAttempt.BUDGET_EXHAUSTED;
    boolean moved;
    try {
      moved = mob.getPathfinder().moveTo(destination.location(world), speed);
    } catch (RuntimeException ignored) {
      moved = false;
    }
    agent.nextPathAttemptAtNanos = now + (moved ? TACTICS_TICK.toNanos()
        : AiTacticsPolicy.DEFAULT.pathFailureBackoff().toNanos());
    return moved ? MoveAttempt.STARTED : MoveAttempt.FAILED;
  }

  private void transition(AgentRuntime agent, Mob mob, AiTacticsPolicy.State next, long now) {
    agent.state = next;
    agent.stateSinceNanos = now;
    switch (next) {
      case DORMANT -> {
        clearTarget(agent, mob);
        mob.getPathfinder().stopPathfinding();
        aware(mob, false);
      }
      case PATROL -> {
        clearTarget(agent, mob);
        mob.getPathfinder().stopPathfinding();
        agent.nextPatrolAtNanos = now;
        aware(mob, true);
      }
      case ALERT, CHASE -> {
        mob.getPathfinder().stopPathfinding();
        aware(mob, true);
      }
      case SEARCH, RETURN -> {
        clearTarget(agent, mob);
        mob.getPathfinder().stopPathfinding();
        agent.nextPathAttemptAtNanos = now;
        aware(mob, true);
      }
    }
  }

  private void alert(AgentHandle handle, Player target, long now, boolean propagate) {
    if (!eligibleTarget(handle.runtime(), target)) return;
    AgentRuntime agent = handle.agent();
    UUID targetId = target.getUniqueId();
    boolean changed = !targetId.equals(agent.targetId)
        || (agent.state != AiTacticsPolicy.State.ALERT
            && agent.state != AiTacticsPolicy.State.CHASE);
    agent.targetId = targetId;
    agent.lastKnown = Point.of(target.getLocation());
    agent.lastSeenAtNanos = now;
    if (changed) {
      agent.state = AiTacticsPolicy.State.ALERT;
      agent.stateSinceNanos = now;
    }
    Entity entity = Bukkit.getEntity(agent.entityId());
    if (entity instanceof Mob mob) {
      if (changed) mob.getPathfinder().stopPathfinding();
      aware(mob, true);
    }
    if (propagate && changed) propagateAlert(handle, target, now);
  }

  private void retreatAfterBoundaryHit(AgentHandle handle, Player attacker, long now) {
    Entity entity = Bukkit.getEntity(handle.agent().entityId());
    if (!(entity instanceof Mob mob)) return;
    handle.agent().lastKnown = Point.of(attacker.getLocation());
    handle.agent().lastSeenAtNanos = now;
    transition(handle.agent(), mob, AiTacticsPolicy.State.RETURN, now);
  }

  private void propagateAlert(AgentHandle source, Player target, long now) {
    Entity sourceEntity = Bukkit.getEntity(source.agent().entityId());
    if (!(sourceEntity instanceof Mob sourceMob)) return;
    String team = source.zone().definition().team();
    double maximumDistance = square(AiTacticsPolicy.DEFAULT.alertRadius());
    int remaining = AiTacticsPolicy.DEFAULT.maximumAlertPeers();
    for (ZoneRuntime zone : source.runtime().zones().values()) {
      if (!team.equals(zone.definition().team())) continue;
      for (AgentRuntime peer : zone.agents().values()) {
        if (remaining <= 0) return;
        if (peer.entityId().equals(source.agent().entityId())) continue;
        Entity entity = Bukkit.getEntity(peer.entityId());
        if (!(entity instanceof Mob mob) || !entity.isValid()
            || distanceSquared(sourceMob.getLocation(), mob.getLocation()) > maximumDistance) {
          continue;
        }
        AgentHandle handle = agents.get(peer.entityId());
        if (handle == null || !eligibleCombatTarget(handle, target)) continue;
        alert(handle, target, now, false);
        remaining--;
      }
    }
  }

  private List<Player> eligibleTargets(GameRuntime runtime) {
    CraftovGame game = games.find(runtime.gameId()).orElse(null);
    if (game == null || game.state() != CraftovGameState.RUNNING) return List.of();
    List<Player> result = new ArrayList<>();
    for (CraftovPlayer participant : game.players()) {
      Player player = Bukkit.getPlayer(participant.playerId());
      if (player != null && eligibleTarget(runtime, game, participant, player)) {
        result.add(player);
      }
    }
    return List.copyOf(result);
  }

  private boolean eligibleTarget(GameRuntime runtime, Player player) {
    CraftovGame game = games.find(runtime.gameId()).orElse(null);
    CraftovPlayer participant = game == null ? null : game.players().stream()
        .filter(candidate -> candidate.playerId().equals(player.getUniqueId()))
        .findFirst().orElse(null);
    return eligibleTarget(runtime, game, participant, player);
  }

  private boolean eligibleCombatTarget(AgentHandle handle, Player player) {
    if (!eligibleTarget(handle.runtime(), player)
        || !insideLeash(handle.zone().definition(), player.getLocation())) return false;
    Entity entity = Bukkit.getEntity(handle.agent().entityId());
    return entity instanceof Mob mob && entity.isValid()
        && insideLeash(handle.zone().definition(), mob.getLocation());
  }

  private boolean eligibleTarget(GameRuntime runtime, @Nullable CraftovGame game,
      @Nullable CraftovPlayer participant, Player player) {
    boolean sameInstanceWorld = runtime.worldName().equals(player.getWorld().getName())
        && worlds.gameByWorld(player.getWorld().getName())
            .filter(runtime.gameId()::equals).isPresent();
    boolean combatStateAlive;
    try {
      combatStateAlive = combatAlive.test(player.getUniqueId());
    } catch (RuntimeException ignored) {
      combatStateAlive = false;
    }
    return AiTargetPolicy.eligible(new AiTargetPolicy.Facts(
        game != null && game.state() == CraftovGameState.RUNNING,
        participant != null,
        participant != null && participant.extracted(),
        sameInstanceWorld,
        player.isOnline(),
        player.isValid(),
        player.isDead(),
        !player.isDead() && player.getHealth() > 0,
        player.getGameMode() == GameMode.SURVIVAL,
        combatStateAlive));
  }

  private static @Nullable Player nearestVisible(Mob mob, CraftovAiZone zone,
      List<Player> targets, double maximumDistance) {
    Player nearest = null;
    double nearestDistance = square(maximumDistance);
    Location origin = mob.getLocation();
    for (Player player : targets) {
      if (!insideLeash(zone, player.getLocation())) continue;
      double distance = distanceSquared(origin, player.getLocation());
      if (distance > nearestDistance || !mob.hasLineOfSight(player)) continue;
      nearest = player;
      nearestDistance = distance;
    }
    return nearest;
  }

  private static boolean insideLeash(CraftovAiZone zone, Location location) {
    return AiTacticsPolicy.insideLeash(location.getX(), location.getZ(),
        zone.minX(), zone.maxX(), zone.minZ(), zone.maxZ(),
        AiTacticsPolicy.DEFAULT.leashMargin());
  }

  private static double distanceSquared(Location first, Location second) {
    double dx = first.getX() - second.getX();
    double dy = first.getY() - second.getY();
    double dz = first.getZ() - second.getZ();
    return dx * dx + dy * dy + dz * dz;
  }

  private static double square(double value) {
    return value * value;
  }

  private static int floor(double value) {
    return (int) Math.floor(value);
  }

  private static boolean chunkLoaded(World world, Point point) {
    return world.isChunkLoaded(floor(point.x()) >> 4, floor(point.z()) >> 4);
  }

  private static int coordinate(SplittableRandom random, int minimum, int maximum) {
    return (int) random.nextLong(minimum, (long) maximum + 1L);
  }

  private static Duration elapsed(long now, long since) {
    return Duration.ofNanos(Math.max(0, now - since));
  }

  private static @Nullable Player target(@Nullable UUID targetId, List<Player> targets) {
    if (targetId == null) return null;
    return targets.stream().filter(player -> player.getUniqueId().equals(targetId))
        .findFirst().orElse(null);
  }

  private static void clearTarget(AgentRuntime agent, Mob mob) {
    agent.targetId = null;
    if (mob.getTarget() != null) mob.setTarget(null);
  }

  private static void aware(Mob mob, boolean value) {
    if (mob.isAware() != value) mob.setAware(value);
  }

  private void detach(AgentHandle handle, boolean refill) {
    if (agents.remove(handle.agent().entityId()) != handle) return;
    handle.zone().agents().remove(handle.agent().entityId());
    if (refill && handle.eventId() == null) {
      scheduleRefill(handle.runtime(), handle.zone(), FAILED_SPAWN_RETRY);
    } else if (refill && handle.eventId() != null && mapEvents != null) {
      loseBoss(handle);
    }
  }

  private void loseBoss(AgentHandle handle) {
    MapEventRuntime eventRuntime = mapEvents;
    String eventId = handle.eventId();
    if (eventRuntime == null || eventId == null) return;
    boolean retrying = eventRuntime.lostActive(handle.runtime().gameId(), eventId,
        handle.agent().entityId());
    if (!retrying && eventRuntime.state(handle.runtime().gameId(), eventId)
        == MapEventTracker.State.FAILED) {
      eventRuntime.event(handle.runtime().gameId(), eventId)
          .ifPresent(event -> announce(handle.runtime(), "map-event.failed", event));
    }
  }

  private @Nullable AgentHandle ownedAgent(Entity entity) {
    if (!(entity instanceof Mob mob)) return null;
    AgentHandle handle = agents.get(mob.getUniqueId());
    if (handle == null || !handle.runtime().worldName().equals(mob.getWorld().getName())
        || !ownedBy(mob, handle.runtime().gameId())
        || !handle.zone().definition().id().equals(mob.getPersistentDataContainer()
            .get(zoneKey, PersistentDataType.STRING))) return null;
    String taggedEvent = mob.getPersistentDataContainer()
        .get(eventKey, PersistentDataType.STRING);
    if (handle.eventId() == null ? taggedEvent != null : ownedBossEvent(handle, mob) == null) {
      return null;
    }
    return handle;
  }

  private @Nullable CraftovMapEvent ownedBossEvent(AgentHandle handle, Entity entity) {
    String eventId = handle.eventId();
    MapEventRuntime eventRuntime = mapEvents;
    if (eventId == null || eventRuntime == null
        || !eventRuntime.ownsActive(handle.runtime().gameId(), eventId,
            entity.getUniqueId())) return null;
    CraftovMapEvent definition = eventRuntime.event(handle.runtime().gameId(), eventId).orElse(null);
    PersistentDataContainer data = entity.getPersistentDataContainer();
    return definition != null && MapEventBossPolicy.matchesPdc(definition,
        data.get(eventKey, PersistentDataType.STRING),
        data.get(eventHealthMultiplierKey, PersistentDataType.DOUBLE),
        data.get(eventDamageMultiplierKey, PersistentDataType.DOUBLE)) ? definition : null;
  }

  private boolean taggedAi(Entity entity) {
    return entity.getPersistentDataContainer().has(gameKey, PersistentDataType.STRING);
  }

  private void untrackProjectile(Projectile projectile) {
    String gameId = projectile.getPersistentDataContainer()
        .get(gameKey, PersistentDataType.STRING);
    if (gameId != null) projectileRegistry.untrack(gameId, projectile.getUniqueId());
  }

  private void clearProjectileOwnership(Projectile projectile) {
    PersistentDataContainer data = projectile.getPersistentDataContainer();
    data.remove(gameKey);
    data.remove(zoneKey);
    data.remove(teamKey);
    data.remove(projectileDamageMultiplierKey);
    data.remove(projectileBaseDamageKey);
  }

  private static @Nullable Mob sourceMob(Entity entity) {
    if (entity instanceof Mob mob) return mob;
    if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Mob mob) {
      return mob;
    }
    return null;
  }

  private static @Nullable Player playerAttacker(Entity entity) {
    if (entity instanceof Player player) return player;
    if (entity instanceof Projectile projectile
        && projectile.getShooter() instanceof Player player) return player;
    return null;
  }

  private void scheduleRefill(GameRuntime runtime, ZoneRuntime zone, Duration delay) {
    if (zone.eventOnly() || zone.refillTask != null || zone.refillQueued
        || zone.targetPopulation() <= zone.agents().size()) return;
    zone.refillTask = scheduler.runLater(delay.isZero() ? Duration.ofMillis(50) : delay, () -> {
      zone.refillTask = null;
      CraftovGame game = games.find(runtime.gameId())
          .filter(candidate -> candidate.state() == CraftovGameState.RUNNING).orElse(null);
      if (game != null && runtimes.get(runtime.gameId()) == runtime) enqueueRefill(runtime, zone);
    });
  }

  private void stop(CraftovGameId gameId) {
    cancelPendingStart(gameId);
    GameRuntime runtime = runtimes.remove(gameId);
    if (runtime == null) return;
    refillQueue.removeIf(request -> request.runtime() == runtime);
    for (UUID projectileId : projectileRegistry.drain(gameId.value())) {
      Entity projectile = Bukkit.getEntity(projectileId);
      if (ownedBy(projectile, gameId)) projectile.remove();
    }
    for (ZoneRuntime zone : runtime.zones().values()) {
      if (zone.refillTask != null) zone.refillTask.cancel();
      zone.refillTask = null;
      zone.refillQueued = false;
      for (UUID entityId : List.copyOf(zone.agents().keySet())) {
        agents.remove(entityId);
        Entity entity = Bukkit.getEntity(entityId);
        if (ownedBy(entity, gameId)) entity.remove();
      }
      zone.agents().clear();
    }
    World world = Bukkit.getWorld(runtime.worldName());
    if (world != null) {
      // PDC is authoritative too: this catches an entity that another plugin
      // reloaded while its UUID was temporarily absent from Bukkit's index.
      for (Entity entity : List.copyOf(world.getEntities())) {
        if (ownedBy(entity, gameId)) entity.remove();
      }
    }
  }

  private boolean ownedBy(@Nullable Entity entity, CraftovGameId gameId) {
    if (entity == null) return false;
    return gameId.value().equals(entity.getPersistentDataContainer()
        .get(gameKey, PersistentDataType.STRING));
  }

  private @Nullable GameRuntime runtimeByValue(String gameId) {
    for (GameRuntime runtime : runtimes.values()) {
      if (runtime.gameId().value().equals(gameId)) return runtime;
    }
    return null;
  }

  private static long seed(CraftovGameId gameId, String zoneId) {
    long value = ((long) gameId.value().hashCode() << 32) ^ zoneId.hashCode();
    return value ^ 0x5deece66dL;
  }

  private record GameRuntime(
      CraftovGameId gameId,
      String worldName,
      double difficultyMultiplier,
      RaidMode mode,
      RaidDifficulty difficulty,
      @Nullable RaidActionProfile actionProfile,
      Map<String, ZoneRuntime> zones) {}

  private record AgentHandle(
      GameRuntime runtime, ZoneRuntime zone, AgentRuntime agent, @Nullable String eventId) {}

  private record RefillRequest(GameRuntime runtime, ZoneRuntime zone) {}

  private record SpawnAttempt(CraftovAiSpawnResolver.Status status, @Nullable Mob mob) {}

  private static final class AgentRuntime {
    private final UUID entityId;
    private final Point home;
    private final long spawnOrdinal;
    private Point lastPosition;
    private AiTacticsPolicy.State state = AiTacticsPolicy.State.DORMANT;
    private long stateSinceNanos;
    private long lastSeenAtNanos;
    private long nextPathAttemptAtNanos;
    private long nextPatrolAtNanos;
    private long patrolSequence;
    private int patrolWaypointIndex;
    private @Nullable UUID targetId;
    private @Nullable Point lastKnown;

    private AgentRuntime(UUID entityId, Location home, long now, CraftovAiZone zone,
        long spawnOrdinal) {
      this.entityId = entityId;
      this.home = Point.of(home);
      this.spawnOrdinal = spawnOrdinal;
      lastPosition = this.home;
      stateSinceNanos = now;
      lastSeenAtNanos = now;
      nextPathAttemptAtNanos = now;
      nextPatrolAtNanos = now;
      patrolWaypointIndex = AiPatrolPolicy.initialCursor(zone, spawnOrdinal);
    }

    UUID entityId() { return entityId; }
  }

  private record Point(double x, double y, double z) {

    private static Point of(Location location) {
      return new Point(location.getX(), location.getY(), location.getZ());
    }

    private Location location(World world) {
      return new Location(world, x, y, z);
    }

    private double distanceSquared(Location location) {
      double dx = x - location.getX();
      double dy = y - location.getY();
      double dz = z - location.getZ();
      return dx * dx + dy * dy + dz * dz;
    }
  }

  private static final class PathBudget {
    private int remaining;

    private PathBudget(int remaining) {
      this.remaining = remaining;
    }

    private boolean acquire() {
      if (remaining <= 0) return false;
      remaining--;
      return true;
    }
  }

  private enum MoveAttempt {
    STARTED,
    FAILED,
    UNLOADED,
    DEFERRED,
    BUDGET_EXHAUSTED
  }

  private static final class ZoneRuntime {
    private final CraftovAiZone definition;
    private final int targetPopulation;
    private final boolean eventOnly;
    private final Map<UUID, AgentRuntime> agents;
    private long nextSequence;
    private long nextAgentOrdinal;
    private @Nullable ScheduledTask refillTask;
    private boolean refillQueued;

    private ZoneRuntime(CraftovAiZone definition, int targetPopulation, boolean eventOnly,
        Map<UUID, AgentRuntime> agents) {
      this.definition = definition;
      this.targetPopulation = targetPopulation;
      this.eventOnly = eventOnly;
      this.agents = agents;
    }

    CraftovAiZone definition() { return definition; }
    int targetPopulation() { return targetPopulation; }
    boolean eventOnly() { return eventOnly; }
    Map<UUID, AgentRuntime> agents() { return agents; }
  }
}
