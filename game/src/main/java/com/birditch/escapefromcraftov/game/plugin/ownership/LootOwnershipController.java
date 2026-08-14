package com.birditch.escapefromcraftov.game.plugin.ownership;

import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.time.TimeSource;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Authoritative loot-priority runtime for player death crates and physical AI drops.
 *
 * <p>Claims contain UUIDs only and freeze the killer's current team at creation. Physical
 * drops carry their claim in item PDC, so every pickup is rechecked without trusting names or
 * client state.</p>
 */
public final class LootOwnershipController implements Listener, AutoCloseable {

  private static final Duration NOTICE_COOLDOWN = Duration.ofSeconds(1);

  private final CraftovGameService games;
  private final InstanceWorldIndex worlds;
  private final Messages messages;
  private final Logger logger;
  private final TimeSource clock;
  private final long protectionMillis;
  private final Predicate<UUID> manualCarry;
  private final NamespacedKey gameKey;
  private final NamespacedKey priorityKey;
  private final NamespacedKey unlockKey;
  private final Map<DeathKey, LootClaim> deathClaims = new HashMap<>();
  private final Map<UUID, LootClaim> physicalClaims = new HashMap<>();
  private final Map<NoticeKey, Long> nextNotices = new HashMap<>();

  public LootOwnershipController(Plugin plugin, CraftovGameService games,
      InstanceWorldIndex worlds, Messages messages, TimeSource clock, Duration protection) {
    this(plugin, games, worlds, messages, clock, protection, _ -> false);
  }

  public LootOwnershipController(Plugin plugin, CraftovGameService games,
      InstanceWorldIndex worlds, Messages messages, TimeSource clock, Duration protection,
      Predicate<UUID> manualCarry) {
    Plugin checkedPlugin = Preconditions.checkNotNull(plugin, "plugin");
    this.games = Preconditions.checkNotNull(games, "games");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
    this.messages = Preconditions.checkNotNull(messages, "messages");
    logger = checkedPlugin.getLogger();
    this.clock = Preconditions.checkNotNull(clock, "clock");
    Preconditions.checkNotNull(protection, "protection");
    Preconditions.checkArgument(!protection.isNegative() && !protection.isZero(),
        "protection must be positive");
    this.manualCarry = Preconditions.checkNotNull(manualCarry, "manualCarry");
    protectionMillis = protection.toMillis();
    gameKey = new NamespacedKey(checkedPlugin, "loot_owner_game");
    priorityKey = new NamespacedKey(checkedPlugin, "loot_owner_priority");
    unlockKey = new NamespacedKey(checkedPlugin, "loot_owner_unlock");
  }

  /** Records the claim resolved by GameCombatListener's validated final attribution. */
  public LootClaim recordPlayerDeath(CraftovGame game, UUID victimId,
      @Nullable UUID attributedKiller) {
    Preconditions.checkNotNull(game, "game");
    Preconditions.checkNotNull(victimId, "victimId");
    LootClaim claim = LootClaimPolicy.forPlayerDeath(game, victimId, attributedKiller,
        clock.currentMillis(), protectionMillis);
    deathClaims.put(new DeathKey(game.id(), victimId), claim);
    return claim;
  }

  /** Returns a recorded death claim, or safely-public loot if no combat evidence exists. */
  public LootClaim playerDeathClaim(CraftovGameId gameId, UUID victimId) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(victimId, "victimId");
    return deathClaims.getOrDefault(new DeathKey(gameId, victimId), publicClaim(gameId));
  }

  /** Freezes the killer's team for an AI reward. Invalid/non-member killers yield public loot. */
  public LootClaim claimForKiller(CraftovGame game, UUID killerId) {
    Preconditions.checkNotNull(game, "game");
    Preconditions.checkNotNull(killerId, "killerId");
    return LootClaimPolicy.forKiller(game, killerId, clock.currentMillis(), protectionMillis);
  }

  public LootAccess access(CraftovGame game, UUID playerId, LootClaim claim) {
    Preconditions.checkNotNull(game, "game");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(claim, "claim");
    return LootClaimPolicy.access(game, playerId, claim, clock.currentMillis());
  }

  /**
   * Rechecks ownership after the HIGHEST guard and prepares a claim-free clone only. The live
   * entity, its PDC and the in-memory claim remain untouched until {@link #commitManualPickup}.
   */
  public Optional<ManualPickupPermit> prepareManualPickup(Item entity, Player player) {
    Preconditions.checkNotNull(entity, "entity");
    Preconditions.checkNotNull(player, "player");
    ItemStack original = entity.getItemStack().clone();
    LootClaim claim = physicalClaims.get(entity.getUniqueId());
    if (claim == null) claim = claimOf(original).orElse(null);
    CraftovGame game = worlds.gameByWorld(player.getWorld().getName())
        .flatMap(games::find).orElse(null);
    LootAccess access = claim == null
        ? new LootAccess(LootAccess.Kind.PUBLIC, 0)
        : game == null
            ? new LootAccess(LootAccess.Kind.NOT_ACCESSIBLE, 0)
            : access(game, player.getUniqueId(), claim);
    if (!ManualLootPickupPolicy.mayTake(false, true, access)) return Optional.empty();
    ItemStack admitted = original.clone();
    clearProtection(admitted);
    return Optional.of(new ManualPickupPermit(entity.getUniqueId(), claim, access,
        original, admitted));
  }

  /**
   * Main-thread no-fail source commit. Every comparison happens before claim/PDC/entity mutation.
   */
  public boolean commitManualPickup(Item entity, Player player, ManualPickupPermit permit) {
    Preconditions.checkNotNull(entity, "entity");
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(permit, "permit");
    if (!entity.getUniqueId().equals(permit.entityId()) || !entity.isValid()) return false;
    ItemStack current = entity.getItemStack();
    if (current.getAmount() != permit.original().getAmount()
        || !current.isSimilar(permit.original())) return false;
    LootClaim currentClaim = physicalClaims.get(entity.getUniqueId());
    if (currentClaim == null) currentClaim = claimOf(current.clone()).orElse(null);
    if (!java.util.Objects.equals(currentClaim, permit.claim())) return false;

    releasePhysical(entity.getUniqueId(), current);
    entity.setItemStack(current);
    entity.remove();
    if (permit.access().kind() == LootAccess.Kind.PRIORITY && permit.claim() != null) {
      // Source removal is irreversible. Presentation must never make the surrounding target
      // transaction believe this commit failed and roll its newly placed anchor back out.
      try {
        notifyPriority(player, permit.claim().gameId(), permit.access());
      } catch (RuntimeException presentationFailure) {
        try {
          logger.log(Level.WARNING, "战利品拾取已提交，但优先权提示发送失败", presentationFailure);
        } catch (RuntimeException ignored) {
          // A broken logger is presentation too; commitManualPickup must remain true after remove.
        }
      }
    }
    return true;
  }

  /** Adds or replaces the claim on a physical drop. Public claims clear stale protection. */
  public void protectDrop(ItemStack stack, LootClaim claim) {
    Preconditions.checkNotNull(stack, "stack");
    Preconditions.checkNotNull(claim, "claim");
    if (!claim.protectedAt(clock.currentMillis())) {
      clearProtection(stack);
      return;
    }
    stack.editMeta(meta -> {
      PersistentDataContainer data = meta.getPersistentDataContainer();
      data.set(gameKey, PersistentDataType.STRING, claim.gameId().value());
      data.set(priorityKey, PersistentDataType.STRING, claim.priorityPlayers().stream()
          .map(UUID::toString).sorted().collect(Collectors.joining(",")));
      data.set(unlockKey, PersistentDataType.LONG, claim.unlockAtMillis());
    });
  }

  /** Removes a physical-drop claim when a death crate supersedes it. */
  public void clearProtection(ItemStack stack) {
    Preconditions.checkNotNull(stack, "stack");
    stack.editMeta(meta -> {
      PersistentDataContainer data = meta.getPersistentDataContainer();
      data.remove(gameKey);
      data.remove(priorityKey);
      data.remove(unlockKey);
    });
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPickupGuard(EntityPickupItemEvent event) {
    ItemStack stack = event.getItem().getItemStack();
    LootClaim claim = physicalClaims.get(event.getItem().getUniqueId());
    if (claim == null) claim = claimOf(stack).orElse(null);
    if (claim == null) return;
    if (!(event.getEntity() instanceof Player player)) {
      if (claim.protectedAt(clock.currentMillis())) {
        event.setCancelled(true);
      } else {
        releasePhysical(event.getItem().getUniqueId(), stack);
        event.getItem().setItemStack(stack);
      }
      return;
    }
    CraftovGame game = worlds.gameByWorld(player.getWorld().getName())
        .flatMap(games::find).orElse(null);
    LootAccess access = game == null
        ? new LootAccess(LootAccess.Kind.NOT_ACCESSIBLE, 0)
        : access(game, player.getUniqueId(), claim);
    if (!access.allowed()) {
      event.setCancelled(true);
      notifyDenied(player, claim.gameId(), access);
      return;
    }
    // One ground entity has one claim. A partial vanilla pickup cannot strip
    // tags from only the transferred amount, so protected/tagged loot moves as
    // a whole stack or not at all.
    if (event.getRemaining() > 0) {
      event.setCancelled(true);
      notifyWholeStack(player, claim.gameId());
    }
  }

  /** Commits release only after every HIGHEST pickup blocker has had a chance to cancel. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPickupCommitted(EntityPickupItemEvent event) {
    if (!(event.getEntity() instanceof Player player)) return;
    // Active carry performs the source release in its later MONITOR transaction. Releasing here
    // would strip ownership before that target transaction has committed.
    if (manualCarry.test(player.getUniqueId())) return;
    ItemStack stack = event.getItem().getItemStack();
    LootClaim claim = physicalClaims.get(event.getItem().getUniqueId());
    if (claim == null) claim = claimOf(stack).orElse(null);
    if (claim == null) return;
    CraftovGame game = worlds.gameByWorld(player.getWorld().getName())
        .flatMap(games::find).orElse(null);
    LootAccess access = game == null
        ? new LootAccess(LootAccess.Kind.NOT_ACCESSIBLE, 0)
        : access(game, player.getUniqueId(), claim);
    if (!LootPickupReleasePolicy.shouldRelease(
        event.isCancelled(), event.getRemaining(), access)) return;
    releasePhysical(event.getItem().getUniqueId(), stack);
    event.getItem().setItemStack(stack);
    if (access.kind() == LootAccess.Kind.PRIORITY) notifyPriority(player, claim.gameId(), access);
  }

  /** Hoppers cannot bypass a live priority window; expired loot becomes ordinary public loot. */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryPickup(InventoryPickupItemEvent event) {
    ItemStack stack = event.getItem().getItemStack();
    LootClaim claim = physicalClaims.get(event.getItem().getUniqueId());
    if (claim == null) claim = claimOf(stack).orElse(null);
    if (claim == null) return;
    if (claim.protectedAt(clock.currentMillis())) {
      event.setCancelled(true);
      return;
    }
    releasePhysical(event.getItem().getUniqueId(), stack);
    event.getItem().setItemStack(stack);
  }

  /** Registers protection on the actual spawned Item entity, not only its death-event stack. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onItemSpawn(ItemSpawnEvent event) {
    ItemStack stack = event.getEntity().getItemStack();
    LootClaim claim = claimOf(stack).orElse(null);
    if (claim == null) return;
    CraftovGameId worldGame = worlds.gameByWorld(event.getLocation().getWorld().getName())
        .orElse(null);
    if (!claim.gameId().equals(worldGame)) {
      clearProtection(stack);
      event.getEntity().setItemStack(stack);
      return;
    }
    physicalClaims.put(event.getEntity().getUniqueId(), claim);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onEntityRemoved(EntityRemoveEvent event) {
    physicalClaims.remove(event.getEntity().getUniqueId());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    nextNotices.keySet().removeIf(key -> key.playerId().equals(playerId));
  }

  /** Clears records and strips live Item tags for one finished room. */
  public void clearGame(CraftovGameId gameId) {
    Preconditions.checkNotNull(gameId, "gameId");
    deathClaims.keySet().removeIf(key -> key.gameId().equals(gameId));
    // Strip tags from the actual Item entities before the instance world is
    // unloaded, so no transient claim can survive an unusual delayed cleanup.
    worlds.worldOf(gameId).map(Bukkit::getWorld).ifPresent(world -> {
      if (world == null) return;
      for (Item item : world.getEntitiesByClass(Item.class)) {
        ItemStack stack = item.getItemStack();
        LootClaim claim = claimOf(stack).orElse(null);
        if (claim == null || !claim.gameId().equals(gameId)) continue;
        clearProtection(stack);
        item.setItemStack(stack);
      }
    });
    physicalClaims.entrySet().removeIf(entry -> entry.getValue().gameId().equals(gameId));
    nextNotices.keySet().removeIf(key -> key.gameId().equals(gameId));
  }

  @Override
  public void close() {
    deathClaims.clear();
    physicalClaims.clear();
    nextNotices.clear();
  }

  private LootClaim publicClaim(CraftovGameId gameId) {
    return LootClaimPolicy.publicClaim(gameId, clock.currentMillis());
  }

  private void releasePhysical(UUID entityId, ItemStack stack) {
    physicalClaims.remove(entityId);
    // The claim has served its purpose. Never let transient ownership PDC enter
    // inventories, death crates, extraction payloads or durable storage.
    clearProtection(stack);
  }

  private Optional<LootClaim> claimOf(ItemStack stack) {
    if (!stack.hasItemMeta()) return Optional.empty();
    PersistentDataContainer data = stack.getItemMeta().getPersistentDataContainer();
    String rawGame = data.get(gameKey, PersistentDataType.STRING);
    String rawPriority = data.get(priorityKey, PersistentDataType.STRING);
    Long unlockAt = data.get(unlockKey, PersistentDataType.LONG);
    if (rawGame == null && rawPriority == null && unlockAt == null) return Optional.empty();
    if (rawGame == null || rawPriority == null || unlockAt == null) {
      clearProtection(stack);
      return Optional.empty();
    }
    try {
      Set<UUID> priority = rawPriority.isBlank() ? Set.of() : java.util.Arrays.stream(
          rawPriority.split(",")).map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
      return Optional.of(new LootClaim(CraftovGameId.of(rawGame), priority, unlockAt));
    } catch (RuntimeException malformed) {
      clearProtection(stack);
      return Optional.empty();
    }
  }

  private void notifyDenied(Player player, CraftovGameId gameId, LootAccess access) {
    NoticeKey key = new NoticeKey(gameId, player.getUniqueId());
    if (!readyToNotify(key)) return;
    if (access.kind() == LootAccess.Kind.PROTECTED) {
      player.sendActionBar(messages.raw("loot-ownership.protected",
          Placeholder.unparsed("seconds", Integer.toString(access.secondsRemaining()))));
    } else {
      player.sendActionBar(messages.raw("loot-ownership.not-accessible"));
    }
  }

  private void notifyPriority(Player player, CraftovGameId gameId, LootAccess access) {
    NoticeKey key = new NoticeKey(gameId, player.getUniqueId());
    if (!readyToNotify(key)) return;
    player.sendActionBar(messages.raw("loot-ownership.priority",
        Placeholder.unparsed("seconds", Integer.toString(access.secondsRemaining()))));
  }

  private void notifyWholeStack(Player player, CraftovGameId gameId) {
    NoticeKey key = new NoticeKey(gameId, player.getUniqueId());
    if (readyToNotify(key)) player.sendActionBar(messages.raw("loot-ownership.whole-stack"));
  }

  private boolean readyToNotify(NoticeKey key) {
    long now = clock.currentMillis();
    long next = nextNotices.getOrDefault(key, 0L);
    if (now < next) return false;
    nextNotices.put(key, now + NOTICE_COOLDOWN.toMillis());
    return true;
  }

  private record DeathKey(CraftovGameId gameId, UUID victimId) {}

  private record NoticeKey(CraftovGameId gameId, UUID playerId) {}

  public record ManualPickupPermit(
      UUID entityId,
      @Nullable LootClaim claim,
      LootAccess access,
      ItemStack original,
      ItemStack admitted) {

    public ManualPickupPermit {
      Preconditions.checkNotNull(entityId, "entityId");
      Preconditions.checkNotNull(access, "access");
      original = Preconditions.checkNotNull(original, "original").clone();
      admitted = Preconditions.checkNotNull(admitted, "admitted").clone();
    }

    @Override
    public ItemStack original() {
      return original.clone();
    }

    @Override
    public ItemStack admitted() {
      return admitted.clone();
    }
  }
}
