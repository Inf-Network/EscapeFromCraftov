package com.birditch.escapefromcraftov.game.plugin.ai;

import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovStartedEvent;
import com.birditch.escapefromcraftov.api.game.CraftovAiZone;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.loot.LootRollContext;
import com.birditch.escapefromcraftov.api.loot.LootService;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.RaidActionRuntimePolicy;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import com.birditch.escapefromcraftov.game.plugin.report.RaidResultRuntime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
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
 * the zone that owned it and cleanup never reaches another instance.</p>
 */
public final class CraftovAiRuntime implements Listener, AutoCloseable {

  private static final Duration WORLD_READY_RETRY = Duration.ofSeconds(1);
  private static final Duration FAILED_SPAWN_RETRY = Duration.ofSeconds(5);

  private final CraftovGameService games;
  private final InstanceWorldIndex worlds;
  private final CraftovScheduler scheduler;
  private final LootService loot;
  private final CraftovItemStacks itemStacks;
  private final RaidResultRuntime raidReports;
  private final Logger logger;
  private final NamespacedKey gameKey;
  private final NamespacedKey zoneKey;
  private final NamespacedKey teamKey;
  private final Map<CraftovGameId, GameRuntime> runtimes = new HashMap<>();
  private final Map<CraftovGameId, ScheduledTask> pendingStarts = new HashMap<>();
  private boolean closed;

  public CraftovAiRuntime(Plugin plugin, CraftovGameService games, InstanceWorldIndex worlds,
      CraftovScheduler scheduler, LootService loot, CraftovItemStacks itemStacks,
      RaidResultRuntime raidReports, Logger logger) {
    Preconditions.checkNotNull(plugin, "plugin");
    this.games = Preconditions.checkNotNull(games, "games");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.loot = Preconditions.checkNotNull(loot, "loot");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    this.raidReports = Preconditions.checkNotNull(raidReports, "raidReports");
    this.logger = Preconditions.checkNotNull(logger, "logger");
    gameKey = new NamespacedKey(plugin, "ai_game");
    zoneKey = new NamespacedKey(plugin, "ai_zone");
    teamKey = new NamespacedKey(plugin, "ai_team");
  }

  @Subscribe
  public void onCraftovStarted(CraftovStartedEvent event) {
    activate(event.gameId());
  }

  @Subscribe
  public void onCraftovEnded(CraftovEndedEvent event) {
    stop(event.gameId());
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
    GameRuntime runtime = runtimes.values().stream()
        .filter(candidate -> candidate.gameId().value().equals(rawGame))
        .findFirst().orElse(null);
    if (runtime == null || !runtime.worldName().equals(event.getEntity().getWorld().getName())) {
      return;
    }
    ZoneRuntime zone = runtime.zones().get(rawZone);
    if (zone == null || !zone.entities().remove(event.getEntity().getUniqueId())) return;
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
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
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
    RaidActionProfile actionProfile = game.actionProfile().orElse(null);
    GameRuntime runtime = new GameRuntime(
        gameId, worldName,
        RaidActionRuntimePolicy.aiMultiplier(game.mode(), game.difficulty(), actionProfile),
        game.mode(), game.difficulty(), actionProfile,
        new LinkedHashMap<>());
    runtimes.put(gameId, runtime);
    for (CraftovAiZone definition : game.aiZones()) {
      long seed = seed(gameId, definition.id());
      int target = AiRuntimePolicy.targetPopulation(
          definition.minimum(), definition.maximum(), seed);
      ZoneRuntime zone = new ZoneRuntime(definition, target, new LinkedHashSet<>());
      runtime.zones().put(definition.id(), zone);
      refill(runtime, zone);
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

  private void refill(GameRuntime runtime, ZoneRuntime zone) {
    if (runtimes.get(runtime.gameId()) != runtime || closed) return;
    World world = Bukkit.getWorld(runtime.worldName());
    if (world == null) return;
    int missing = zone.targetPopulation() - zone.entities().size();
    int spawned = 0;
    for (int index = 0; index < missing; index++) {
      Mob mob = spawn(runtime, zone, world);
      if (mob == null) break;
      zone.entities().add(mob.getUniqueId());
      spawned++;
    }
    if (spawned < missing) {
      logger.warning("AI 区域没有足够的安全生成点，将稍后重试: "
          + runtime.gameId().value() + "/" + zone.definition().id() + " ("
          + zone.entities().size() + "/" + zone.targetPopulation() + ")");
      scheduleRefill(runtime, zone, FAILED_SPAWN_RETRY);
    }
  }

  private @Nullable Mob spawn(GameRuntime runtime, ZoneRuntime zone, World world) {
    long sequence = zone.nextSequence++;
    long seed = seed(runtime.gameId(), zone.definition().id()) ^ (sequence * 0x9e3779b97f4a7c15L);
    Location location = CraftovAiSpawnResolver.resolve(world, zone.definition(), seed).orElse(null);
    if (location == null) return null;
    EntityType type = entityType(zone.definition().aiType());
    try {
      Entity spawned = world.spawnEntity(location, type, CreatureSpawnEvent.SpawnReason.CUSTOM);
      if (!(spawned instanceof Mob mob)) {
        spawned.remove();
        return null;
      }
      own(mob, runtime.gameId(), zone.definition());
      configure(mob, zone.definition(), runtime.difficultyMultiplier());
      return mob;
    } catch (RuntimeException exception) {
      logger.log(Level.WARNING, "生成 AI 失败: " + runtime.gameId().value() + "/"
          + zone.definition().id() + "/" + type, exception);
      return null;
    }
  }

  private void own(Mob mob, CraftovGameId gameId, CraftovAiZone zone) {
    PersistentDataContainer data = mob.getPersistentDataContainer();
    data.set(gameKey, PersistentDataType.STRING, gameId.value());
    data.set(zoneKey, PersistentDataType.STRING, zone.id());
    data.set(teamKey, PersistentDataType.STRING, zone.team());
    mob.addScoreboardTag("craftov_ai");
    mob.setPersistent(true);
    mob.setRemoveWhenFarAway(false);
    mob.setCanPickupItems(false);
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

  private void scheduleRefill(GameRuntime runtime, ZoneRuntime zone, Duration delay) {
    if (zone.refillTask != null || zone.targetPopulation() <= zone.entities().size()) return;
    zone.refillTask = scheduler.runLater(delay.isZero() ? Duration.ofMillis(50) : delay, () -> {
      zone.refillTask = null;
      CraftovGame game = games.find(runtime.gameId())
          .filter(candidate -> candidate.state() == CraftovGameState.RUNNING).orElse(null);
      if (game != null && runtimes.get(runtime.gameId()) == runtime) refill(runtime, zone);
    });
  }

  private void stop(CraftovGameId gameId) {
    cancelPendingStart(gameId);
    GameRuntime runtime = runtimes.remove(gameId);
    if (runtime == null) return;
    for (ZoneRuntime zone : runtime.zones().values()) {
      if (zone.refillTask != null) zone.refillTask.cancel();
      for (UUID entityId : zone.entities()) {
        Entity entity = Bukkit.getEntity(entityId);
        if (ownedBy(entity, gameId)) entity.remove();
      }
      zone.entities().clear();
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

  private static final class ZoneRuntime {
    private final CraftovAiZone definition;
    private final int targetPopulation;
    private final Set<UUID> entities;
    private long nextSequence;
    private @Nullable ScheduledTask refillTask;

    private ZoneRuntime(CraftovAiZone definition, int targetPopulation, Set<UUID> entities) {
      this.definition = definition;
      this.targetPopulation = targetPopulation;
      this.entities = entities;
    }

    CraftovAiZone definition() { return definition; }
    int targetPopulation() { return targetPopulation; }
    Set<UUID> entities() { return entities; }
  }
}
