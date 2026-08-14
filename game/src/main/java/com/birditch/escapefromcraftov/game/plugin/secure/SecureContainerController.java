package com.birditch.escapefromcraftov.game.plugin.secure;

import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.bukkit.TrMenuBridge;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

  private final CraftovGameService games;
  private final InstanceWorldIndex worlds;
  private final CraftovItemStacks itemStacks;
  private final Messages messages;
  private final TrMenuBridge menus;
  private final AutoCloseable itemSource;
  private final Map<SecureKey, SecureContainerState> states = new HashMap<>();

  public SecureContainerController(CraftovGameService games, InstanceWorldIndex worlds,
      CraftovItemStacks itemStacks, Messages messages, TrMenuBridge menus) {
    this.games = Preconditions.checkNotNull(games, "games");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    this.messages = Preconditions.checkNotNull(messages, "messages");
    this.menus = Preconditions.checkNotNull(menus, "menus");
    this.itemSource = menus.registerItemSource("CRAFTOVCORESECURE", this::menuItem);
  }

  public void open(Player player) {
    Preconditions.checkNotNull(player, "player");
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
    if (hasOtherPhysicalHalf(player, tagged.instanceId())) {
      itemStacks.assignFreshIdentity(held).orElseThrow();
      player.getInventory().setItemInMainHand(held);
    }
    List<ItemInstance> snapshot = itemStacks.scan(new ItemStack[] {held.clone()});
    if (snapshot.size() != 1) {
      player.sendMessage(messages.raw("secure.invalid-stack"));
      return;
    }
    int slot = view.state().store(snapshot.getFirst());
    if (slot < 0) {
      player.sendMessage(messages.raw("secure.full"));
      return;
    }
    player.getInventory().setItemInMainHand(null);
    player.sendActionBar(messages.raw("secure.stored"));
    refresh(player);
  }

  /** Returns one complete stack into an empty physical storage slot. */
  public void take(Player player, int slot) {
    ValidatedView view = validateView(player);
    if (view == null) return;
    ItemInstance item = view.state().item(slot).orElse(null);
    if (item == null) return;
    int inventorySlot = firstEmptyStorageSlot(player);
    if (inventorySlot < 0) {
      player.sendMessage(messages.raw("secure.inventory-full"));
      return;
    }
    List<ItemStack> restored = itemStacks.fromInstance(item);
    if (restored.size() != 1) {
      player.sendMessage(messages.raw("secure.invalid-stack"));
      return;
    }
    player.getInventory().setItem(inventorySlot, restored.getFirst());
    view.state().take(slot);
    player.sendActionBar(messages.raw("secure.taken"));
    refresh(player);
  }

  /** Seals and returns the retry-stable protected snapshot for a final settlement. */
  public List<ItemInstance> beginSettlement(CraftovGameId gameId, UUID playerId) {
    SecureKey key = new SecureKey(gameId, playerId);
    Player online = Bukkit.getPlayer(playerId);
    if (online != null && menus.isViewing(online, MENU_ID)) menus.close(online, MENU_ID);
    return states.computeIfAbsent(key, _ -> new SecureContainerState()).beginSettlement();
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
    if (online != null && menus.isViewing(online, MENU_ID)) menus.close(online, MENU_ID);
  }

  private ItemStack menuItem(Player player, String rawId) {
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
    if (!menus.open(player, MENU_ID)) player.sendMessage(messages.raw("secure.open-failed"));
  }

  private static int firstEmptyStorageSlot(Player player) {
    ItemStack[] contents = player.getInventory().getStorageContents();
    for (int slot = 0; slot < contents.length; slot++) {
      if (contents[slot] == null || contents[slot].isEmpty()) return slot;
    }
    return -1;
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
