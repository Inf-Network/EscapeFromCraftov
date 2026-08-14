package com.birditch.escapefromcraftov.game.plugin.secure;

import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.combat.PlayerDownedEvent;
import com.birditch.escapefromcraftov.api.event.extraction.PlayerExtractionLockedEvent;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.bukkit.TrMenuBridge;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.RaidInventoryMutationGate;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import com.birditch.escapefromcraftov.game.plugin.carry.RaidCarryController;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Three-slot, per-player/per-craftov secure container backed by a TrMenu virtual menu. */
public final class SecureContainerController implements AutoCloseable {

  public static final String MENU_ID = "escapefromcraftov_secure";
  private static final Logger LOGGER = Logger.getLogger(SecureContainerController.class.getName());

  private final CraftovGameService games;
  private final InstanceWorldIndex worlds;
  private final CraftovItemStacks itemStacks;
  private final ItemRegistry items;
  private final RaidCarryController carry;
  private final RaidInventoryMutationGate mutationGate;
  private final Messages messages;
  private final TrMenuBridge menus;
  private final AutoCloseable itemSource;
  private final Map<SecureKey, SecureContainerState> states = new HashMap<>();

  public SecureContainerController(CraftovGameService games, InstanceWorldIndex worlds,
      CraftovItemStacks itemStacks, ItemRegistry items, RaidCarryController carry,
      Messages messages, TrMenuBridge menus) {
    this(games, worlds, itemStacks, items, carry, messages, menus,
        RaidInventoryMutationGate.legacyAllowAll());
  }

  public SecureContainerController(CraftovGameService games, InstanceWorldIndex worlds,
      CraftovItemStacks itemStacks, ItemRegistry items, RaidCarryController carry,
      Messages messages, TrMenuBridge menus, RaidInventoryMutationGate mutationGate) {
    this.games = Preconditions.checkNotNull(games, "games");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    this.items = Preconditions.checkNotNull(items, "items");
    this.carry = Preconditions.checkNotNull(carry, "carry");
    this.mutationGate = Preconditions.checkNotNull(mutationGate, "mutationGate");
    this.messages = Preconditions.checkNotNull(messages, "messages");
    this.menus = Preconditions.checkNotNull(menus, "menus");
    this.itemSource = menus.registerItemSource("CRAFTOVCORESECURE", this::menuItem);
  }

  public void open(Player player) {
    Preconditions.checkNotNull(player, "player");
    if (!mutationGate.allows(player.getUniqueId())) {
      menus.close(player, MENU_ID);
      player.sendMessage(messages.raw("secure.not-in-craftov"));
      return;
    }
    SecureKey key = activeKey(player).orElse(null);
    if (key == null) {
      player.sendMessage(messages.raw("secure.not-in-craftov"));
      return;
    }
    SecureContainerState state = states.computeIfAbsent(key, _ -> new SecureContainerState());
    if (state.sealed()) {
      player.sendMessage(messages.raw("secure.settling"));
      return;
    }
    if (!menus.open(player, MENU_ID)) {
      player.sendMessage(messages.raw("secure.open-failed"));
    }
  }

  /** Stores the complete main-hand stack; partial virtual/physical identities are never made. */
  public void storeHeld(Player player) {
    ValidatedView view = validateView(player);
    if (view == null) return;
    ItemStack held = player.getInventory().getItemInMainHand();
    if (held.isEmpty()) {
      player.sendMessage(messages.raw("secure.empty-hand"));
      return;
    }
    if (!itemStacks.isCraftovItem(held)) {
      player.sendMessage(messages.raw("secure.craftov-items-only"));
      return;
    }
    // Vanilla copies PDC when a stack is split. Keeping the same instance ID
    // on the half that crosses into the virtual secure container would make a
    // later loss/extraction settlement see two physical stacks as one item.
    // The half left in the physical inventory keeps the durable identity; the
    // protected half receives a new identity before it leaves Bukkit storage.
    var tagged = itemStacks.read(held).orElseThrow();
    if (!items.require(tagged.itemId()).secureAllowed()) {
      player.sendMessage(messages.raw("secure.not-allowed"));
      return;
    }
    boolean activeCarry = carry.active(player.getUniqueId());
    boolean cargoAnchor = carry.cargoAnchor(player, tagged.instanceId());
    if (hasOtherPhysicalHalf(player, tagged.instanceId())) {
      if (activeCarry) {
        player.sendMessage(messages.raw("secure.invalid-stack"));
        return;
      }
      itemStacks.assignFreshIdentity(held).orElseThrow();
      player.getInventory().setItemInMainHand(held);
    }
    List<ItemInstance> snapshot = itemStacks.scan(new ItemStack[] {held.clone()});
    if (snapshot.size() != 1) {
      player.sendMessage(messages.raw("secure.invalid-stack"));
      return;
    }
    ItemInstance storedItem = snapshot.getFirst();
    if (cargoAnchor || activeCarry) {
      int[] storedSlot = {-1};
      java.util.function.BooleanSupplier destination = () -> {
        if (!mutationGate.allows(player.getUniqueId())) return false;
        storedSlot[0] = view.state().store(storedItem);
        return storedSlot[0] >= 0;
      };
      RaidCarryController.CargoRemoval removal = cargoAnchor
          ? carry.transferOutOfCargo(player, storedItem.instanceId(), destination)
          : carry.transferOutOfEquipment(player, storedItem.instanceId(), destination);
      if (removal != RaidCarryController.CargoRemoval.COMMITTED) {
        player.sendMessage(messages.raw("secure.full"));
        return;
      }
      player.sendActionBar(messages.raw("secure.stored"));
      refresh(player);
      return;
    }

    int slot = -1;
    boolean targetStored = false;
    try {
      if (!mutationGate.allows(player.getUniqueId())) return;
      slot = view.state().store(storedItem);
      if (slot < 0) {
        player.sendMessage(messages.raw("secure.full"));
        return;
      }
      targetStored = true;
      player.getInventory().setItemInMainHand(null);
    } catch (RuntimeException sourceFailure) {
      rollbackManualStore(player, view.state(), slot, storedItem, held, targetStored,
          sourceFailure);
      player.sendMessage(messages.raw("secure.invalid-stack"));
      return;
    }
    player.sendActionBar(messages.raw("secure.stored"));
    refresh(player);
  }

  private void rollbackManualStore(Player player, SecureContainerState state, int slot,
      ItemInstance storedItem, ItemStack original, boolean targetStored, RuntimeException failure) {
    boolean targetRemoved = !targetStored;
    if (targetStored) {
      try {
        targetRemoved = state.takeIfSame(slot, storedItem);
      } catch (RuntimeException rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
      }
    }
    try {
      if (targetRemoved) {
        if (player.getInventory().getItemInMainHand().isEmpty()) {
          player.getInventory().setItemInMainHand(original);
        }
      } else if (sameIdentity(player.getInventory().getItemInMainHand(), storedItem.instanceId())) {
        // The protected target is now authoritative; remove any uncertain source copy.
        player.getInventory().setItemInMainHand(null);
      }
    } catch (RuntimeException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
    logBestEffort(Level.SEVERE, "安全箱存入失败；已按单一身份策略回滚", failure);
  }

  /** Returns one complete stack through the authoritative carry-grid transaction. */
  public void take(Player player, int slot) {
    ValidatedView view = validateView(player);
    if (view == null) return;
    ItemInstance item = view.state().item(slot).orElse(null);
    if (item == null) return;
    List<ItemStack> restored = itemStacks.fromInstance(item);
    if (restored.size() != 1) {
      player.sendMessage(messages.raw("secure.invalid-stack"));
      return;
    }
    if (!carry.transferIntoCargo(player, restored.getFirst(),
        () -> mutationGate.allows(player.getUniqueId())
            && view.state().takeIfSame(slot, item))) {
      player.sendMessage(messages.raw("secure.inventory-full"));
      return;
    }
    player.sendActionBar(messages.raw("secure.taken"));
    refresh(player);
  }

  /** Seals and returns the retry-stable protected snapshot for a final settlement. */
  public List<ItemInstance> beginSettlement(CraftovGameId gameId, UUID playerId) {
    SecureKey key = new SecureKey(gameId, playerId);
    List<ItemInstance> snapshot = states.computeIfAbsent(
        key, _ -> new SecureContainerState()).beginSettlement();
    Player online = Bukkit.getPlayer(playerId);
    if (online != null) closeSettlementMenuBestEffort(online, "封存安全箱");
    return snapshot;
  }

  /** Current protected items for live HUD/weight calculations without sealing mutation. */
  public List<ItemInstance> contents(CraftovGameId gameId, UUID playerId) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(playerId, "playerId");
    SecureContainerState state = states.get(new SecureKey(gameId, playerId));
    return state == null ? List.of() : state.contents();
  }

  /** Current protected items for the player's single active raid, if any. */
  public List<ItemInstance> contents(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return states.entrySet().stream()
        .filter(entry -> entry.getKey().playerId().equals(playerId))
        .flatMap(entry -> entry.getValue().contents().stream())
        .toList();
  }

  /** Reopens mutation only when extraction authorization failed and the raid continues. */
  public void cancelSettlement(CraftovGameId gameId, UUID playerId) {
    SecureContainerState state = states.get(new SecureKey(gameId, playerId));
    if (state != null) state.cancelSettlement();
  }

  /** Removes all transient state only after PostgreSQL has a durable outcome. */
  public void completeSettlement(CraftovGameId gameId, UUID playerId) {
    SecureKey key = new SecureKey(gameId, playerId);
    states.remove(key);
    Player online = Bukkit.getPlayer(playerId);
    if (online != null) closeSettlementMenuBestEffort(online, "完成安全箱结算");
  }

  private void closeSettlementMenuBestEffort(Player player, String action) {
    try {
      if (menus.isViewing(player, MENU_ID)) menus.close(player, MENU_ID);
    } catch (RuntimeException failure) {
      logBestEffort(Level.WARNING, action + "后关闭菜单失败，但权威状态已提交", failure);
    }
  }

  private static void logBestEffort(Level level, String message, Throwable failure) {
    try {
      LOGGER.log(level, message, failure);
    } catch (RuntimeException ignored) {
      // Logging and menu presentation are outside secure-container transactions.
    }
  }

  @Subscribe
  public void onPlayerDowned(PlayerDownedEvent event) {
    closeBlockedPlayer(event.playerId());
  }

  @Subscribe
  public void onExtractionLocked(PlayerExtractionLockedEvent event) {
    closeBlockedPlayer(event.playerId());
  }

  private void closeBlockedPlayer(UUID playerId) {
    Player player = Bukkit.getPlayer(playerId);
    if (player != null && menus.isViewing(player, MENU_ID)) menus.close(player, MENU_ID);
  }

  private ItemStack menuItem(Player player, String rawId) {
    if (!mutationGate.allows(player.getUniqueId())) return emptySlot();
    SecureKey key = activeKey(player).orElse(null);
    if (key == null) return emptySlot();
    SecureContainerState state = states.get(key);
    if (state == null) return emptySlot();
    String id = rawId.toLowerCase(Locale.ROOT);
    if (!id.startsWith("slot-")) return emptySlot();
    int slot;
    try {
      slot = Integer.parseInt(id.substring(5));
    } catch (NumberFormatException ignored) {
      return emptySlot();
    }
    if (slot < 0 || slot >= SecureContainerState.CAPACITY) return emptySlot();
    ItemInstance item = state.item(slot).orElse(null);
    if (item == null) return emptySlot();
    ItemStack display = itemStacks.fromInstance(item).getFirst();
    display.editMeta(meta -> {
      List<Component> lore = meta.lore() == null
          ? new java.util.ArrayList<>() : new java.util.ArrayList<>(meta.lore());
      lore.add(Component.empty());
      lore.add(Component.text("点击取回本局背包", NamedTextColor.GREEN)
          .decoration(TextDecoration.ITALIC, false));
      meta.lore(lore);
    });
    return display;
  }

  private ValidatedView validateView(Player player) {
    Preconditions.checkNotNull(player, "player");
    if (!mutationGate.allows(player.getUniqueId())) {
      menus.close(player, MENU_ID);
      player.sendMessage(messages.raw("secure.not-in-craftov"));
      return null;
    }
    if (!menus.isViewing(player, MENU_ID)) {
      player.sendMessage(messages.raw("secure.open-first"));
      return null;
    }
    SecureKey key = activeKey(player).orElse(null);
    SecureContainerState state = key == null ? null : states.get(key);
    if (state == null) {
      menus.close(player, MENU_ID);
      player.sendMessage(messages.raw("secure.not-in-craftov"));
      return null;
    }
    if (state.sealed()) {
      menus.close(player, MENU_ID);
      player.sendMessage(messages.raw("secure.settling"));
      return null;
    }
    return new ValidatedView(key, state);
  }

  private Optional<SecureKey> activeKey(Player player) {
    return worlds.gameByWorld(player.getWorld().getName()).flatMap(gameId ->
        games.find(gameId).filter(game -> playable(game, player.getUniqueId()))
            .map(_ -> new SecureKey(gameId, player.getUniqueId())));
  }

  private static boolean playable(CraftovGame game, UUID playerId) {
    CraftovGameState state = game.state();
    boolean open = state == CraftovGameState.WAITING || state == CraftovGameState.PREPARING
        || state == CraftovGameState.STARTING || state == CraftovGameState.RUNNING;
    return open && game.players().stream().anyMatch(player ->
        player.playerId().equals(playerId) && !player.extracted());
  }

  private void refresh(Player player) {
    if (!mutationGate.allows(player.getUniqueId())) {
      menus.close(player, MENU_ID);
      return;
    }
    if (!menus.open(player, MENU_ID)) player.sendMessage(messages.raw("secure.open-failed"));
  }

  private boolean hasOtherPhysicalHalf(Player player, UUID instanceId) {
    ItemStack[] storage = player.getInventory().getStorageContents();
    int heldSlot = player.getInventory().getHeldItemSlot();
    for (int slot = 0; slot < storage.length; slot++) {
      if (slot == heldSlot) continue;
      if (sameIdentity(storage[slot], instanceId)) return true;
    }
    if (sameIdentity(player.getInventory().getItemInOffHand(), instanceId)) return true;
    for (ItemStack armor : player.getInventory().getArmorContents()) {
      if (sameIdentity(armor, instanceId)) return true;
    }
    return false;
  }

  private boolean sameIdentity(ItemStack stack, UUID instanceId) {
    return itemStacks.read(stack).map(tagged -> tagged.instanceId().equals(instanceId))
        .orElse(false);
  }

  private static ItemStack emptySlot() {
    ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
    item.editMeta(meta -> {
      meta.itemName(Component.text("空安全槽", NamedTextColor.DARK_GRAY)
          .decoration(TextDecoration.ITALIC, false));
      meta.lore(List.of(Component.text("把物品拿在主手后点击右侧存入按钮",
          NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
    });
    return item;
  }

  @Override
  public void close() throws Exception {
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (menus.isViewing(player, MENU_ID)) menus.close(player, MENU_ID);
    }
    states.clear();
    itemSource.close();
  }

  private record SecureKey(CraftovGameId gameId, UUID playerId) {
    private SecureKey {
      Preconditions.checkNotNull(gameId, "gameId");
      Preconditions.checkNotNull(playerId, "playerId");
    }
  }

  private record ValidatedView(SecureKey key, SecureContainerState state) {}
}
