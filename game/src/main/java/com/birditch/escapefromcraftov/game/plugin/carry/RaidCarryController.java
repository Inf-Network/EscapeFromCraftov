package com.birditch.escapefromcraftov.game.plugin.carry;

import com.birditch.escapefromcraftov.api.event.EventBus;
import com.birditch.escapefromcraftov.api.event.EventSubscription;
import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.event.game.PlayerLeftCraftovEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.inventory.GridItemPlacement;
import com.birditch.escapefromcraftov.api.inventory.GridPosition;
import com.birditch.escapefromcraftov.api.inventory.GridRotation;
import com.birditch.escapefromcraftov.api.inventory.ItemGrid;
import com.birditch.escapefromcraftov.api.inventory.ItemGridLayout;
import com.birditch.escapefromcraftov.api.inventory.ItemGridService;
import com.birditch.escapefromcraftov.api.inventory.LoadoutSlot;
import com.birditch.escapefromcraftov.api.inventory.RaidCarryCapacity;
import com.birditch.escapefromcraftov.api.inventory.RaidCarryRules;
import com.birditch.escapefromcraftov.api.inventory.RaidCarryService;
import com.birditch.escapefromcraftov.api.inventory.RaidCarrySnapshot;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.item.ItemCategory;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Authoritative physical projection of the raid's pocket, rig and backpack grids.
 *
 * <p>Only an anchor cell contains the real Craftov stack. Covered cells contain identity-free
 * projection markers, so a multi-cell item can never be settled or duplicated through a ghost.
 * The movement curve and dimensions are server tuning rather than upstream numeric claims.</p>
 */
public final class RaidCarryController implements Listener, AutoCloseable {

  private static final Duration REFRESH_PERIOD = Duration.ofMillis(250);
  private static final int[] EQUIPMENT_STORAGE_SLOTS = {
      RaidCarryLayout.MAIN_HAND_SLOT,
      RaidCarryLayout.QUICK_USE_1_SLOT,
      RaidCarryLayout.QUICK_USE_2_SLOT,
      RaidCarryLayout.CHEST_RIG_SLOT,
      RaidCarryLayout.BACKPACK_SLOT
  };
  private static final int[] TRACKED_EQUIPMENT_SLOTS = {
      RaidCarryLayout.MAIN_HAND_SLOT,
      RaidCarryLayout.QUICK_USE_1_SLOT,
      RaidCarryLayout.QUICK_USE_2_SLOT,
      RaidCarryLayout.CHEST_RIG_SLOT,
      RaidCarryLayout.BACKPACK_SLOT,
      36, 37, 38, 39, 40 // boots, leggings, chest, helmet, off-hand
  };

  private final RaidCarryService carry;
  private final ItemGridService grids;
  private final CraftovItemStacks itemStacks;
  private final ItemRegistry items;
  private final RaidCarryGridPlanner planner;
  private final Logger logger;
  private final NamespacedKey lockKey;
  private final NamespacedKey ghostKey;
  private final NamespacedKey movementKey;
  private final double emergencyMovementMultiplier;
  private final EventSubscription domainSubscription;
  private final ScheduledTask ticker;
  private final Map<UUID, CarryState> states = new HashMap<>();
  private final Map<PlayerDropItemEvent, FrozenDrop> frozenDrops = new WeakHashMap<>();
  private final List<Consumer<Player>> deactivationListeners = new ArrayList<>();
  private long nextEpoch;

  public RaidCarryController(Plugin plugin, EventBus events, CraftovScheduler scheduler,
      RaidCarryService carry, ItemGridService grids, CraftovItemStacks itemStacks,
      ItemRegistry items) {
    Plugin checkedPlugin = Preconditions.checkNotNull(plugin, "plugin");
    this.carry = Preconditions.checkNotNull(carry, "carry");
    this.grids = Preconditions.checkNotNull(grids, "grids");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    this.items = Preconditions.checkNotNull(items, "items");
    this.planner = new RaidCarryGridPlanner(items);
    CraftovScheduler checkedScheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    logger = checkedPlugin.getLogger();
    lockKey = new NamespacedKey(checkedPlugin, "raid_carry_locked_slot");
    ghostKey = new NamespacedKey(checkedPlugin, "raid_carry_ghost_anchor");
    movementKey = new NamespacedKey(checkedPlugin, "raid_carry_weight");
    emergencyMovementMultiplier = RaidCarryRules.defaults().minimumMovementMultiplier();
    domainSubscription = Preconditions.checkNotNull(events, "events").register(this);
    ticker = checkedScheduler.runTimer(Duration.ZERO, REFRESH_PERIOD, this::tick);
  }

  /** Installs and imports pre-positioned quick-use overflow before legacy cargo is admitted. */
  public void install(Player player, CraftovGameId gameId, List<StoredItem> claimedLoadout) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(gameId, "gameId");
    List<StoredItem> claimed = List.copyOf(
        Preconditions.checkNotNull(claimedLoadout, "claimedLoadout"));
    clear(player);
    RaidCarryCapacity capacity = carry.capacityFor(claimed);
    RaidCarryGridPlanner.Plan plan = planner.plan(capacity, claimed);
    Map<Integer, ItemInstance> expected = expectedEquipmentBaselines(claimed);
    if (capacity.legacyCompatibility()) captureLegacyEquipment(player, expected);
    CarryState state = new CarryState(gameId, ++nextEpoch, claimed, capacity, expected,
        plan, plan.emptyLayout());
    validateInitialEquipment(player, state);

    ItemGridLayout imported = state.layout;
    for (int slot : allCargoStorageSlots()) {
      ItemStack stack = player.getInventory().getItem(slot);
      boolean usable = plan.cargoPhysicalSlots().contains(slot);
      if (!usable) {
        if (!empty(stack)) throw new IllegalStateException("出战物资超过可用网格容量: " + slot);
        continue;
      }
      if (empty(stack)) continue;
      ItemInstance physical = scanSingle(stack);
      imported = plan.autoPlace(grids, imported, physical)
          .orElseThrow(() -> new IllegalStateException("出战物资无法放入携行网格: " + slot))
          .layout();
    }
    state.layout = imported;
    state.version++;
    state.snapshot = snapshotForLayout(player, state, imported);
    states.put(player.getUniqueId(), state);
    try {
      render(player, state, imported);
      validateEquipment(player, state);
      enforceEquipmentLocks(player, state);
      applySnapshot(player, state.snapshot);
    } catch (RuntimeException failure) {
      states.remove(player.getUniqueId());
      removePlaceholders(player.getInventory());
      removeMovement(player);
      throw failure;
    }
  }

  /** Rebuilds from physical anchors before returning a current weighted snapshot. */
  public RaidCarrySnapshot refresh(Player player) {
    Preconditions.checkNotNull(player, "player");
    CarryState state = requireState(player);
    synchronizePhysical(player, state);
    validateEquipment(player, state);
    enforceEquipmentLocks(player, state);
    RaidCarrySnapshot snapshot = snapshotForLayout(player, state, state.layout);
    state.snapshot = snapshot;
    state.invalid = false;
    state.invalidLogged = false;
    applySnapshot(player, snapshot);
    return snapshot;
  }

  /** Final settlement authority: physical anchors and logical placements must agree both ways. */
  public RaidCarrySnapshot settlementSnapshot(Player player, CraftovGameId gameId) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(gameId, "gameId");
    CarryState state = states.get(player.getUniqueId());
    if (state == null || !state.gameId.equals(gameId)) {
      throw new IllegalStateException("玩家没有当前行动的携行状态");
    }
    return refresh(player);
  }

  /** Latest validated view only; settlement must call {@link #settlementSnapshot}. */
  public Optional<RaidCarrySnapshot> snapshot(UUID playerId) {
    CarryState state = states.get(Preconditions.checkNotNull(playerId, "playerId"));
    return state == null ? Optional.empty() : Optional.ofNullable(state.snapshot);
  }

  public Optional<RaidCarrySnapshot> snapshot(Player player) {
    Preconditions.checkNotNull(player, "player");
    return snapshot(player.getUniqueId());
  }

  public Optional<RaidCarryCapacity> capacity(UUID playerId) {
    CarryState state = states.get(Preconditions.checkNotNull(playerId, "playerId"));
    return state == null ? Optional.empty() : Optional.of(state.capacity);
  }

  public int cargoCapacity(UUID playerId) {
    return capacity(playerId).map(RaidCarryCapacity::cargoSlots).orElse(0);
  }

  public boolean active(UUID playerId) {
    return states.containsKey(Preconditions.checkNotNull(playerId, "playerId"));
  }

  /**
   * Immutable, non-authoritative rendering view for the TrMenu carry organizer. Mutations still
   * have to pass {@link #mutateFromMenu(Player, MenuMutation)} with this exact binding.
   */
  public Optional<MenuView> menuView(Player player) {
    Preconditions.checkNotNull(player, "player");
    CarryState state = states.get(player.getUniqueId());
    if (state == null) return Optional.empty();
    refresh(player);
    return Optional.of(menuView(state));
  }

  /** Registers a main-thread lifecycle hook used to close projections before carry is cleared. */
  public AutoCloseable onDeactivated(Consumer<Player> listener) {
    Consumer<Player> checked = Preconditions.checkNotNull(listener, "listener");
    deactivationListeners.add(checked);
    return () -> deactivationListeners.remove(checked);
  }

  /**
   * The sole transaction used by the carry organizer. The caller supplies only a previously
   * server-issued binding, selected identity, and a server-mapped destination cell. Physical
   * anchors are reconciled and the version is compared again before the identity is relocated.
   */
  public MenuMutationResult mutateFromMenu(Player player, MenuMutation mutation) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(mutation, "mutation");
    CarryState state = states.get(player.getUniqueId());
    if (state == null || !state.gameId.equals(mutation.gameId())
        || state.epoch != mutation.epoch()) {
      return new MenuMutationResult(MenuMutationStatus.INACTIVE, null);
    }

    try {
      synchronizePhysical(player, state);
      validateEquipment(player, state);
      state.snapshot = snapshotForLayout(player, state, state.layout);
    } catch (RuntimeException invalid) {
      markInvalid(player, state, invalid);
      return new MenuMutationResult(MenuMutationStatus.REJECTED, null);
    }
    if (state.version != mutation.version()) {
      return new MenuMutationResult(MenuMutationStatus.STALE, menuView(state));
    }

    LocatedPlacement source = locate(state, mutation.instanceId()).orElse(null);
    if (source == null) {
      return new MenuMutationResult(MenuMutationStatus.STALE, menuView(state));
    }
    String targetGrid;
    GridPosition targetAnchor;
    GridRotation targetRotation;
    if (mutation.kind() == MenuMutationKind.ROTATE) {
      if (!items.require(source.placement().item().itemId()).footprint().rotatable()) {
        return new MenuMutationResult(MenuMutationStatus.REJECTED, menuView(state));
      }
      targetGrid = source.region().id();
      targetAnchor = source.placement().anchor();
      targetRotation = source.placement().rotation() == GridRotation.DEFAULT
          ? GridRotation.CLOCKWISE_90 : GridRotation.DEFAULT;
    } else {
      RaidCarryGridPlanner.Region region = state.plan.region(mutation.targetGridId())
          .orElse(null);
      if (region == null || region.shape().compartmentAt(mutation.targetAnchor()).isEmpty()) {
        return new MenuMutationResult(MenuMutationStatus.REJECTED, menuView(state));
      }
      targetGrid = region.id();
      targetAnchor = mutation.targetAnchor();
      targetRotation = source.placement().rotation();
    }

    ItemGridLayout beforeLayout = state.layout;
    var changed = grids.transfer(beforeLayout, source.region().id(), targetGrid,
        source.placement().item().instanceId(), targetAnchor, targetRotation);
    if (!changed.accepted()) {
      return new MenuMutationResult(MenuMutationStatus.REJECTED,
          state.snapshot == null ? null : menuView(state));
    }

    RaidCarrySnapshot afterSnapshot;
    try {
      afterSnapshot = snapshotForLayout(player, state, changed.layout());
    } catch (RuntimeException rejected) {
      return new MenuMutationResult(MenuMutationStatus.REJECTED, menuView(state));
    }
    try {
      // Rendering is the only physical mutation. It must finish before the logical CAS commits.
      render(player, state, changed.layout());
    } catch (RuntimeException renderFailure) {
      try {
        render(player, state, beforeLayout);
      } catch (RuntimeException rollbackFailure) {
        renderFailure.addSuppressed(rollbackFailure);
        markInvalid(player, state, renderFailure);
      }
      return new MenuMutationResult(MenuMutationStatus.REJECTED,
          state.snapshot == null ? null : menuView(state));
    }

    state.layout = changed.layout();
    state.snapshot = afterSnapshot;
    state.version++;
    state.invalid = false;
    state.invalidLogged = false;
    try {
      applySnapshot(player, afterSnapshot);
    } catch (RuntimeException movementFailure) {
      // Weight presentation is not allowed to roll back an already committed identity move.
      logBestEffort(Level.WARNING, "携行整理已提交，但负重属性刷新失败", movementFailure);
    }
    return new MenuMutationResult(MenuMutationStatus.COMMITTED, menuView(state));
  }

  /** Non-mutating admission projection after first reconciling normal physical consumption. */
  public boolean canAccept(Player player, ItemStack incoming) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(incoming, "incoming");
    CarryState state = states.get(player.getUniqueId());
    if (state == null) return false;
    try {
      synchronizePhysical(player, state);
      validateEquipment(player, state);
    } catch (RuntimeException invalid) {
      markInvalid(player, state, invalid);
      return false;
    }
    try {
      ItemInstance item = scanSingle(incoming);
      var candidate = state.plan.autoPlace(grids, state.layout, item).orElse(null);
      return candidate != null && validSnapshot(player, state, candidate.layout());
    } catch (RuntimeException rejected) {
      return false;
    }
  }

  /** Bootstrap insertion used after the durable loadout has already been claimed. */
  public boolean admit(Player player, ItemStack incoming) {
    return transferIntoCargo(player, incoming, () -> true);
  }

  public enum CargoRemoval {
    NOT_FOUND,
    REJECTED,
    COMMITTED
  }

  /** True only when the supplied durable identity is the real anchor of a cargo placement. */
  public boolean cargoAnchor(Player player, UUID instanceId) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(instanceId, "instanceId");
    CarryState state = states.get(player.getUniqueId());
    if (state == null) return false;
    try {
      synchronizePhysical(player, state);
      validateEquipment(player, state);
      return locate(state, instanceId).isPresent();
    } catch (RuntimeException invalid) {
      markInvalid(player, state, invalid);
      return false;
    }
  }

  /**
   * Removes one anchor before synchronously committing a virtual destination (for example the
   * secure container). A rejected destination restores the old layout and physical anchor.
   */
  public CargoRemoval transferOutOfCargo(Player player, UUID instanceId,
      BooleanSupplier destinationCommit) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(instanceId, "instanceId");
    Preconditions.checkNotNull(destinationCommit, "destinationCommit");
    CarryState state = states.get(player.getUniqueId());
    if (state == null) return CargoRemoval.NOT_FOUND;
    ItemGridLayout before = state.layout;
    RaidCarrySnapshot beforeSnapshot = state.snapshot;
    boolean projected = false;
    try {
      synchronizePhysical(player, state);
      validateEquipment(player, state);
      LocatedPlacement located = locate(state, instanceId).orElse(null);
      if (located == null) return CargoRemoval.NOT_FOUND;
      before = state.layout;
      beforeSnapshot = snapshotForLayout(player, state, before);
      var removed = grids.remove(before, located.region().id(), instanceId);
      if (!removed.accepted()) return CargoRemoval.REJECTED;
      RaidCarrySnapshot afterSnapshot = snapshotForLayout(player, state, removed.layout());
      projected = true;
      render(player, state, removed.layout());
      state.layout = removed.layout();
      state.snapshot = afterSnapshot;
      state.version++;
      boolean committed;
      try {
        committed = destinationCommit.getAsBoolean();
      } catch (RuntimeException destinationFailure) {
        rollback(player, state, before, beforeSnapshot);
        projected = false;
        logBestEffort(Level.WARNING, "安全目的地拒绝携行移除，已回滚目标网格", destinationFailure);
        return CargoRemoval.REJECTED;
      }
      if (!committed) {
        rollback(player, state, before, beforeSnapshot);
        projected = false;
        return CargoRemoval.REJECTED;
      }
      projected = false;
      try {
        applySnapshot(player, afterSnapshot);
      } catch (RuntimeException movementFailure) {
        logBestEffort(Level.WARNING, "安全箱转移已提交，但负重属性刷新失败", movementFailure);
      }
      return CargoRemoval.COMMITTED;
    } catch (RuntimeException invalid) {
      if (projected) {
        try {
          rollback(player, state, before, beforeSnapshot);
        } catch (RuntimeException rollbackFailure) {
          invalid.addSuppressed(rollbackFailure);
        }
      }
      markInvalid(player, state, invalid);
      return CargoRemoval.REJECTED;
    }
  }

  /** Atomically removes a tracked non-container equipment stack into a virtual destination. */
  public CargoRemoval transferOutOfEquipment(Player player, UUID instanceId,
      BooleanSupplier destinationCommit) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(instanceId, "instanceId");
    Preconditions.checkNotNull(destinationCommit, "destinationCommit");
    CarryState state = states.get(player.getUniqueId());
    if (state == null) return CargoRemoval.NOT_FOUND;
    boolean projected = false;
    int slot = -1;
    ItemInstance expected = null;
    ItemStack physical = null;
    RaidCarrySnapshot beforeSnapshot = state.snapshot;
    try {
      synchronizePhysical(player, state);
      validateEquipment(player, state);
      for (Map.Entry<Integer, ItemInstance> entry : state.expectedEquipment.entrySet()) {
        if (entry.getValue().instanceId().equals(instanceId)) {
          slot = entry.getKey();
          expected = entry.getValue();
          break;
        }
      }
      if (expected == null) return CargoRemoval.NOT_FOUND;
      if (slot == RaidCarryLayout.CHEST_RIG_SLOT || slot == RaidCarryLayout.BACKPACK_SLOT) {
        return CargoRemoval.REJECTED;
      }
      physical = player.getInventory().getItem(slot).clone();
      if (!expectedEquipmentStack(physical, expected)) return CargoRemoval.REJECTED;
      beforeSnapshot = snapshotForLayout(player, state, state.layout);
      // Every failure after the first source mutation must restore the baseline and physical
      // stack. Once the destination commits, source rollback is permanently disabled.
      projected = true;
      state.expectedEquipment.remove(slot);
      state.version++;
      player.getInventory().setItem(slot, null);
      enforceEquipmentLocks(player, state);
      RaidCarrySnapshot afterSnapshot = snapshotForLayout(player, state, state.layout);
      state.snapshot = afterSnapshot;
      boolean committed;
      try {
        committed = destinationCommit.getAsBoolean();
      } catch (RuntimeException destinationFailure) {
        restoreEquipmentTransfer(player, state, slot, expected, physical, beforeSnapshot);
        projected = false;
        logBestEffort(Level.WARNING, "安全目的地拒绝装备移除，已回滚物理装备", destinationFailure);
        return CargoRemoval.REJECTED;
      }
      if (!committed) {
        restoreEquipmentTransfer(player, state, slot, expected, physical, beforeSnapshot);
        projected = false;
        return CargoRemoval.REJECTED;
      }
      projected = false;
      state.invalid = false;
      state.invalidLogged = false;
      try {
        applySnapshot(player, afterSnapshot);
      } catch (RuntimeException movementFailure) {
        logBestEffort(Level.WARNING, "装备已转入安全箱，但负重属性刷新失败", movementFailure);
      }
      return CargoRemoval.COMMITTED;
    } catch (RuntimeException invalid) {
      if (projected && expected != null && physical != null && slot >= 0) {
        try {
          restoreEquipmentTransfer(player, state, slot, expected, physical, beforeSnapshot);
        } catch (RuntimeException rollbackFailure) {
          invalid.addSuppressed(rollbackFailure);
          markInvalid(player, state, invalid);
        }
      } else {
        markInvalid(player, state, invalid);
      }
      return CargoRemoval.REJECTED;
    }
  }

  private void restoreEquipmentTransfer(Player player, CarryState state, int slot,
      ItemInstance expected, ItemStack physical, RaidCarrySnapshot snapshot) {
    state.expectedEquipment.put(slot, expected);
    player.getInventory().setItem(slot, physical);
    state.snapshot = snapshot;
    state.version++;
    applySnapshot(player, snapshot);
  }

  /**
   * Commits a target grid first, then invokes a synchronous all-or-nothing source deletion.
   * Returning {@code false} from the source callback restores the exact old target projection.
   * Callbacks must be main-thread-confined and must not partially mutate before returning false.
   */
  public boolean transferIntoCargo(Player player, ItemStack incoming,
      BooleanSupplier sourceCommit) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(incoming, "incoming");
    Preconditions.checkNotNull(sourceCommit, "sourceCommit");
    CarryState state = states.get(player.getUniqueId());
    if (state == null) return false;
    ItemGridLayout beforeLayout;
    RaidCarrySnapshot beforeSnapshot;
    try {
      synchronizePhysical(player, state);
      validateEquipment(player, state);
      beforeLayout = state.layout;
      beforeSnapshot = snapshotForLayout(player, state, beforeLayout);
    } catch (RuntimeException invalid) {
      markInvalid(player, state, invalid);
      return false;
    }
    boolean projected = false;
    try {
      ItemInstance item = scanSingle(incoming);
      RaidCarryGridPlanner.PlannedPlacement candidate =
          state.plan.autoPlace(grids, beforeLayout, item).orElse(null);
      if (candidate == null) return false;
      RaidCarrySnapshot candidateSnapshot = snapshotForLayout(player, state, candidate.layout());
      projected = true;
      render(player, state, candidate.layout());
      state.layout = candidate.layout();
      state.snapshot = candidateSnapshot;
      state.version++;
      boolean sourceCommitted;
      try {
        sourceCommitted = sourceCommit.getAsBoolean();
      } catch (RuntimeException sourceFailure) {
        rollback(player, state, beforeLayout, beforeSnapshot);
        projected = false;
        logBestEffort(Level.WARNING, "物资来源提交失败，已回滚携行网格", sourceFailure);
        return false;
      }
      if (!sourceCommitted) {
        rollback(player, state, beforeLayout, beforeSnapshot);
        projected = false;
        return false;
      }
      state.invalid = false;
      state.invalidLogged = false;
      projected = false;
      try {
        applySnapshot(player, candidateSnapshot);
      } catch (RuntimeException movementFailure) {
        logBestEffort(Level.WARNING, "物资转移已提交，但负重属性刷新失败", movementFailure);
      }
      return true;
    } catch (RuntimeException rejected) {
      if (projected) rollback(player, state, beforeLayout, beforeSnapshot);
      return false;
    }
  }

  /** Removes only synthetic projections and movement state; real anchor stacks remain. */
  public void clear(Player player) {
    Preconditions.checkNotNull(player, "player");
    if (states.containsKey(player.getUniqueId())) notifyDeactivated(player);
    states.remove(player.getUniqueId());
    removePlaceholders(player.getInventory());
    removeMovement(player);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  @SuppressWarnings("removal")
  public void onInventoryClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)
        || !states.containsKey(player.getUniqueId())) return;
    boolean playerCell = event.getClickedInventory() == player.getInventory();
    if (playerCell && (event.getAction() == InventoryAction.DROP_ALL_SLOT
        || event.getAction() == InventoryAction.DROP_ONE_SLOT)) {
      // All HIGHEST blockers must get an uncancelled view. The MONITOR handler below replaces
      // vanilla deletion only after none of them denied the action.
      return;
    }
    boolean intoPlayer = event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
        && event.getClickedInventory() != null
        && event.getClickedInventory() != player.getInventory();
    boolean hotbarMutation = event.getHotbarButton() >= 0
        || event.getClick() == ClickType.SWAP_OFFHAND
        || event.getAction() == InventoryAction.HOTBAR_SWAP
        || event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD;
    boolean craftovCursor = itemStacks.isCraftovItem(event.getCursor());
    boolean craftovCurrent = itemStacks.isCraftovItem(event.getCurrentItem());
    if (playerCell || intoPlayer || hotbarMutation
        || event.getAction() == InventoryAction.COLLECT_TO_CURSOR
        || craftovCursor || craftovCurrent
        || placeholder(event.getCursor()) || placeholder(event.getCurrentItem())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onInventoryDropCommit(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)
        || !RaidCarryEventPolicy.mayCommit(
            event.isCancelled(), states.containsKey(player.getUniqueId()))) return;
    if (event.getClickedInventory() != player.getInventory()
        || event.getAction() != InventoryAction.DROP_ALL_SLOT
            && event.getAction() != InventoryAction.DROP_ONE_SLOT) return;
    event.setCancelled(true);
    CarryState state = states.get(player.getUniqueId());
    int slot = event.getSlot();
    boolean dropOne = event.getAction() == InventoryAction.DROP_ONE_SLOT;
    boolean dropped = slot == RaidCarryLayout.CHEST_RIG_SLOT
        || slot == RaidCarryLayout.BACKPACK_SLOT
        ? dropContainer(player, state, slot, true)
        : dropCargoAnchor(player, state, slot, true, dropOne);
    if (!dropped) {
      player.sendActionBar(Component.text("该物品不能从当前网格丢弃", NamedTextColor.RED));
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInventoryDrag(InventoryDragEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)
        || !states.containsKey(player.getUniqueId())) return;
    if (itemStacks.isCraftovItem(event.getOldCursor()) || placeholder(event.getOldCursor())) {
      event.setCancelled(true);
      return;
    }
    for (int rawSlot : event.getRawSlots()) {
      if (event.getView().getInventory(rawSlot) == player.getInventory()) {
        event.setCancelled(true);
        return;
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onSwapHands(PlayerSwapHandItemsEvent event) {
    if (states.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onDropGuard(PlayerDropItemEvent event) {
    Player player = event.getPlayer();
    if (!states.containsKey(player.getUniqueId())) return;
    ItemStack dropped = event.getItemDrop().getItemStack();
    if (placeholder(dropped)) {
      event.setCancelled(true);
      return;
    }
    var tagged = itemStacks.read(dropped).orElse(null);
    if (tagged == null || !items.require(tagged.itemId()).droppable()) {
      event.setCancelled(true);
      player.sendActionBar(Component.text("该物品不可丢弃", NamedTextColor.RED));
      return;
    }
    CarryState state = states.get(player.getUniqueId());
    int slot = player.getInventory().getHeldItemSlot();
    ItemStack remaining = player.getInventory().getItem(slot);
    boolean partial = expectedInstance(remaining, tagged.instanceId());
    if (!partial && !empty(remaining)) {
      event.setCancelled(true);
      return;
    }
    int remainingAmount = partial ? remaining.getAmount() : 0;
    int originalAmount;
    try {
      originalAmount = Math.addExact(dropped.getAmount(), remainingAmount);
    } catch (ArithmeticException invalidAmount) {
      event.setCancelled(true);
      return;
    }
    var stamp = new RaidDropCommitPolicy.DropStamp(player.getUniqueId(), slot,
        event.getItemDrop().getUniqueId(), tagged.instanceId(), tagged.itemId(),
        dropped.getAmount(), remainingAmount, originalAmount, state.version);
    frozenDrops.put(event, new FrozenDrop(stamp, dropped));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDropCommit(PlayerDropItemEvent event) {
    Player player = event.getPlayer();
    CarryState state = states.get(player.getUniqueId());
    if (!RaidCarryEventPolicy.mayCommit(event.isCancelled(), state != null)) return;
    FrozenDrop frozen = frozenDrops.remove(event);
    if (frozen == null || !validFrozenDrop(event, player, state, frozen)) {
      if (frozen != null) event.getItemDrop().setItemStack(frozen.dropped());
      event.setCancelled(true);
      return;
    }
    ItemStack dropped = event.getItemDrop().getItemStack();
    var tagged = itemStacks.read(dropped).orElse(null);
    if (tagged == null || !items.require(tagged.itemId()).droppable()) {
      event.setCancelled(true);
      return;
    }
    int slot = frozen.slot();
    ItemStack remaining = player.getInventory().getItem(slot);
    boolean partial = frozen.remainingAmount() > 0;
    if (partial) {
      ItemStack originalDrop = dropped.clone();
      ItemGridLayout oldLayout = state.layout;
      RaidCarrySnapshot oldSnapshot = state.snapshot;
      Map<Integer, ItemInstance> oldEquipment = new LinkedHashMap<>(state.expectedEquipment);
      try {
        itemStacks.assignFreshIdentity(dropped).orElseThrow();
        event.getItemDrop().setItemStack(dropped);
        refresh(player);
      } catch (RuntimeException invalid) {
        state.layout = oldLayout;
        state.snapshot = oldSnapshot;
        state.expectedEquipment.clear();
        state.expectedEquipment.putAll(oldEquipment);
        state.version++;
        event.getItemDrop().setItemStack(originalDrop);
        event.setCancelled(true);
        markInvalid(player, state, invalid);
      }
      return;
    }

    // Bukkit has already moved the full stack into the Item entity. Restore it only for the
    // authority check; a successful logical removal clears the slot again and leaves that one
    // entity as the sole owner of the durable UUID.
    player.getInventory().setItem(slot, dropped.clone());
    boolean accepted = slot == RaidCarryLayout.CHEST_RIG_SLOT
        || slot == RaidCarryLayout.BACKPACK_SLOT
        ? dropContainer(player, state, slot, false)
        : locate(state, tagged.instanceId())
            .filter(found -> found.region().physicalSlot(found.placement().anchor())
                .filter(anchorSlot -> anchorSlot == slot).isPresent())
            .map(_ -> dropCargoAnchor(player, state, slot, false, false))
            .orElseGet(() -> dropEquipped(player, state, slot, tagged.instanceId()));
    if (!accepted) {
      player.getInventory().setItem(slot, null);
      event.setCancelled(true);
      player.sendActionBar(Component.text("剩余物资无法重排，不能丢弃", NamedTextColor.RED));
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInteract(PlayerInteractEvent event) {
    if (states.containsKey(event.getPlayer().getUniqueId())
        && containerOrPlaceholder(event.getItem())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInteractEntity(PlayerInteractEntityEvent event) {
    if (!states.containsKey(event.getPlayer().getUniqueId())) return;
    if (containerOrPlaceholder(event.getPlayer().getInventory().getItem(event.getHand()))) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
    onInteractEntity(event);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onToggleSprint(PlayerToggleSprintEvent event) {
    if (!event.isSprinting()) return;
    CarryState state = states.get(event.getPlayer().getUniqueId());
    if (state != null && (state.invalid
        || state.snapshot != null && overweight(state.snapshot))) {
      event.setCancelled(true);
      event.getPlayer().setSprinting(false);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onDeath(PlayerDeathEvent event) {
    Player player = event.getEntity();
    if (!states.containsKey(player.getUniqueId())) return;
    event.getDrops().removeIf(this::placeholder);
    notifyDeactivated(player);
    states.remove(player.getUniqueId());
    removeMovement(player);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    clear(event.getPlayer());
  }

  @Subscribe
  public void onPlayerLeft(PlayerLeftCraftovEvent event) {
    CarryState state = states.get(event.playerId());
    if (state == null || !state.gameId.equals(event.gameId())) return;
    Player player = Bukkit.getPlayer(event.playerId());
    if (player == null) states.remove(event.playerId());
    else clear(player);
  }

  @Subscribe
  public void onCraftovEnded(CraftovEndedEvent event) {
    List<UUID> affected = states.entrySet().stream()
        .filter(entry -> entry.getValue().gameId.equals(event.gameId()))
        .map(Map.Entry::getKey).toList();
    for (UUID playerId : affected) {
      Player player = Bukkit.getPlayer(playerId);
      if (player == null) states.remove(playerId);
      else clear(player);
    }
  }

  private void tick() {
    for (Map.Entry<UUID, CarryState> entry : List.copyOf(states.entrySet())) {
      Player player = Bukkit.getPlayer(entry.getKey());
      if (player == null || !player.isOnline()) continue;
      try {
        refresh(player);
      } catch (RuntimeException invalid) {
        markInvalid(player, entry.getValue(), invalid);
      }
    }
  }

  private boolean dropCargoAnchor(Player player, CarryState state, int physicalSlot,
      boolean spawnDrop, boolean dropOne) {
    ItemGridLayout oldLayout = state.layout;
    RaidCarrySnapshot oldSnapshot = state.snapshot;
    boolean projected = false;
    try {
      synchronizePhysical(player, state);
      validateEquipment(player, state);
      oldLayout = state.layout;
      oldSnapshot = snapshotForLayout(player, state, oldLayout);
      RaidCarryGridPlanner.Region region = state.plan.regionForPhysicalSlot(physicalSlot)
          .orElse(null);
      if (region == null) return false;
      GridPosition position = region.positionForPhysicalSlot(physicalSlot).orElseThrow();
      ItemGrid grid = state.layout.find(region.id()).orElseThrow();
      GridItemPlacement placement = grids.placementAt(grid, position).orElse(null);
      if (placement == null || !placement.anchor().equals(position)
          || !items.require(placement.item().itemId()).droppable()) return false;
      int dropQuantity = RaidCarryEventPolicy.clickedDropQuantity(
          dropOne, placement.item().quantity());
      if (dropQuantity < placement.item().quantity()) {
        if (!spawnDrop) return false;
        ItemStack original = player.getInventory().getItem(physicalSlot);
        if (empty(original) || original.getAmount() != placement.item().quantity()) return false;
        ItemStack dropped = original.clone();
        dropped.setAmount(dropQuantity);
        itemStacks.assignFreshIdentity(dropped).orElseThrow();
        ItemStack remaining = original.clone();
        remaining.setAmount(original.getAmount() - dropQuantity);
        projected = true;
        player.getInventory().setItem(physicalSlot, remaining);
        synchronizePhysical(player, state);
        RaidCarrySnapshot snapshot = snapshotForLayout(player, state, state.layout);
        state.snapshot = snapshot;
        state.invalid = false;
        state.invalidLogged = false;
        applySnapshot(player, snapshot);
        // The separated ground stack owns a fresh UUID. Spawn is the terminal external action.
        if (spawnDrop) player.getWorld().dropItemNaturally(player.getLocation(), dropped);
        return true;
      }
      var removed = grids.remove(state.layout, region.id(), placement.item().instanceId());
      if (!removed.accepted()) return false;
      RaidCarrySnapshot snapshot = snapshotForLayout(player, state, removed.layout());
      ItemStack physical = physicalStack(placement.item());
      projected = true;
      render(player, state, removed.layout());
      state.layout = removed.layout();
      state.snapshot = snapshot;
      state.version++;
      state.invalid = false;
      state.invalidLogged = false;
      applySnapshot(player, snapshot);
      // Entity creation is the final potentially external action. Nothing after a successful
      // spawn may enter the rollback branch and recreate this durable UUID in the grid.
      if (spawnDrop) player.getWorld().dropItemNaturally(player.getLocation(), physical);
      return true;
    } catch (RuntimeException failure) {
      if (!projected) {
        markInvalid(player, state, failure);
        return false;
      }
      try {
        rollback(player, state, oldLayout, oldSnapshot);
      } catch (RuntimeException rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
        markInvalid(player, state, failure);
      }
      return false;
    }
  }

  private boolean dropEquipped(Player player, CarryState state, int slot, UUID instanceId) {
    ItemStack stack = player.getInventory().getItem(slot);
    ItemInstance expected = state.expectedEquipment.get(slot);
    if (expected == null || !expected.instanceId().equals(instanceId)
        || !expectedEquipmentStack(stack, expected)) return false;
    // The source entity already owns this complete stack. Clear the temporary validation copy,
    // then immediately rebuild weight/equipment state and place a lock in its old reserved cell.
    state.expectedEquipment.remove(slot);
    state.version++;
    player.getInventory().setItem(slot, null);
    try {
      refresh(player);
      return true;
    } catch (RuntimeException invalid) {
      state.expectedEquipment.put(slot, expected);
      state.version++;
      markInvalid(player, state, invalid);
      return false;
    }
  }

  private boolean dropContainer(Player player, CarryState state, int slot, boolean spawnDrop) {
    LoadoutSlot loadoutSlot = switch (slot) {
      case RaidCarryLayout.CHEST_RIG_SLOT -> LoadoutSlot.CHEST_RIG;
      case RaidCarryLayout.BACKPACK_SLOT -> LoadoutSlot.BACKPACK;
      default -> null;
    };
    if (loadoutSlot == null) return false;
    ItemStack equipped = player.getInventory().getItem(slot);
    ItemInstance expected = state.expectedEquipment.get(slot);
    if (expected == null || !expectedEquipmentStack(equipped, expected)) return false;
    var tagged = itemStacks.read(equipped).orElseThrow();
    if (!items.require(tagged.itemId()).droppable()) return false;

    try {
      synchronizePhysical(player, state);
      validateEquipment(player, state);
    } catch (RuntimeException invalid) {
      markInvalid(player, state, invalid);
      return false;
    }
    List<StoredItem> remaining;
    RaidCarryCapacity capacity;
    RaidCarryGridPlanner.Plan plan;
    ItemGridLayout layout;
    RaidCarrySnapshot snapshot;
    try {
      remaining = state.claimedLoadout.stream()
          .filter(item -> item.loadoutSlot() != loadoutSlot).toList();
      capacity = carry.capacityFor(remaining);
      plan = planner.plan(capacity, remaining);
      layout = plan.repack(grids, state.layout).orElse(null);
      if (layout == null) return false;
      snapshot = snapshotForLayout(player, capacity, layout, slot);
    } catch (RuntimeException rejected) {
      return false;
    }

    List<StoredItem> oldClaimed = state.claimedLoadout;
    RaidCarryCapacity oldCapacity = state.capacity;
    RaidCarryGridPlanner.Plan oldPlan = state.plan;
    ItemGridLayout oldLayout = state.layout;
    RaidCarrySnapshot oldSnapshot = state.snapshot;
    ItemInstance oldExpected = state.expectedEquipment.get(slot);
    ItemStack physical = equipped.clone();
    try {
      player.getInventory().setItem(slot, null);
      render(player, plan, layout);
      state.claimedLoadout = remaining;
      state.capacity = capacity;
      state.plan = plan;
      state.layout = layout;
      state.expectedEquipment.remove(slot);
      state.snapshot = snapshot;
      state.version++;
      enforceEquipmentLocks(player, state);
      applySnapshot(player, snapshot);
      // As above, a successful source spawn is terminal for this transaction.
      if (spawnDrop) player.getWorld().dropItemNaturally(player.getLocation(), physical);
      return true;
    } catch (RuntimeException commitFailure) {
      state.claimedLoadout = oldClaimed;
      state.capacity = oldCapacity;
      state.plan = oldPlan;
      state.layout = oldLayout;
      state.snapshot = oldSnapshot;
      if (oldExpected != null) state.expectedEquipment.put(slot, oldExpected);
      state.version++;
      player.getInventory().setItem(slot, physical);
      try {
        render(player, oldPlan, oldLayout);
        enforceEquipmentLocks(player, state);
        if (oldSnapshot != null) applySnapshot(player, oldSnapshot);
      } catch (RuntimeException rollbackFailure) {
        commitFailure.addSuppressed(rollbackFailure);
        markInvalid(player, state, commitFailure);
      }
      return false;
    }
  }

  private Optional<LocatedPlacement> locate(CarryState state, UUID instanceId) {
    for (RaidCarryGridPlanner.Region region : state.plan.regions()) {
      ItemGrid grid = state.layout.find(region.id()).orElseThrow();
      GridItemPlacement placement = grid.find(instanceId).orElse(null);
      if (placement != null) return Optional.of(new LocatedPlacement(region, placement));
    }
    return Optional.empty();
  }

  private void synchronizePhysical(Player player, CarryState state) {
    PlayerInventory inventory = player.getInventory();
    Map<UUID, ItemInstance> updated = new LinkedHashMap<>();
    Set<UUID> consumed = new LinkedHashSet<>();

    for (RaidCarryGridPlanner.Region region : state.plan.regions()) {
      ItemGrid grid = state.layout.find(region.id()).orElseThrow();
      for (GridItemPlacement placement : grid.placements()) {
        int slot = region.physicalSlot(placement.anchor()).orElseThrow();
        ItemStack physical = inventory.getItem(slot);
        if (empty(physical)) {
          if (!consumable(placement.item())) {
            throw new IllegalStateException("携行锚点物品消失: " + placement.item().instanceId());
          }
          consumed.add(placement.item().instanceId());
          continue;
        }
        if (placeholder(physical)) {
          throw new IllegalStateException("携行锚点被投影物覆盖: " + slot);
        }
        ItemInstance actual = scanSingle(physical);
        if (!actual.instanceId().equals(placement.item().instanceId())
            || !actual.itemId().equals(placement.item().itemId())) {
          throw new IllegalStateException("携行锚点身份被替换: " + slot);
        }
        if (actual.quantity() > placement.item().quantity()) {
          throw new IllegalStateException("携行锚点数量未经准入增加: " + slot);
        }
        updated.put(actual.instanceId(), actual);
      }
    }

    // Validate the old projection before accepting a normal consumption update. This catches a
    // moved/duplicated anchor in an otherwise empty cell during the same tick.
    for (RaidCarryGridPlanner.Region region : state.plan.regions()) {
      ItemGrid grid = state.layout.find(region.id()).orElseThrow();
      for (RaidCarryGridPlanner.CellSlot cell : region.cells()) {
        ItemStack physical = inventory.getItem(cell.physicalSlot());
        GridItemPlacement placement = grids.placementAt(grid, cell.position()).orElse(null);
        if (placement == null) {
          if (!empty(physical)) {
            throw new IllegalStateException("空网格单元出现未授权物品: " + cell.physicalSlot());
          }
          continue;
        }
        if (placement.anchor().equals(cell.position())) {
          if (consumed.contains(placement.item().instanceId())) {
            if (!empty(physical)) throw new IllegalStateException("已消耗锚点并非空槽");
          } else if (empty(physical) || placeholder(physical)) {
            throw new IllegalStateException("携行锚点缺少真实物品: " + cell.physicalSlot());
          }
        } else if (!ghost(physical)) {
          throw new IllegalStateException("覆盖单元缺少正确 ghost: " + cell.physicalSlot());
        }
      }
    }
    for (int slot : state.plan.lockedPhysicalSlots()) {
      if (!locked(inventory.getItem(slot))) {
        throw new IllegalStateException("锁定容器单元被修改: " + slot);
      }
    }

    ItemGridLayout rebuilt = rebuildLayout(state.layout, updated, consumed);
    if (!rebuilt.equals(state.layout)) {
      state.layout = rebuilt;
      state.version++;
      render(player, state, rebuilt);
    }
  }

  private ItemGridLayout rebuildLayout(ItemGridLayout layout,
      Map<UUID, ItemInstance> updated, Set<UUID> removed) {
    Map<String, ItemGrid> rebuilt = new LinkedHashMap<>();
    for (Map.Entry<String, ItemGrid> entry : layout.grids().entrySet()) {
      ItemGrid old = entry.getValue();
      List<GridItemPlacement> placements = new ArrayList<>();
      for (GridItemPlacement placement : old.placements()) {
        UUID identity = placement.item().instanceId();
        if (removed.contains(identity)) continue;
        ItemInstance item = updated.getOrDefault(identity, placement.item());
        placements.add(new GridItemPlacement(item, placement.anchor(), placement.rotation(),
            placement.footprint()));
      }
      ItemGrid grid = new ItemGrid(old.width(), old.height(), placements, old.shape());
      grids.occupiedCells(grid);
      rebuilt.put(entry.getKey(), grid);
    }
    return new ItemGridLayout(rebuilt);
  }

  private void render(Player player, CarryState state, ItemGridLayout layout) {
    render(player, state.plan, layout);
  }

  private void render(Player player, RaidCarryGridPlanner.Plan plan, ItemGridLayout layout) {
    PlayerInventory inventory = player.getInventory();
    for (RaidCarryGridPlanner.Region region : plan.regions()) {
      ItemGrid grid = layout.find(region.id()).orElseThrow();
      for (RaidCarryGridPlanner.CellSlot cell : region.cells()) {
        GridItemPlacement placement = grids.placementAt(grid, cell.position()).orElse(null);
        ItemStack projection = placement == null ? null
            : placement.anchor().equals(cell.position())
                ? physicalStack(placement.item())
                : ghost();
        inventory.setItem(cell.physicalSlot(), projection);
      }
    }
    for (int slot : plan.lockedPhysicalSlots()) inventory.setItem(slot, lockedSlot());
  }

  private ItemStack physicalStack(ItemInstance item) {
    List<ItemStack> restored = itemStacks.fromInstance(item);
    if (restored.size() != 1) {
      throw new IllegalStateException("一个网格锚点只能对应一个物理堆栈: " + item.instanceId());
    }
    return restored.getFirst();
  }

  private ItemInstance scanSingle(ItemStack stack) {
    if (empty(stack) || placeholder(stack)) {
      throw new IllegalArgumentException("网格只接受真实 Craftov 物品");
    }
    List<ItemInstance> scanned = itemStacks.scan(new ItemStack[] {stack.clone()});
    if (scanned.size() != 1 || itemStacks.read(stack).isEmpty()) {
      throw new IllegalArgumentException("网格只接受一个有效物理 Craftov 堆栈");
    }
    ItemInstance item = scanned.getFirst();
    physicalStack(item);
    return item;
  }

  private RaidCarrySnapshot snapshotForLayout(
      Player player, CarryState state, ItemGridLayout layout) {
    return snapshotForLayout(player, state.capacity, layout, -1);
  }

  private MenuView menuView(CarryState state) {
    return new MenuView(state.gameId, state.epoch, state.version, state.capacity,
        state.snapshot, state.plan.regions(), state.plan.lockedPhysicalSlots(), state.layout);
  }

  private void notifyDeactivated(Player player) {
    for (Consumer<Player> listener : List.copyOf(deactivationListeners)) {
      try {
        listener.accept(player);
      } catch (RuntimeException failure) {
        logBestEffort(Level.WARNING, "关闭携行整理视图失败: " + player.getUniqueId(), failure);
      }
    }
  }

  private RaidCarrySnapshot snapshotForLayout(Player player, RaidCarryCapacity capacity,
      ItemGridLayout layout, int omittedEquipmentSlot) {
    List<ItemStack> equipmentStacks = new ArrayList<>();
    PlayerInventory inventory = player.getInventory();
    for (int slot : EQUIPMENT_STORAGE_SLOTS) {
      if (slot != omittedEquipmentSlot) equipmentStacks.add(inventory.getItem(slot));
    }
    for (ItemStack armor : inventory.getArmorContents()) equipmentStacks.add(armor);
    equipmentStacks.add(inventory.getItemInOffHand());
    List<ItemInstance> equipment = new ArrayList<>();
    Set<UUID> equipmentIdentities = new LinkedHashSet<>();
    for (ItemStack stack : equipmentStacks) {
      if (empty(stack) || placeholder(stack)) continue;
      ItemInstance item = scanSingle(stack);
      if (!equipmentIdentities.add(item.instanceId())) {
        throw new IllegalStateException("装备位出现重复物理身份: " + item.instanceId());
      }
      equipment.add(item);
    }
    List<ItemInstance> cargo = layout.grids().values().stream()
        .flatMap(grid -> grid.placements().stream())
        .map(GridItemPlacement::item).toList();
    int occupied = layout.grids().values().stream()
        .mapToInt(grid -> grids.occupiedCells(grid).size()).sum();
    return carry.snapshot(capacity, equipment, cargo, occupied);
  }

  private boolean validSnapshot(Player player, CarryState state, ItemGridLayout layout) {
    try {
      snapshotForLayout(player, state, layout);
      return true;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private void rollback(Player player, CarryState state, ItemGridLayout layout,
      RaidCarrySnapshot snapshot) {
    render(player, state, layout);
    state.layout = layout;
    state.snapshot = snapshot;
    state.version++;
    applySnapshot(player, snapshot);
  }

  private CarryState requireState(Player player) {
    CarryState state = states.get(player.getUniqueId());
    if (state == null) throw new IllegalStateException("玩家尚未安装局内携行布局");
    return state;
  }

  private void validateInitialEquipment(Player player, CarryState state) {
    PlayerInventory inventory = player.getInventory();
    for (int slot : TRACKED_EQUIPMENT_SLOTS) {
      ItemInstance expected = state.expectedEquipment.get(slot);
      ItemStack stack = inventory.getItem(slot);
      if (expected == null) {
        if (!empty(stack)) throw new IllegalStateException("保留装备位发生冲突: " + slot);
      } else if (!expectedEquipmentStack(stack, expected)) {
        throw new IllegalStateException("具名装备实例与保留位置不匹配: " + slot);
      }
    }
  }

  private void validateEquipment(Player player, CarryState state) {
    PlayerInventory inventory = player.getInventory();
    for (int slot : TRACKED_EQUIPMENT_SLOTS) {
      ItemInstance expected = state.expectedEquipment.get(slot);
      ItemStack stack = inventory.getItem(slot);
      boolean real = !empty(stack) && !placeholder(stack);
      if (expected == null) {
        if (real) throw new IllegalStateException("保留装备位出现未授权物品: " + slot);
        continue;
      }
      ItemInstance actual = real ? scanSingle(stack) : null;
      RaidEquipmentAuthority.Outcome outcome = RaidEquipmentAuthority.reconcile(
          expected, actual, consumable(expected));
      switch (outcome) {
        case UNCHANGED -> {
          // Same identity/type/quantity. Payload may legitimately reflect durability or custom NBT.
        }
        case DECREASED -> {
          state.expectedEquipment.put(slot, actual);
          state.version++;
        }
        case CONSUMED -> {
          state.expectedEquipment.remove(slot);
          state.version++;
        }
        case REJECTED -> throw new IllegalStateException(
            "保留装备位身份、类型或数量未经授权变更: " + slot);
      }
    }
  }

  private void enforceEquipmentLocks(Player player, CarryState state) {
    PlayerInventory inventory = player.getInventory();
    for (int slot : EQUIPMENT_STORAGE_SLOTS) {
      ItemInstance expected = state.expectedEquipment.get(slot);
      if (expected != null && expectedEquipmentStack(inventory.getItem(slot), expected)) continue;
      inventory.setItem(slot, lockedSlot());
    }
  }

  private Map<Integer, ItemInstance> expectedEquipmentBaselines(List<StoredItem> claimed) {
    Map<Integer, UUID> expected = new LinkedHashMap<>();
    Map<Integer, ItemInstance> baselines = new LinkedHashMap<>();
    for (StoredItem item : claimed) {
      if (item.loadoutSlot() == null) continue;
      int slot = slotIndex(item.loadoutSlot());
      if (slot < 0) continue;
      if (expected.putIfAbsent(slot, item.instanceId()) != null) {
        throw new IllegalStateException("保留装备位重复: " + item.loadoutSlot());
      }
      ItemStack physicalTemplate = itemStacks.fromStored(item).getFirst();
      baselines.put(slot, new ItemInstance(item.instanceId(), item.itemId(),
          physicalTemplate.getAmount(), item.opaquePayload()));
    }
    return baselines;
  }

  private void captureLegacyEquipment(Player player, Map<Integer, ItemInstance> expected) {
    for (int slot : TRACKED_EQUIPMENT_SLOTS) {
      ItemStack stack = player.getInventory().getItem(slot);
      if (!expected.containsKey(slot) && itemStacks.read(stack).isPresent()) {
        expected.put(slot, scanSingle(stack));
      }
    }
  }

  private static int slotIndex(LoadoutSlot slot) {
    return switch (slot) {
      case MAIN_HAND -> RaidCarryLayout.MAIN_HAND_SLOT;
      case QUICK_USE_1 -> RaidCarryLayout.QUICK_USE_1_SLOT;
      case QUICK_USE_2 -> RaidCarryLayout.QUICK_USE_2_SLOT;
      case CHEST_RIG -> RaidCarryLayout.CHEST_RIG_SLOT;
      case BACKPACK -> RaidCarryLayout.BACKPACK_SLOT;
      case OFF_HAND -> 40;
      case HEAD -> 39;
      case BODY_ARMOR -> 38;
      default -> -1;
    };
  }

  private boolean consumable(ItemInstance item) {
    return switch (items.require(item.itemId()).category()) {
      case PROJECTILE, HEALING, FOOD, POTION, CONSUMABLE -> true;
      default -> false;
    };
  }

  private boolean expectedInstance(ItemStack stack, UUID expected) {
    if (empty(stack) || placeholder(stack)) return false;
    return itemStacks.read(stack).map(item -> item.instanceId().equals(expected)).orElse(false);
  }

  private boolean validFrozenDrop(PlayerDropItemEvent event, Player player,
      CarryState state, FrozenDrop frozen) {
    ItemStack dropped = event.getItemDrop().getItemStack();
    var tagged = itemStacks.read(dropped).orElse(null);
    if (tagged == null) return false;
    ItemStack remaining = player.getInventory().getItem(frozen.slot());
    int remainingAmount;
    if (empty(remaining)) {
      remainingAmount = 0;
    } else {
      var remainingTag = itemStacks.read(remaining).orElse(null);
      if (remainingTag == null || !remainingTag.instanceId().equals(tagged.instanceId())
          || !remainingTag.itemId().equals(tagged.itemId())) return false;
      remainingAmount = remaining.getAmount();
    }
    int originalAmount;
    try {
      originalAmount = Math.addExact(dropped.getAmount(), remainingAmount);
    } catch (ArithmeticException invalidAmount) {
      return false;
    }
    var current = new RaidDropCommitPolicy.DropStamp(player.getUniqueId(),
        player.getInventory().getHeldItemSlot(), event.getItemDrop().getUniqueId(),
        tagged.instanceId(), tagged.itemId(), dropped.getAmount(), remainingAmount,
        originalAmount, state.version);
    return RaidDropCommitPolicy.mayCommit(event.isCancelled(), frozen.stamp(), current);
  }

  private boolean expectedEquipmentStack(ItemStack stack, ItemInstance expected) {
    if (empty(stack) || placeholder(stack)) return false;
    try {
      ItemInstance actual = scanSingle(stack);
      return RaidEquipmentAuthority.reconcile(expected, actual, false)
          == RaidEquipmentAuthority.Outcome.UNCHANGED;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private boolean containerOrPlaceholder(ItemStack stack) {
    if (placeholder(stack)) return true;
    var tagged = itemStacks.read(stack).orElse(null);
    if (tagged == null) return false;
    ItemCategory category = items.require(tagged.itemId()).category();
    return category == ItemCategory.CHEST_RIG || category == ItemCategory.BACKPACK;
  }

  private static List<Integer> allCargoStorageSlots() {
    List<Integer> result = new ArrayList<>();
    for (int slot = RaidCarryLayout.FIRST_POCKET_SLOT;
        slot <= RaidCarryLayout.LAST_POCKET_SLOT; slot++) result.add(slot);
    for (int slot = RaidCarryLayout.FIRST_CONTAINER_SLOT;
        slot <= RaidCarryLayout.LAST_CONTAINER_SLOT; slot++) result.add(slot);
    return result;
  }

  private void markInvalid(Player player, CarryState state, RuntimeException invalid) {
    state.invalid = true;
    state.snapshot = null;
    player.setSprinting(false);
    applyMovement(player, emergencyMovementMultiplier);
    if (!state.invalidLogged) {
      state.invalidLogged = true;
      logBestEffort(Level.WARNING, "局内网格与物理锚点不一致，已禁止继续准入: "
          + player.getUniqueId(), invalid);
    }
  }

  private void logBestEffort(Level level, String message, Throwable failure) {
    try {
      logger.log(level, message, failure);
    } catch (RuntimeException ignored) {
      // Logging is never part of a live or already committed inventory transaction.
    }
  }

  private void applySnapshot(Player player, RaidCarrySnapshot snapshot) {
    applyMovement(player, snapshot.movementMultiplier());
    if (overweight(snapshot) && player.isSprinting()) player.setSprinting(false);
  }

  private static boolean overweight(RaidCarrySnapshot snapshot) {
    return snapshot.totalWeightGrams() > snapshot.capacity().comfortableWeightGrams();
  }

  private void applyMovement(Player player, double multiplier) {
    AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
    if (speed == null) return;
    AttributeModifier existing = speed.getModifier(movementKey);
    double amount = multiplier - 1.0;
    if (existing != null && Math.abs(existing.getAmount() - amount) < 0.000_001) return;
    if (existing != null) speed.removeModifier(existing);
    if (amount < -0.000_001) {
      speed.addTransientModifier(new AttributeModifier(movementKey, amount,
          AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }
  }

  private void removeMovement(Player player) {
    AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
    if (speed == null) return;
    AttributeModifier existing = speed.getModifier(movementKey);
    if (existing != null) speed.removeModifier(existing);
  }

  private ItemStack lockedSlot() {
    ItemStack stack = new ItemStack(Material.BARRIER);
    stack.editMeta(meta -> {
      meta.itemName(Component.text("未解锁的携行槽位", NamedTextColor.DARK_GRAY)
          .decoration(TextDecoration.ITALIC, false));
      meta.getPersistentDataContainer().set(lockKey, PersistentDataType.BYTE, (byte) 1);
    });
    return stack;
  }

  private ItemStack ghost() {
    ItemStack stack = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
    stack.editMeta(meta -> {
      meta.itemName(Component.text("物品占用", NamedTextColor.DARK_GRAY)
          .decoration(TextDecoration.ITALIC, false));
      meta.getPersistentDataContainer().set(ghostKey, PersistentDataType.BYTE, (byte) 1);
    });
    return stack;
  }

  public boolean placeholder(ItemStack stack) {
    return locked(stack) || ghost(stack);
  }

  private boolean locked(ItemStack stack) {
    return has(stack, lockKey, PersistentDataType.BYTE);
  }

  private boolean ghost(ItemStack stack) {
    return has(stack, ghostKey, PersistentDataType.BYTE);
  }

  private static <T, Z> boolean has(ItemStack stack, NamespacedKey key,
      PersistentDataType<T, Z> type) {
    if (empty(stack) || !stack.hasItemMeta()) return false;
    PersistentDataContainer data = stack.getItemMeta().getPersistentDataContainer();
    return data.has(key, type)
        && !data.has(CraftovItemStacks.ITEM_ID_KEY, PersistentDataType.STRING);
  }

  private void removePlaceholders(PlayerInventory inventory) {
    for (int slot = 0; slot <= RaidCarryLayout.LAST_CONTAINER_SLOT; slot++) {
      if (placeholder(inventory.getItem(slot))) inventory.setItem(slot, null);
    }
  }

  private static boolean empty(ItemStack stack) {
    return stack == null || stack.isEmpty();
  }

  @Override
  public void close() {
    ticker.cancel();
    domainSubscription.unsubscribe();
    for (UUID playerId : List.copyOf(states.keySet())) {
      Player player = Bukkit.getPlayer(playerId);
      if (player == null) states.remove(playerId);
      else clear(player);
    }
    deactivationListeners.clear();
  }

  public enum MenuMutationKind {
    MOVE,
    ROTATE
  }

  public enum MenuMutationStatus {
    COMMITTED,
    STALE,
    REJECTED,
    INACTIVE
  }

  public record MenuView(
      CraftovGameId gameId,
      long epoch,
      long version,
      RaidCarryCapacity capacity,
      RaidCarrySnapshot snapshot,
      List<RaidCarryGridPlanner.Region> regions,
      Set<Integer> lockedPhysicalSlots,
      ItemGridLayout layout) {

    public MenuView {
      Preconditions.checkNotNull(gameId, "gameId");
      Preconditions.checkArgument(epoch > 0, "epoch must be positive");
      Preconditions.checkArgument(version >= 0, "version must not be negative");
      Preconditions.checkNotNull(capacity, "capacity");
      Preconditions.checkNotNull(snapshot, "snapshot");
      regions = List.copyOf(Preconditions.checkNotNull(regions, "regions"));
      lockedPhysicalSlots = Set.copyOf(
          Preconditions.checkNotNull(lockedPhysicalSlots, "lockedPhysicalSlots"));
      Preconditions.checkNotNull(layout, "layout");
    }
  }

  public record MenuMutation(
      CraftovGameId gameId,
      long epoch,
      long version,
      UUID instanceId,
      MenuMutationKind kind,
      String targetGridId,
      GridPosition targetAnchor) {

    public MenuMutation {
      Preconditions.checkNotNull(gameId, "gameId");
      Preconditions.checkArgument(epoch > 0, "epoch must be positive");
      Preconditions.checkArgument(version >= 0, "version must not be negative");
      Preconditions.checkNotNull(instanceId, "instanceId");
      Preconditions.checkNotNull(kind, "kind");
      Preconditions.checkNotNull(targetGridId, "targetGridId");
      Preconditions.checkNotNull(targetAnchor, "targetAnchor");
    }
  }

  public record MenuMutationResult(
      MenuMutationStatus status,
      @Nullable MenuView view) {

    public MenuMutationResult {
      Preconditions.checkNotNull(status, "status");
      Preconditions.checkArgument(status != MenuMutationStatus.COMMITTED || view != null,
          "committed mutations require a current view");
    }
  }

  private record LocatedPlacement(
      RaidCarryGridPlanner.Region region,
      GridItemPlacement placement) {}

  private record FrozenDrop(RaidDropCommitPolicy.DropStamp stamp, ItemStack dropped) {

    private FrozenDrop {
      Preconditions.checkNotNull(stamp, "stamp");
      dropped = dropped.clone();
    }

    private int slot() {
      return stamp.slot();
    }

    private int remainingAmount() {
      return stamp.remainingAmount();
    }

    @Override
    public ItemStack dropped() {
      return dropped.clone();
    }
  }

  private static final class CarryState {
    private final CraftovGameId gameId;
    private final long epoch;
    private List<StoredItem> claimedLoadout;
    private RaidCarryCapacity capacity;
    private final Map<Integer, ItemInstance> expectedEquipment;
    private RaidCarryGridPlanner.Plan plan;
    private ItemGridLayout layout;
    private RaidCarrySnapshot snapshot;
    private boolean invalid;
    private boolean invalidLogged;
    private long version;

    private CarryState(CraftovGameId gameId, long epoch, List<StoredItem> claimedLoadout,
        RaidCarryCapacity capacity, Map<Integer, ItemInstance> expectedEquipment,
        RaidCarryGridPlanner.Plan plan, ItemGridLayout layout) {
      this.gameId = gameId;
      this.epoch = epoch;
      this.claimedLoadout = claimedLoadout;
      this.capacity = capacity;
      this.expectedEquipment = new LinkedHashMap<>(expectedEquipment);
      this.plan = plan;
      this.layout = layout;
    }
  }
}
