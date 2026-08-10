package com.birditch.raidcore.game.plugin.loot;

import com.birditch.raidcore.api.event.Subscribe;
import com.birditch.raidcore.api.event.game.RaidEndedEvent;
import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.game.RaidLootPoint;
import com.birditch.raidcore.api.loot.LootRollContext;
import com.birditch.raidcore.api.loot.LootService;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.bukkit.RaidItemStacks;
import com.birditch.raidcore.bukkit.TrMenuBridge;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.game.plugin.instance.InstanceWorldIndex;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Shared, one-roll-per-raid Loot state with a TrMenu-owned search interface.
 * Items are removed only by an atomic, revalidated take action; the virtual
 * menu never exposes movable Bukkit inventory slots.
 */
public final class RaidLootListener implements Listener, AutoCloseable {

  private static final double INTERACTION_DISTANCE_SQUARED = 3.25 * 3.25;
  private static final int PAGE_SIZE = 45;

  private final RaidGameService games;
  private final LootService loot;
  private final RaidItemStacks itemStacks;
  private final InstanceWorldIndex worlds;
  private final Messages messages;
  private final int inventorySize;
  private final TrMenuBridge menus;
  private final AutoCloseable itemSource;
  private final Map<ContainerKey, LootContainerState> containers = new HashMap<>();
  private final Map<UUID, LootView> views = new HashMap<>();

  public RaidLootListener(RaidGameService games, LootService loot, RaidItemStacks itemStacks,
      InstanceWorldIndex worlds, Messages messages, int inventorySize, TrMenuBridge menus) {
    this.games = Preconditions.checkNotNull(games, "games");
    this.loot = Preconditions.checkNotNull(loot, "loot");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
    this.messages = Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkArgument(inventorySize >= 9 && inventorySize <= 54
        && inventorySize % 9 == 0, "inventorySize must be a multiple of 9 from 9..54");
    this.inventorySize = inventorySize;
    this.menus = Preconditions.checkNotNull(menus, "menus");
    this.itemSource = menus.registerItemSource("RAIDCORELOOT", this::menuItem);
  }

  @EventHandler(ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK
        && event.getAction() != Action.RIGHT_CLICK_AIR) return;
    Player player = event.getPlayer();
    Optional<RaidGameId> foundGame = worlds.gameByWorld(player.getWorld().getName());
    if (foundGame.isEmpty()) return;
    RaidGame game = games.find(foundGame.get()).orElse(null);
    if (!activeMember(game, player)) {
      player.sendMessage(messages.raw("loot.not-running"));
      return;
    }
    Location target = target(event, player);
    if (target == null) return;
    RaidLootPoint point = nearest(game, target).orElse(null);
    if (point == null) return;
    event.setCancelled(true);
    ContainerKey key = new ContainerKey(game.id(), point.id());
    containers.computeIfAbsent(key, _ -> createContainer(game, point, player));
    views.put(player.getUniqueId(), new LootView(key, 0));
    open(player);
    player.sendMessage(messages.raw("loot.opened"));
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    menus.close(event.getPlayer(), "raidcore_loot");
    views.remove(event.getPlayer().getUniqueId());
  }

  public void take(Player player, int visibleSlot) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkArgument(visibleSlot >= 0 && visibleSlot < PAGE_SIZE,
        "visibleSlot must be from 0..44");
    ValidatedView validated = validateView(player);
    int containerSlot = validated.view().page() * PAGE_SIZE + visibleSlot;
    LootContainerState container = validated.container();
    ItemStack taken;
    synchronized (container) {
      taken = container.item(containerSlot);
      if (taken == null || taken.isEmpty()) return;
      if (!fits(player, taken)) {
        player.sendMessage(messages.raw("loot.inventory-full"));
        return;
      }
      container.remove(containerSlot);
      Map<Integer, ItemStack> leftovers = player.getInventory().addItem(taken.clone());
      if (!leftovers.isEmpty()) {
        container.restore(containerSlot, taken);
        throw new IllegalStateException("背包容量检查与实际写入不一致");
      }
    }
    player.sendActionBar(messages.raw("loot.taken"));
    open(player);
  }

  public void page(Player player, int direction) {
    Preconditions.checkArgument(direction == -1 || direction == 1,
        "direction must be -1 or 1");
    ValidatedView validated = validateView(player);
    int totalPages = Math.max(1, (inventorySize + PAGE_SIZE - 1) / PAGE_SIZE);
    int page = Math.max(0, Math.min(totalPages - 1, validated.view().page() + direction));
    views.put(player.getUniqueId(), new LootView(validated.view().key(), page));
    open(player);
  }

  @Subscribe
  public void onRaidEnded(RaidEndedEvent event) {
    containers.keySet().removeIf(key -> key.gameId().equals(event.gameId()));
    views.entrySet().removeIf(entry -> {
      if (!entry.getValue().key().gameId().equals(event.gameId())) return false;
      Player player = org.bukkit.Bukkit.getPlayer(entry.getKey());
      if (player != null) menus.close(player, "raidcore_loot");
      return true;
    });
  }

  public int activeContainerCount() {
    return containers.size();
  }

  private void open(Player player) {
    LootView view = views.get(player.getUniqueId());
    if (view == null || !menus.open(player, "raidcore_loot",
        Integer.toString(view.page() + 1),
        Integer.toString(Math.max(1, (inventorySize + PAGE_SIZE - 1) / PAGE_SIZE)))) {
      throw new IllegalStateException("TrMenu 无法打开 Loot 搜索菜单");
    }
  }

  private ItemStack menuItem(Player player, String id) {
    LootView view = views.get(player.getUniqueId());
    if (view == null) return air();
    LootContainerState container = containers.get(view.key());
    if (container == null) return air();
    String normalized = id.toLowerCase(Locale.ROOT);
    if (!normalized.startsWith("slot-")) return air();
    int visible;
    try {
      visible = Integer.parseInt(normalized.substring(5));
    } catch (NumberFormatException error) {
      return air();
    }
    if (visible < 0 || visible >= PAGE_SIZE) return air();
    ItemStack item = container.item(view.page() * PAGE_SIZE + visible);
    return item == null ? air() : item;
  }

  private ValidatedView validateView(Player player) {
    LootView view = views.get(player.getUniqueId());
    if (view == null) throw new IllegalStateException("Loot 搜索会话已结束");
    if (!menus.isViewing(player, "raidcore_loot")) {
      views.remove(player.getUniqueId());
      throw new IllegalStateException("请先打开物资搜索界面");
    }
    RaidGame game = games.find(view.key().gameId()).orElse(null);
    if (!activeMember(game, player)
        || !worlds.gameByWorld(player.getWorld().getName()).filter(view.key().gameId()::equals)
            .isPresent()) {
      views.remove(player.getUniqueId());
      menus.close(player, "raidcore_loot");
      throw new IllegalStateException("你已经不在这场行动中");
    }
    RaidLootPoint point = game.lootPoints().stream()
        .filter(value -> value.id().equals(view.key().lootPointId())).findFirst()
        .orElseThrow(() -> new IllegalStateException("Loot 点已不存在"));
    Location location = player.getLocation();
    if (point.distanceSquared(location.getX(), location.getY(), location.getZ())
        > INTERACTION_DISTANCE_SQUARED) {
      views.remove(player.getUniqueId());
      menus.close(player, "raidcore_loot");
      throw new IllegalStateException("你离物资点太远了");
    }
    LootContainerState container = containers.get(view.key());
    if (container == null) throw new IllegalStateException("Loot 容器已关闭");
    return new ValidatedView(view, container);
  }

  private LootContainerState createContainer(RaidGame game, RaidLootPoint point, Player player) {
    List<ItemStack> contents = new ArrayList<>(java.util.Collections.nCopies(inventorySize, null));
    loot.roll(point.lootTableId(), new LootRollContext(player.getUniqueId(), game.id()))
        .stream().flatMap(item -> itemStacks.fromInstance(item).stream())
        .forEach(item -> add(contents, item));
    return new LootContainerState(contents);
  }

  private static void add(List<ItemStack> contents, ItemStack source) {
    ItemStack remaining = source.clone();
    for (int index = 0; index < contents.size() && remaining.getAmount() > 0; index++) {
      ItemStack current = contents.get(index);
      if (current == null || current.isEmpty() || !current.isSimilar(remaining)) continue;
      int moved = Math.min(remaining.getAmount(), current.getMaxStackSize() - current.getAmount());
      if (moved <= 0) continue;
      current.setAmount(current.getAmount() + moved);
      remaining.setAmount(remaining.getAmount() - moved);
    }
    for (int index = 0; index < contents.size() && remaining.getAmount() > 0; index++) {
      ItemStack current = contents.get(index);
      if (current != null && !current.isEmpty()) continue;
      int moved = Math.min(remaining.getAmount(), remaining.getMaxStackSize());
      ItemStack placed = remaining.clone();
      placed.setAmount(moved);
      contents.set(index, placed);
      remaining.setAmount(remaining.getAmount() - moved);
    }
  }

  private static boolean fits(Player player, ItemStack item) {
    int remaining = item.getAmount();
    for (ItemStack current : player.getInventory().getStorageContents()) {
      if (current == null || current.isEmpty()) {
        remaining -= item.getMaxStackSize();
      } else if (current.isSimilar(item)) {
        remaining -= Math.max(0, current.getMaxStackSize() - current.getAmount());
      }
      if (remaining <= 0) return true;
    }
    return false;
  }

  private static boolean activeMember(RaidGame game, Player player) {
    return game != null && game.state() == RaidGameState.RUNNING
        && game.players().stream().anyMatch(value ->
            value.playerId().equals(player.getUniqueId()) && !value.extracted());
  }

  private static Optional<RaidLootPoint> nearest(RaidGame game, Location target) {
    return game.lootPoints().stream()
        .filter(point -> point.distanceSquared(target.getX(), target.getY(), target.getZ())
            <= INTERACTION_DISTANCE_SQUARED)
        .min(java.util.Comparator.comparingDouble(point ->
            point.distanceSquared(target.getX(), target.getY(), target.getZ())));
  }

  private static Location target(PlayerInteractEvent event, Player player) {
    Block clicked = event.getClickedBlock();
    if (clicked != null) return clicked.getLocation().add(0.5, 0.5, 0.5);
    Block lookedAt = player.getTargetBlockExact(5);
    return lookedAt == null ? null : lookedAt.getLocation().add(0.5, 0.5, 0.5);
  }

  private static ItemStack air() {
    return new ItemStack(Material.AIR);
  }

  @Override
  public void close() throws Exception {
    for (Player player : List.copyOf(org.bukkit.Bukkit.getOnlinePlayers())) {
      menus.close(player, "raidcore_loot");
    }
    itemSource.close();
    views.clear();
    containers.clear();
  }

  private record ContainerKey(RaidGameId gameId, String lootPointId) {
    private ContainerKey {
      Objects.requireNonNull(gameId);
      Objects.requireNonNull(lootPointId);
    }
  }

  private record LootView(ContainerKey key, int page) {}
  private record ValidatedView(LootView view, LootContainerState container) {}

  private static final class LootContainerState {
    private final List<ItemStack> contents;

    private LootContainerState(List<ItemStack> contents) {
      this.contents = contents;
    }

    private synchronized ItemStack item(int slot) {
      if (slot < 0 || slot >= contents.size()) return null;
      ItemStack item = contents.get(slot);
      return item == null ? null : item.clone();
    }

    private void remove(int slot) {
      contents.set(slot, null);
    }

    private void restore(int slot, ItemStack item) {
      contents.set(slot, item.clone());
    }
  }
}
