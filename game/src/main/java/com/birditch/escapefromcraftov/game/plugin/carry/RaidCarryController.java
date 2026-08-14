package com.birditch.escapefromcraftov.game.plugin.carry;

import com.birditch.escapefromcraftov.api.event.EventBus;
import com.birditch.escapefromcraftov.api.event.EventSubscription;
import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.event.game.PlayerLeftCraftovEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.inventory.LoadoutSlot;
import com.birditch.escapefromcraftov.api.inventory.RaidCarryCapacity;
import com.birditch.escapefromcraftov.api.inventory.RaidCarryRules;
import com.birditch.escapefromcraftov.api.inventory.RaidCarryService;
import com.birditch.escapefromcraftov.api.inventory.RaidCarrySnapshot;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.item.ItemCategory;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Enforces the physical raid inventory and applies registry-backed carried-weight movement.
 *
 * <p>The movement curve comes exclusively from {@link RaidCarryService}; its default tuning is
 * an adjustable game rule, not a claim about another game's unpublished numeric thresholds.</p>
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

  private final RaidCarryService carry;
  private final CraftovItemStacks itemStacks;
  private final ItemRegistry items;
  private final Logger logger;
  private final NamespacedKey lockKey;
  private final NamespacedKey movementKey;
  private final double emergencyMovementMultiplier;
  private final EventSubscription domainSubscription;
  private final ScheduledTask ticker;
  private final Map<UUID, CarryState> states = new HashMap<>();

  public RaidCarryController(Plugin plugin, EventBus events, CraftovScheduler scheduler,
      RaidCarryService carry, CraftovItemStacks itemStacks, ItemRegistry items) {
    Plugin checkedPlugin = Preconditions.checkNotNull(plugin, "plugin");
    this.carry = Preconditions.checkNotNull(carry, "carry");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    this.items = Preconditions.checkNotNull(items, "items");
    CraftovScheduler checkedScheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.logger = checkedPlugin.getLogger();
    this.lockKey = new NamespacedKey(checkedPlugin, "raid_carry_locked_slot");
    this.movementKey = new NamespacedKey(checkedPlugin, "raid_carry_weight");
    this.emergencyMovementMultiplier = RaidCarryRules.defaults().minimumMovementMultiplier();
    this.domainSubscription = Preconditions.checkNotNull(events, "events").register(this);
    this.ticker = checkedScheduler.runTimer(Duration.ZERO, REFRESH_PERIOD, this::tick);
  }

  /** Installs locks after named equipment was mapped and before legacy cargo is added. */
  public void install(Player player, CraftovGameId gameId, List<StoredItem> claimedLoadout) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(gameId, "gameId");
    List<StoredItem> claimed = List.copyOf(
        Preconditions.checkNotNull(claimedLoadout, "claimedLoadout"));
    clear(player);
    RaidCarryCapacity capacity = carry.capacityFor(claimed);
    CarryState state = new CarryState(gameId, claimed, capacity, expectedEquipment(claimed));
    validateInitialLayout(player, state);
    states.put(player.getUniqueId(), state);
    enforceLayout(player, state);
  }

  /** Rebuilds the authoritative snapshot and immediately applies its movement multiplier. */
  public RaidCarrySnapshot refresh(Player player) {
    Preconditions.checkNotNull(player, "player");
    CarryState state = states.get(player.getUniqueId());
    if (state == null) throw new IllegalStateException("玩家尚未安装局内携行布局");
    RaidCarrySnapshot snapshot = buildSnapshot(player, state.capacity, false);
    state.snapshot = snapshot;
    state.invalid = false;
    state.invalidLogged = false;
    applyMovement(player, snapshot.movementMultiplier());
    if (overweight(snapshot) && player.isSprinting()) player.setSprinting(false);
    return snapshot;
  }

  /**
   * Produces the only physical-inventory snapshot that may cross the durable
   * extraction boundary. Event guards improve UX, but this method is the final
   * authority against command/creative/other-plugin inventory mutations.
   */
  public RaidCarrySnapshot settlementSnapshot(Player player, CraftovGameId gameId) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(gameId, "gameId");
    CarryState state = states.get(player.getUniqueId());
    if (state == null || !state.gameId.equals(gameId)) {
      throw new IllegalStateException("玩家没有当前行动的携行状态");
    }
    validateSettlementLayout(player, state);
    return refresh(player);
  }

  /** Latest successfully validated view. Callers must not use it as a settlement payload. */
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

  /** Admission guard for plugin-driven loot transfers that never fire a Bukkit pickup event. */
  public boolean canAccept(Player player, ItemStack incoming) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(incoming, "incoming");
    CarryState state = states.get(player.getUniqueId());
    return state != null && !state.invalid
        && canAccept(player, state, incoming, incoming.getAmount());
  }

  /** Removes only synthetic slot locks and weight state; real raid items are untouched. */
  public void clear(Player player) {
    Preconditions.checkNotNull(player, "player");
    states.remove(player.getUniqueId());
    removePlaceholders(player.getInventory());
    removeMovement(player);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  @SuppressWarnings("removal") // Paper still emits this action for legacy number-key moves.
  public void onInventoryClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) return;
    CarryState state = states.get(player.getUniqueId());
    if (state == null) return;
    ItemStack current = event.getCurrentItem();
    ItemStack cursor = event.getCursor();
    if (event.getAction() == InventoryAction.CLONE_STACK
        && (itemStacks.isCraftovItem(current) || itemStacks.isCraftovItem(cursor))) {
      event.setCancelled(true);
      return;
    }
    if (placeholder(current) || placeholder(cursor)) {
      event.setCancelled(true);
      return;
    }
    if (event.getClick() == ClickType.SWAP_OFFHAND
        || (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
            && containerOrPlaceholder(current))) {
      event.setCancelled(true);
      return;
    }
    if (event.getClickedInventory() == player.getInventory()
        && protectedInventorySlot(event.getSlot(), state)) {
      event.setCancelled(true);
      return;
    }
    int hotbar = event.getHotbarButton();
    if (hotbar >= 0 && protectedInventorySlot(hotbar, state)) {
      event.setCancelled(true);
      return;
    }
    if ((event.getAction() == InventoryAction.HOTBAR_SWAP
        || event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD)
        && event.getClickedInventory() != player.getInventory()
        && itemStacks.isCraftovItem(current)) {
      event.setCancelled(true);
      return;
    }
    if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR
        && itemStacks.isCraftovItem(cursor)) {
      event.setCancelled(true);
      return;
    }
    boolean enteringFromTop = event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
        && event.getClickedInventory() != null
        && event.getClickedInventory() != player.getInventory();
    if (enteringFromTop && itemStacks.isCraftovItem(current)
        && !canAccept(player, state, current, current.getAmount())) {
      rejectCapacity(player, event);
      return;
    }
    boolean placingInPlayer = event.getClickedInventory() == player.getInventory()
        && switch (event.getAction()) {
          case PLACE_ALL, PLACE_SOME, PLACE_ONE -> true;
          default -> false;
        };
    if (placingInPlayer && itemStacks.isCraftovItem(cursor)
        && !canAccept(player, state, cursor, cursor.getAmount())) {
      rejectCapacity(player, event);
      return;
    }
    if (event.getAction() == InventoryAction.SWAP_WITH_CURSOR
        && event.getClickedInventory() == player.getInventory()
        && itemStacks.isCraftovItem(cursor)) {
      // A pick-then-place sequence remains available and has an unambiguous projection.
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInventoryDrag(InventoryDragEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) return;
    CarryState state = states.get(player.getUniqueId());
    if (state == null) return;
    for (int rawSlot : event.getRawSlots()) {
      if (event.getView().getInventory(rawSlot) != player.getInventory()) continue;
      if (protectedInventorySlot(event.getView().convertSlot(rawSlot), state)
          || itemStacks.isCraftovItem(event.getOldCursor())) {
        event.setCancelled(true);
        return;
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onSwapHands(PlayerSwapHandItemsEvent event) {
    if (states.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInteract(PlayerInteractEvent event) {
    if (!states.containsKey(event.getPlayer().getUniqueId())) return;
    if (containerOrPlaceholder(event.getItem())) event.setCancelled(true);
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
  public void onDrop(PlayerDropItemEvent event) {
    Player player = event.getPlayer();
    CarryState state = states.get(player.getUniqueId());
    if (state == null) return;
    ItemStack dropped = event.getItemDrop().getItemStack();
    if (placeholder(dropped)) {
      event.setCancelled(true);
      return;
    }
    var tagged = itemStacks.read(dropped).orElse(null);
    if (tagged != null && !items.require(tagged.itemId()).droppable()) {
      event.setCancelled(true);
      return;
    }
    int slot = player.getInventory().getHeldItemSlot();
    LoadoutSlot containerSlot = switch (slot) {
      case RaidCarryLayout.CHEST_RIG_SLOT -> LoadoutSlot.CHEST_RIG;
      case RaidCarryLayout.BACKPACK_SLOT -> LoadoutSlot.BACKPACK;
      default -> null;
    };
    if (containerSlot != null && tagged != null
        && containerSlot.accepts(items.require(tagged.itemId()).category())) {
      ItemStack stillEquipped = player.getInventory().getItem(slot);
      boolean eventStillOwnsSlot = itemStacks.read(stillEquipped)
          .map(equipped -> equipped.instanceId().equals(tagged.instanceId())).orElse(false);
      if (eventStillOwnsSlot) player.getInventory().setItem(slot, null);
      if (!shrinkAfterContainerDrop(player, state, containerSlot)) {
        if (eventStillOwnsSlot) player.getInventory().setItem(slot, stillEquipped);
        event.setCancelled(true);
        player.sendActionBar(Component.text("剩余物资放不下，无法丢弃容器", NamedTextColor.RED));
      }
      return;
    }
    if (RaidCarryLayout.reservedEquipmentSlot(slot)
        && empty(player.getInventory().getItem(slot))) {
      player.getInventory().setItem(slot, placeholder());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onPickup(EntityPickupItemEvent event) {
    if (!(event.getEntity() instanceof Player player)) return;
    CarryState state = states.get(player.getUniqueId());
    if (state == null) return;
    ItemStack incoming = event.getItem().getItemStack();
    if (!itemStacks.isCraftovItem(incoming)) return;
    int amount = Math.max(0, incoming.getAmount() - event.getRemaining());
    if (amount == 0) amount = incoming.getAmount();
    if (canAccept(player, state, incoming, amount)) return;
    event.setCancelled(true);
    player.sendActionBar(Component.text("携行空间或负重已达上限", NamedTextColor.RED));
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
    if (player == null) {
      states.remove(event.playerId());
      return;
    }
    clear(player);
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
      CarryState state = entry.getValue();
      try {
        enforceLayout(player, state);
        refresh(player);
      } catch (RuntimeException invalid) {
        state.invalid = true;
        state.snapshot = null;
        player.setSprinting(false);
        applyMovement(player, emergencyMovementMultiplier);
        if (!state.invalidLogged) {
          state.invalidLogged = true;
          logger.log(Level.WARNING,
              "局内携行状态无效，已禁止继续冲刺: " + player.getUniqueId(), invalid);
        }
      }
    }
  }

  private boolean shrinkAfterContainerDrop(Player player, CarryState state,
      LoadoutSlot removedSlot) {
    List<StoredItem> remaining = state.claimedLoadout.stream()
        .filter(item -> item.loadoutSlot() != removedSlot).toList();
    RaidCarryCapacity candidate;
    try {
      candidate = carry.capacityFor(remaining);
    } catch (RuntimeException invalid) {
      return false;
    }
    int occupied = occupiedPhysicalCargoCells(player.getInventory());
    if (!RaidCarryLayout.canShrink(occupied, candidate.containerSlots())) return false;
    try {
      buildSnapshot(player, candidate, true);
    } catch (RuntimeException overCapacity) {
      return false;
    }
    compactCargo(player.getInventory(), candidate.containerSlots());
    state.claimedLoadout = remaining;
    state.capacity = candidate;
    state.expectedEquipment.remove(slotIndex(removedSlot));
    enforceLayout(player, state);
    refresh(player);
    return true;
  }

  private RaidCarrySnapshot buildSnapshot(Player player, RaidCarryCapacity capacity,
      boolean includeEveryContainerCell) {
    PlayerInventory inventory = player.getInventory();
    List<ItemStack> equipmentStacks = new ArrayList<>();
    for (int slot : EQUIPMENT_STORAGE_SLOTS) {
      equipmentStacks.add(inventory.getItem(slot));
    }
    for (ItemStack armor : inventory.getArmorContents()) {
      equipmentStacks.add(armor);
    }
    equipmentStacks.add(inventory.getItemInOffHand());
    List<ItemStack> cargoStacks = new ArrayList<>();
    for (int slot = RaidCarryLayout.FIRST_POCKET_SLOT;
        slot <= RaidCarryLayout.LAST_POCKET_SLOT; slot++) {
      cargoStacks.add(inventory.getItem(slot));
    }
    int lastContainer = includeEveryContainerCell
        ? RaidCarryLayout.LAST_CONTAINER_SLOT
        : RaidCarryLayout.FIRST_CONTAINER_SLOT + capacity.containerSlots() - 1;
    for (int slot = RaidCarryLayout.FIRST_CONTAINER_SLOT; slot <= lastContainer; slot++) {
      cargoStacks.add(inventory.getItem(slot));
    }
    List<ItemInstance> equipment = itemStacks.scan(equipmentStacks.toArray(ItemStack[]::new));
    List<ItemInstance> cargo = itemStacks.scan(cargoStacks.toArray(ItemStack[]::new));
    int occupiedCargoSlots = occupiedSnapshotCargoCells(inventory, capacity.containerSlots(),
        includeEveryContainerCell);
    return carry.snapshot(capacity, equipment, cargo, occupiedCargoSlots);
  }

  private boolean canAccept(Player player, CarryState state, ItemStack incoming, int amount) {
    if (amount <= 0) return true;
    var tagged = itemStacks.read(incoming).orElse(null);
    if (tagged == null) return true;
    RaidCarrySnapshot current;
    try {
      current = buildSnapshot(player, state.capacity, false);
    } catch (RuntimeException invalid) {
      return false;
    }
    ItemDefinition definition = items.require(tagged.itemId());
    long addedWeight;
    try {
      addedWeight = Math.multiplyExact((long) definition.weightGrams(), amount);
      if (Math.addExact(current.totalWeightGrams(), addedWeight)
          > state.capacity.maximumWeightGrams()) return false;
    } catch (ArithmeticException overflow) {
      return false;
    }
    return physicalRoom(player.getInventory(), state.capacity.containerSlots(), incoming, amount);
  }

  private boolean physicalRoom(PlayerInventory inventory, int containerSlots,
      ItemStack incoming, int amount) {
    int remaining = amount;
    for (int slot = 0; slot <= RaidCarryLayout.LAST_CONTAINER_SLOT; slot++) {
      ItemStack existing = inventory.getItem(slot);
      if (empty(existing) || placeholder(existing) || !existing.isSimilar(incoming)) continue;
      int maximum = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize());
      remaining -= Math.max(0, maximum - existing.getAmount());
      if (remaining <= 0) return true;
    }
    for (int slot : RaidCarryLayout.cargoSlots(containerSlots)) {
      if (empty(inventory.getItem(slot))) return true;
    }
    return false;
  }

  private void validateInitialLayout(Player player, CarryState state) {
    PlayerInventory inventory = player.getInventory();
    for (int slot : EQUIPMENT_STORAGE_SLOTS) {
      UUID expected = state.expectedEquipment.get(slot);
      ItemStack stack = inventory.getItem(slot);
      if (expected == null) {
        if (!empty(stack)) throw new IllegalStateException("保留装备位发生冲突: " + slot);
        continue;
      }
      var tagged = itemStacks.read(stack).orElseThrow(() ->
          new IllegalStateException("具名装备未写入保留位置: " + slot));
      if (!tagged.instanceId().equals(expected)) {
        throw new IllegalStateException("具名装备实例与保留位置不匹配: " + slot);
      }
    }
    for (int slot : RaidCarryLayout.lockedStorageSlots(state.capacity.containerSlots())) {
      if (!empty(inventory.getItem(slot))) {
        throw new IllegalStateException("出战物资超过胸挂和背包容量: " + slot);
      }
    }
  }

  private void validateSettlementLayout(Player player, CarryState state) {
    PlayerInventory inventory = player.getInventory();
    for (int slot : EQUIPMENT_STORAGE_SLOTS) {
      UUID expected = state.expectedEquipment.get(slot);
      ItemStack stack = inventory.getItem(slot);
      boolean real = !empty(stack) && !placeholder(stack);
      if (expected == null) {
        if (real) throw new IllegalStateException("保留装备位出现未授权物品: " + slot);
        continue;
      }
      if (real && !expectedInstance(stack, expected)) {
        throw new IllegalStateException("保留装备位实例被替换: " + slot);
      }
      boolean capacityContainer = slot == RaidCarryLayout.CHEST_RIG_SLOT
          || slot == RaidCarryLayout.BACKPACK_SLOT;
      if (capacityContainer && !expectedInstance(stack, expected)) {
        throw new IllegalStateException("容量容器缺失但携行容量尚未收缩: " + slot);
      }
    }
    for (int slot : RaidCarryLayout.lockedStorageSlots(state.capacity.containerSlots())) {
      ItemStack stack = inventory.getItem(slot);
      if (!empty(stack) && !placeholder(stack)) {
        throw new IllegalStateException("锁定携行槽出现物品: " + slot);
      }
    }
  }

  private void enforceLayout(Player player, CarryState state) {
    PlayerInventory inventory = player.getInventory();
    for (int slot : EQUIPMENT_STORAGE_SLOTS) {
      UUID expected = state.expectedEquipment.get(slot);
      ItemStack stack = inventory.getItem(slot);
      if (expected != null && expectedInstance(stack, expected)) continue;
      if (!empty(stack) && !placeholder(stack)) relocateOrDrop(player, state, slot, stack);
      inventory.setItem(slot, placeholder());
    }
    for (int slot : RaidCarryLayout.lockedStorageSlots(state.capacity.containerSlots())) {
      ItemStack stack = inventory.getItem(slot);
      if (placeholder(stack)) continue;
      if (!empty(stack)) relocateOrDrop(player, state, slot, stack);
      inventory.setItem(slot, placeholder());
    }
  }

  private void relocateOrDrop(Player player, CarryState state, int source, ItemStack stack) {
    PlayerInventory inventory = player.getInventory();
    inventory.setItem(source, null);
    for (int destination : RaidCarryLayout.cargoSlots(state.capacity.containerSlots())) {
      if (!empty(inventory.getItem(destination))) continue;
      inventory.setItem(destination, stack);
      return;
    }
    Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), stack);
    dropped.setOwner(player.getUniqueId());
  }

  private void compactCargo(PlayerInventory inventory, int containerSlots) {
    List<ItemStack> cargo = new ArrayList<>();
    for (int slot = RaidCarryLayout.FIRST_POCKET_SLOT;
        slot <= RaidCarryLayout.LAST_CONTAINER_SLOT; slot++) {
      if (slot == RaidCarryLayout.CHEST_RIG_SLOT || slot == RaidCarryLayout.BACKPACK_SLOT) {
        continue;
      }
      ItemStack stack = inventory.getItem(slot);
      if (!empty(stack) && !placeholder(stack)) cargo.add(stack);
      if (slot >= RaidCarryLayout.FIRST_POCKET_SLOT) inventory.setItem(slot, null);
    }
    List<Integer> destinations = RaidCarryLayout.cargoSlots(containerSlots);
    if (cargo.size() > destinations.size()) {
      throw new IllegalStateException("缩容后物资格数不一致");
    }
    for (int index = 0; index < cargo.size(); index++) {
      inventory.setItem(destinations.get(index), cargo.get(index));
    }
  }

  private int occupiedPhysicalCargoCells(PlayerInventory inventory) {
    int occupied = 0;
    for (int slot = RaidCarryLayout.FIRST_POCKET_SLOT;
        slot <= RaidCarryLayout.LAST_CONTAINER_SLOT; slot++) {
      if (slot == RaidCarryLayout.CHEST_RIG_SLOT || slot == RaidCarryLayout.BACKPACK_SLOT) {
        continue;
      }
      ItemStack stack = inventory.getItem(slot);
      if (!empty(stack) && !placeholder(stack)) occupied++;
    }
    return occupied;
  }

  private Map<Integer, UUID> expectedEquipment(List<StoredItem> claimed) {
    Map<Integer, UUID> expected = new LinkedHashMap<>();
    for (StoredItem item : claimed) {
      if (item.loadoutSlot() == null) continue;
      int slot = slotIndex(item.loadoutSlot());
      if (slot < 0) continue;
      if (expected.putIfAbsent(slot, item.instanceId()) != null) {
        throw new IllegalStateException("保留装备位重复: " + item.loadoutSlot());
      }
    }
    return expected;
  }

  private static int slotIndex(LoadoutSlot slot) {
    return switch (slot) {
      case MAIN_HAND -> RaidCarryLayout.MAIN_HAND_SLOT;
      case QUICK_USE_1 -> RaidCarryLayout.QUICK_USE_1_SLOT;
      case QUICK_USE_2 -> RaidCarryLayout.QUICK_USE_2_SLOT;
      case CHEST_RIG -> RaidCarryLayout.CHEST_RIG_SLOT;
      case BACKPACK -> RaidCarryLayout.BACKPACK_SLOT;
      default -> -1;
    };
  }

  private boolean protectedInventorySlot(int slot, CarryState state) {
    if (slot >= 36) return true;
    return RaidCarryLayout.reservedEquipmentSlot(slot)
        || RaidCarryLayout.lockedStorageSlots(state.capacity.containerSlots()).contains(slot);
  }

  private boolean expectedInstance(ItemStack stack, UUID expected) {
    if (empty(stack) || placeholder(stack)) return false;
    return itemStacks.read(stack).map(item -> item.instanceId().equals(expected)).orElse(false);
  }

  private boolean containerOrPlaceholder(ItemStack stack) {
    if (placeholder(stack)) return true;
    var tagged = itemStacks.read(stack).orElse(null);
    if (tagged == null) return false;
    ItemCategory category = items.require(tagged.itemId()).category();
    return category == ItemCategory.CHEST_RIG || category == ItemCategory.BACKPACK;
  }

  private int occupiedSnapshotCargoCells(PlayerInventory inventory, int containerSlots,
      boolean includeEveryContainerCell) {
    int occupied = 0;
    for (int slot = RaidCarryLayout.FIRST_POCKET_SLOT;
        slot <= RaidCarryLayout.LAST_POCKET_SLOT; slot++) {
      ItemStack stack = inventory.getItem(slot);
      if (!empty(stack) && !placeholder(stack)) occupied++;
    }
    int lastContainer = includeEveryContainerCell
        ? RaidCarryLayout.LAST_CONTAINER_SLOT
        : RaidCarryLayout.FIRST_CONTAINER_SLOT + containerSlots - 1;
    for (int slot = RaidCarryLayout.FIRST_CONTAINER_SLOT; slot <= lastContainer; slot++) {
      ItemStack stack = inventory.getItem(slot);
      if (!empty(stack) && !placeholder(stack)) occupied++;
    }
    return occupied;
  }

  private void rejectCapacity(Player player, InventoryClickEvent event) {
    event.setCancelled(true);
    player.sendActionBar(Component.text("携行空间或负重已达上限", NamedTextColor.RED));
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

  private ItemStack placeholder() {
    ItemStack stack = new ItemStack(Material.BARRIER);
    stack.editMeta(meta -> {
      meta.itemName(Component.text("未解锁的携行槽位", NamedTextColor.DARK_GRAY)
          .decoration(TextDecoration.ITALIC, false));
      meta.getPersistentDataContainer().set(lockKey, PersistentDataType.BYTE, (byte) 1);
    });
    return stack;
  }

  public boolean placeholder(ItemStack stack) {
    return !empty(stack) && stack.hasItemMeta()
        && stack.getItemMeta().getPersistentDataContainer().has(lockKey, PersistentDataType.BYTE);
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
  }

  private static final class CarryState {

    private final CraftovGameId gameId;
    private List<StoredItem> claimedLoadout;
    private RaidCarryCapacity capacity;
    private final Map<Integer, UUID> expectedEquipment;
    private RaidCarrySnapshot snapshot;
    private boolean invalid;
    private boolean invalidLogged;

    private CarryState(CraftovGameId gameId, List<StoredItem> claimedLoadout,
        RaidCarryCapacity capacity, Map<Integer, UUID> expectedEquipment) {
      this.gameId = gameId;
      this.claimedLoadout = claimedLoadout;
      this.capacity = capacity;
      this.expectedEquipment = new LinkedHashMap<>(expectedEquipment);
    }
  }
}
