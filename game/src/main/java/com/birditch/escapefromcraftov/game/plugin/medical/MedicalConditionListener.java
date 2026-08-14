package com.birditch.escapefromcraftov.game.plugin.medical;

import com.birditch.escapefromcraftov.api.combat.CombatService;
import com.birditch.escapefromcraftov.api.combat.PlayerCombatState;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Paper adapter for raid-only bleeding, fractures, pain and interruptible
 * treatment. It observes damage after room/friendly-fire checks, so cancelled
 * hits can never create an injury or cancel medicine use.
 */
public final class MedicalConditionListener implements Listener, AutoCloseable {

  private static final Duration TICK = Duration.ofMillis(100);
  private static final long BLEED_INTERVAL_NANOS = Duration.ofSeconds(4).toNanos();
  private static final long HUD_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();
  private static final double BLEED_DAMAGE = 1;
  private static final int PAIN_EFFECT_TICKS = 40;
  private static final int PAIN_SLOWNESS_AMPLIFIER = 0;
  private static final int PAIN_NAUSEA_AMPLIFIER = 0;
  private static final double MOVE_INTERRUPT_DISTANCE_SQUARED = 0.20 * 0.20;

  private final CraftovGameService games;
  private final CombatService combat;
  private final InstanceWorldIndex worlds;
  private final CraftovItemStacks itemStacks;
  private final Map<UUID, InjurySession> injuries = new HashMap<>();
  private final Map<UUID, TreatmentSession> treatments = new HashMap<>();
  private final ScheduledTask ticker;

  public MedicalConditionListener(CraftovGameService games, CombatService combat,
      InstanceWorldIndex worlds, CraftovItemStacks itemStacks, CraftovScheduler scheduler) {
    this.games = Preconditions.checkNotNull(games, "games");
    this.combat = Preconditions.checkNotNull(combat, "combat");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    Preconditions.checkNotNull(scheduler, "scheduler");
    ticker = scheduler.runTimer(Duration.ZERO, TICK, this::tick);
  }

  @Override
  public void close() {
    ticker.cancel();
    for (UUID playerId : List.copyOf(treatments.keySet())) cancelTreatment(playerId, false);
    for (UUID playerId : List.copyOf(injuries.keySet())) clear(playerId);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDamage(EntityDamageEvent event) {
    if (!(event.getEntity() instanceof Player player) || event.getFinalDamage() <= 0
        || event.getCause() == EntityDamageEvent.DamageCause.CUSTOM) return;
    CraftovGame game = activeGame(player).orElse(null);
    if (game == null || combat.stateOf(player.getUniqueId()) != PlayerCombatState.ALIVE) return;

    cancelTreatment(player.getUniqueId(), true);
    Set<MedicalCondition> additions = MedicalConditionPolicy.conditions(event.getFinalDamage(),
        maximumHealth(player), source(event.getCause()));
    if (additions.isEmpty()) return;
    long now = System.nanoTime();
    InjurySession session = injuries.computeIfAbsent(player.getUniqueId(),
        _ -> new InjurySession(game.id(), now));
    if (!session.gameId().equals(game.id())) {
      clear(player.getUniqueId());
      session = new InjurySession(game.id(), now);
      injuries.put(player.getUniqueId(), session);
    }
    if (session.state().inflict(additions)) {
      player.sendActionBar(status(session.state().snapshot(), "受到创伤"));
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_AIR
        && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
    Player player = event.getPlayer();
    CraftovGame game = activeGame(player).orElse(null);
    if (game == null || combat.stateOf(player.getUniqueId()) != PlayerCombatState.ALIVE) return;
    EquipmentSlot hand = event.getHand();
    if (hand == null) return;
    ItemStack held = hand == EquipmentSlot.HAND
        ? player.getInventory().getItemInMainHand()
        : player.getInventory().getItemInOffHand();
    MedicalTreatment treatment = treatment(held).orElse(null);
    if (treatment == null) return;
    InjurySession injury = injuries.get(player.getUniqueId());
    if (injury == null || injury.state().snapshot().stream()
        .noneMatch(treatment.curedConditions()::contains)) {
      player.sendActionBar(Component.text("当前不需要使用" + treatment.displayName(),
          NamedTextColor.GRAY));
      event.setCancelled(true);
      return;
    }
    event.setCancelled(true);
    beginTreatment(player, game.id(), hand, held, treatment);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onMove(PlayerMoveEvent event) {
    TreatmentSession treatment = treatments.get(event.getPlayer().getUniqueId());
    if (treatment != null && event.getTo() != null
        && !withinTreatmentOrigin(event.getTo(), treatment)) {
      cancelTreatment(event.getPlayer().getUniqueId(), true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onSprint(PlayerToggleSprintEvent event) {
    if (event.isSprinting() && has(event.getPlayer().getUniqueId(), MedicalCondition.FRACTURE)) {
      event.setCancelled(true);
      event.getPlayer().sendActionBar(Component.text("骨折使你无法冲刺", NamedTextColor.RED));
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onJump(PlayerJumpEvent event) {
    if (!has(event.getPlayer().getUniqueId(), MedicalCondition.FRACTURE)) return;
    event.setCancelled(true);
    event.getPlayer().sendActionBar(Component.text("骨折使你无法跳跃", NamedTextColor.RED));
  }

  @EventHandler
  public void onHeldSlot(PlayerItemHeldEvent event) {
    cancelTreatment(event.getPlayer().getUniqueId(), true);
  }

  @EventHandler
  public void onSwapHands(PlayerSwapHandItemsEvent event) {
    cancelTreatment(event.getPlayer().getUniqueId(), true);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onDeath(PlayerDeathEvent event) {
    clear(event.getEntity().getUniqueId());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    clear(event.getPlayer().getUniqueId());
  }

  public Set<MedicalCondition> conditions(UUID playerId) {
    InjurySession session = injuries.get(playerId);
    return session == null ? Set.of() : session.state().snapshot();
  }

  private void beginTreatment(Player player, CraftovGameId gameId, EquipmentSlot hand,
      ItemStack held, MedicalTreatment treatment) {
    UUID playerId = player.getUniqueId();
    cancelTreatment(playerId, false);
    long now = System.nanoTime();
    BossBar bar = BossBar.bossBar(
        Component.text("正在使用 " + treatment.displayName(), NamedTextColor.GREEN),
        0, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
    Location origin = player.getLocation();
    treatments.put(playerId, new TreatmentSession(gameId, hand, held.clone(), treatment,
        origin.getWorld().getName(), origin.getX(), origin.getY(), origin.getZ(),
        now, treatment.useDuration().toNanos(), bar));
    player.showBossBar(bar);
  }

  private void tick() {
    long now = System.nanoTime();
    for (Map.Entry<UUID, InjurySession> entry : List.copyOf(injuries.entrySet())) {
      UUID playerId = entry.getKey();
      InjurySession session = entry.getValue();
      Player player = Bukkit.getPlayer(playerId);
      CraftovGame game = player == null ? null : activeGame(player).orElse(null);
      if (player == null || game == null || !game.id().equals(session.gameId())
          || combat.stateOf(playerId) == PlayerCombatState.DEAD) {
        clear(playerId);
        continue;
      }
      if (combat.stateOf(playerId) == PlayerCombatState.DOWNED) {
        cancelTreatment(playerId, false);
        removePainEffects(player);
        continue;
      }
      if (combat.stateOf(playerId) != PlayerCombatState.ALIVE) continue;

      if (session.state().has(MedicalCondition.BLEEDING)
          && now - session.lastBleedNanos() >= BLEED_INTERVAL_NANOS) {
        session.lastBleedNanos(now);
        player.damage(BLEED_DAMAGE);
      }
      if (session.state().has(MedicalCondition.FRACTURE)) player.setSprinting(false);
      if (session.state().has(MedicalCondition.PAIN)) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
            PAIN_EFFECT_TICKS, PAIN_SLOWNESS_AMPLIFIER, false, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA,
            PAIN_EFFECT_TICKS, PAIN_NAUSEA_AMPLIFIER, false, false, true));
      } else {
        removePainEffects(player);
      }
      if (now - session.lastHudNanos() >= HUD_INTERVAL_NANOS) {
        session.lastHudNanos(now);
        player.sendActionBar(status(session.state().snapshot(), null));
      }
    }

    for (Map.Entry<UUID, TreatmentSession> entry : List.copyOf(treatments.entrySet())) {
      tickTreatment(entry.getKey(), entry.getValue(), now);
    }
  }

  private void tickTreatment(UUID playerId, TreatmentSession expected, long now) {
    if (treatments.get(playerId) != expected) return;
    Player player = Bukkit.getPlayer(playerId);
    InjurySession injury = injuries.get(playerId);
    CraftovGame game = player == null ? null : activeGame(player).orElse(null);
    if (player == null || game == null || !game.id().equals(expected.gameId())
        || combat.stateOf(playerId) != PlayerCombatState.ALIVE || injury == null
        || !sameHeldItem(player, expected)
        || !withinTreatmentOrigin(player.getLocation(), expected)) {
      cancelTreatment(playerId, player != null);
      return;
    }
    float progress = MedicalConditionPolicy.progress(expected.startedAtNanos(), now,
        expected.durationNanos());
    expected.bar().progress(progress);
    if (progress < 1) return;

    ItemStack held = held(player, expected.hand());
    held.subtract(1);
    injury.state().treat(expected.treatment());
    double healing = maximumHealth(player) * expected.treatment().healingFraction();
    if (healing > 0) {
      player.setHealth(Math.min(maximumHealth(player), player.getHealth() + healing));
    }
    treatments.remove(playerId);
    player.hideBossBar(expected.bar());
    player.sendActionBar(Component.text(expected.treatment().displayName() + "使用完成",
        NamedTextColor.GREEN));
    if (!injury.state().has(MedicalCondition.PAIN)) removePainEffects(player);
    if (injury.state().isEmpty()) injuries.remove(playerId);
  }

  private void cancelTreatment(UUID playerId, boolean notify) {
    TreatmentSession treatment = treatments.remove(playerId);
    if (treatment == null) return;
    Player player = Bukkit.getPlayer(playerId);
    if (player == null) return;
    player.hideBossBar(treatment.bar());
    if (notify) player.sendActionBar(Component.text("用药已中断", NamedTextColor.RED));
  }

  private void clear(UUID playerId) {
    cancelTreatment(playerId, false);
    injuries.remove(playerId);
    Player player = Bukkit.getPlayer(playerId);
    if (player != null) removePainEffects(player);
  }

  private Optional<CraftovGame> activeGame(Player player) {
    return worlds.gameByWorld(player.getWorld().getName()).flatMap(games::find)
        .filter(game -> game.state() == CraftovGameState.RUNNING)
        .filter(game -> game.players().stream().anyMatch(candidate ->
            candidate.playerId().equals(player.getUniqueId()) && !candidate.extracted()));
  }

  private Optional<MedicalTreatment> treatment(ItemStack stack) {
    return itemStacks.read(stack)
        .flatMap(item -> MedicalTreatment.fromItemId(item.itemId().value()));
  }

  private static boolean sameHeldItem(Player player, TreatmentSession treatment) {
    ItemStack current = held(player, treatment.hand());
    return current.getAmount() > 0 && current.isSimilar(treatment.itemSnapshot());
  }

  private static ItemStack held(Player player, EquipmentSlot hand) {
    return hand == EquipmentSlot.HAND ? player.getInventory().getItemInMainHand()
        : player.getInventory().getItemInOffHand();
  }

  private static boolean withinTreatmentOrigin(Location location, TreatmentSession treatment) {
    if (!location.getWorld().getName().equals(treatment.worldName())) return false;
    double dx = location.getX() - treatment.originX();
    double dy = location.getY() - treatment.originY();
    double dz = location.getZ() - treatment.originZ();
    return dx * dx + dy * dy + dz * dz <= MOVE_INTERRUPT_DISTANCE_SQUARED;
  }

  private static boolean has(UUID playerId, MedicalCondition condition,
      Map<UUID, InjurySession> injuries) {
    InjurySession state = injuries.get(playerId);
    return state != null && state.state().has(condition);
  }

  private boolean has(UUID playerId, MedicalCondition condition) {
    return has(playerId, condition, injuries);
  }

  private static Component status(Set<MedicalCondition> conditions, String prefix) {
    List<MedicalCondition> ordered = new ArrayList<>(conditions);
    ordered.sort(Comparator.comparingInt(Enum::ordinal));
    String names = String.join(" · ", ordered.stream()
        .map(MedicalCondition::displayName).toList());
    String text = prefix == null ? "伤势: " + names : prefix + " | " + names;
    return Component.text(text, NamedTextColor.RED);
  }

  private static void removePainEffects(Player player) {
    PotionEffect slowness = player.getPotionEffect(PotionEffectType.SLOWNESS);
    if (slowness != null && slowness.getAmplifier() == PAIN_SLOWNESS_AMPLIFIER
        && slowness.getDuration() <= PAIN_EFFECT_TICKS) {
      player.removePotionEffect(PotionEffectType.SLOWNESS);
    }
    PotionEffect nausea = player.getPotionEffect(PotionEffectType.NAUSEA);
    if (nausea != null && nausea.getAmplifier() == PAIN_NAUSEA_AMPLIFIER
        && nausea.getDuration() <= PAIN_EFFECT_TICKS) {
      player.removePotionEffect(PotionEffectType.NAUSEA);
    }
  }

  private static double maximumHealth(Player player) {
    AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
    return attribute == null ? 20 : Math.max(1, attribute.getValue());
  }

  private static MedicalConditionPolicy.TraumaSource source(
      EntityDamageEvent.DamageCause cause) {
    return switch (cause) {
      case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE ->
          MedicalConditionPolicy.TraumaSource.MELEE_OR_PROJECTILE;
      case FALL, FLY_INTO_WALL -> MedicalConditionPolicy.TraumaSource.FALL;
      case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> MedicalConditionPolicy.TraumaSource.EXPLOSION;
      case FIRE, FIRE_TICK, HOT_FLOOR, LAVA -> MedicalConditionPolicy.TraumaSource.FIRE;
      default -> MedicalConditionPolicy.TraumaSource.ENVIRONMENT;
    };
  }

  private static final class InjurySession {
    private final CraftovGameId gameId;
    private final MedicalConditionState state = new MedicalConditionState();
    private long lastBleedNanos;
    private long lastHudNanos;

    private InjurySession(CraftovGameId gameId, long nowNanos) {
      this.gameId = gameId;
      lastBleedNanos = nowNanos;
      lastHudNanos = nowNanos;
    }

    private CraftovGameId gameId() { return gameId; }
    private MedicalConditionState state() { return state; }
    private long lastBleedNanos() { return lastBleedNanos; }
    private void lastBleedNanos(long value) { lastBleedNanos = value; }
    private long lastHudNanos() { return lastHudNanos; }
    private void lastHudNanos(long value) { lastHudNanos = value; }
  }

  private record TreatmentSession(CraftovGameId gameId, EquipmentSlot hand,
                                  ItemStack itemSnapshot, MedicalTreatment treatment,
                                  String worldName, double originX, double originY, double originZ,
                                  long startedAtNanos, long durationNanos, BossBar bar) {}
}
