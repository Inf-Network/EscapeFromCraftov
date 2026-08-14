package com.birditch.escapefromcraftov.game.plugin.loot;

import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovLootPoint;
import com.birditch.escapefromcraftov.api.loot.LootRollContext;
import com.birditch.escapefromcraftov.api.loot.LootService;
import com.birditch.escapefromcraftov.api.loot.LootContainerType;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.bukkit.TrMenuBridge;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Shared, one-roll-per-craftov Loot state with a TrMenu-owned search interface.
 * Items are removed only by an atomic, revalidated take action; the virtual
 * menu never exposes movable Bukkit inventory slots.
 */
public final class CraftovLootListener implements Listener, AutoCloseable {

  private static final double INTERACTION_DISTANCE_SQUARED = 3.25 * 3.25;
  private static final int PAGE_SIZE = 45;

  private final CraftovGameService games;
  private final LootService loot;
  private final CraftovItemStacks itemStacks;
  private final InstanceWorldIndex worlds;
  private final Messages messages;
  private final int inventorySize;
  private final TrMenuBridge menus;
  private final CraftovScheduler scheduler;
  private final AutoCloseable itemSource;
  private final Map<ContainerKey, LootContainerState> containers = new HashMap<>();
  private final Map<UUID, LootView> views = new HashMap<>();
  private final Map<UUID, SearchSession> searches = new HashMap<>();

  public CraftovLootListener(CraftovGameService games, LootService loot, CraftovItemStacks itemStacks,
      InstanceWorldIndex worlds, Messages messages, int inventorySize, TrMenuBridge menus,
      CraftovScheduler scheduler) {
    this.games = Preconditions.checkNotNull(games, "games");
    this.loot = Preconditions.checkNotNull(loot, "loot");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
    this.messages = Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkArgument(inventorySize >= 9 && inventorySize <= 54
        && inventorySize % 9 == 0, "inventorySize must be a multiple of 9 from 9..54");
    this.inventorySize = inventorySize;
    this.menus = Preconditions.checkNotNull(menus, "menus");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.itemSource = menus.registerItemSource("CRAFTOVCORELOOT", this::menuItem);
  }

  @EventHandler(ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK
        && event.getAction() != Action.RIGHT_CLICK_AIR) return;
    Player player = event.getPlayer();
    Optional<CraftovGameId> foundGame = worlds.gameByWorld(player.getWorld().getName());
    if (foundGame.isEmpty()) return;
    CraftovGame game = games.find(foundGame.get()).orElse(null);
    if (!activeMember(game, player)) {
      player.sendMessage(messages.raw("loot.not-running"));
      return;
    }
    Location target = target(event, player);
    if (target == null) return;
    CraftovLootPoint point = nearest(game, target).orElse(null);
    if (point == null) return;
    event.setCancelled(true);
    beginSearch(player, game, point);
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    cancelSearch(event.getPlayer(), false);
    menus.close(event.getPlayer(), "escapefromcraftov_loot");
    views.remove(event.getPlayer().getUniqueId());
  }

  @EventHandler(ignoreCancelled = true)
  public void onDamage(EntityDamageEvent event) {
    if (event.getEntity() instanceof Player player && searches.containsKey(player.getUniqueId())) {
      cancelSearch(player, true);
    }
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
  public void onCraftovEnded(CraftovEndedEvent event) {
    searches.entrySet().removeIf(entry -> {
      if (!entry.getValue().key().gameId().equals(event.gameId())) return false;
      Player player = org.bukkit.Bukkit.getPlayer(entry.getKey());
      entry.getValue().task().cancel();
      if (player != null) player.hideBossBar(entry.getValue().bar());
      return true;
    });
    containers.keySet().removeIf(key -> key.gameId().equals(event.gameId()));
    views.entrySet().removeIf(entry -> {
      if (!entry.getValue().key().gameId().equals(event.gameId())) return false;
      Player player = org.bukkit.Bukkit.getPlayer(entry.getKey());
      if (player != null) menus.close(player, "escapefromcraftov_loot");
      return true;
    });
  }

  public int activeContainerCount() {
    return containers.size();
  }

  private void beginSearch(Player player, CraftovGame game, CraftovLootPoint point) {
    UUID playerId = player.getUniqueId();
    SearchSession current = searches.get(playerId);
    ContainerKey key = new ContainerKey(game.id(), point.id());
    if (current != null) {
      if (current.key().equals(key)) player.sendActionBar(messages.raw("loot.already-searching"));
      return;
    }
    double seconds = searchSeconds(point);
    BossBar bar = BossBar.bossBar(
        Component.text("搜索 " + containerName(point.containerType()) + " · T" + point.tier(),
            NamedTextColor.GOLD), 0, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
    player.showBossBar(bar);
    long startedAt = System.nanoTime();
    long durationNanos = (long) (seconds * 1_000_000_000L);
    SearchSession[] holder = new SearchSession[1];
    ScheduledTask task = scheduler.runTimer(Duration.ZERO, Duration.ofMillis(100),
        () -> tickSearch(playerId, holder[0]));
    SearchSession session = new SearchSession(key, point, player.getWorld().getName(),
        startedAt, durationNanos, bar, task);
    holder[0] = session;
    searches.put(playerId, session);
    player.sendMessage(messages.raw("loot.search-started"));
  }

  private void tickSearch(UUID playerId, SearchSession expected) {
    if (expected == null || searches.get(playerId) != expected) return;
    Player player = org.bukkit.Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()
        || !player.getWorld().getName().equals(expected.worldName())) {
      cancelSearch(playerId, false);
      return;
    }
    CraftovGame game = games.find(expected.key().gameId()).orElse(null);
    if (!activeMember(game, player)
        || expected.point().distanceSquared(player.getX(), player.getY(), player.getZ())
            > INTERACTION_DISTANCE_SQUARED) {
      cancelSearch(player, true);
      return;
    }
    long elapsed = Math.max(0, System.nanoTime() - expected.startedAtNanos());
    float progress = (float) Math.min(1, elapsed / (double) expected.durationNanos());
    expected.bar().progress(progress);
    int filled = Math.min(10, Math.max(0, (int) Math.floor(progress * 10)));
    player.sendActionBar(Component.text("搜索中 ", NamedTextColor.YELLOW)
        .append(Component.text("■".repeat(filled), NamedTextColor.GOLD))
        .append(Component.text("□".repeat(10 - filled), NamedTextColor.DARK_GRAY)));
    if (progress < 1) return;
    finishSearch(player, game, expected);
  }

  private void finishSearch(Player player, CraftovGame game, SearchSession expected) {
    if (!searches.remove(player.getUniqueId(), expected)) return;
    expected.task().cancel();
    player.hideBossBar(expected.bar());
    containers.computeIfAbsent(expected.key(), _ -> createContainer(game, expected.point(), player));
    views.put(player.getUniqueId(), new LootView(expected.key(), 0));
    open(player);
    player.sendMessage(messages.raw("loot.opened"));
  }

  private void cancelSearch(Player player, boolean notify) {
    if (player == null) return;
    SearchSession removed = searches.remove(player.getUniqueId());
    if (removed == null) return;
    removed.task().cancel();
    player.hideBossBar(removed.bar());
    if (notify) player.sendActionBar(messages.raw("loot.search-cancelled"));
  }

  private void cancelSearch(UUID playerId, boolean notify) {
    Player player = org.bukkit.Bukkit.getPlayer(playerId);
    if (player != null) {
      cancelSearch(player, notify);
      return;
    }
    SearchSession removed = searches.remove(playerId);
    if (removed != null) removed.task().cancel();
  }

  private static double searchSeconds(CraftovLootPoint point) {
    double base = switch (point.containerType()) {
      case BARREL -> 1.2;
      case CHEST, DUNGEON_CHEST -> 1.6;
      case POTION_CACHE, HIDDEN_CACHE -> 2.0;
      case SUPPLY_DROP -> 2.8;
      case VAULT -> 4.0;
    };
    return base + (point.tier() - 1) * 0.55;
  }

  private static String containerName(LootContainerType type) {
    return switch (type) {
      case CHEST -> "物资箱";
      case BARREL -> "补给桶";
      case DUNGEON_CHEST -> "武器箱";
      case POTION_CACHE -> "医疗箱";
      case HIDDEN_CACHE -> "隐秘储藏";
      case VAULT -> "保险柜";
      case SUPPLY_DROP -> "空投箱";
    };
  }

  private void open(Player player) {
    LootView view = views.get(player.getUniqueId());
    if (view == null || !menus.open(player, "escapefromcraftov_loot",
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
    if (!menus.isViewing(player, "escapefromcraftov_loot")) {
      views.remove(player.getUniqueId());
      throw new IllegalStateException("请先打开物资搜索界面");
    }
    CraftovGame game = games.find(view.key().gameId()).orElse(null);
    if (!activeMember(game, player)
        || !worlds.gameByWorld(player.getWorld().getName()).filter(view.key().gameId()::equals)
            .isPresent()) {
      views.remove(player.getUniqueId());
      menus.close(player, "escapefromcraftov_loot");
      throw new IllegalStateException("你已经不在这场行动中");
    }
    CraftovLootPoint point = game.lootPoints().stream()
        .filter(value -> value.id().equals(view.key().lootPointId())).findFirst()
        .orElseThrow(() -> new IllegalStateException("Loot 点已不存在"));
    Location location = player.getLocation();
    if (point.distanceSquared(location.getX(), location.getY(), location.getZ())
        > INTERACTION_DISTANCE_SQUARED) {
      views.remove(player.getUniqueId());
      menus.close(player, "escapefromcraftov_loot");
      throw new IllegalStateException("你离物资点太远了");
    }
    LootContainerState container = containers.get(view.key());
    if (container == null) throw new IllegalStateException("Loot 容器已关闭");
    return new ValidatedView(view, container);
  }

  private LootContainerState createContainer(CraftovGame game, CraftovLootPoint point, Player player) {
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

  private static boolean activeMember(CraftovGame game, Player player) {
    return game != null && game.state() == CraftovGameState.RUNNING
        && game.players().stream().anyMatch(value ->
            value.playerId().equals(player.getUniqueId()) && !value.extracted());
  }

  private static Optional<CraftovLootPoint> nearest(CraftovGame game, Location target) {
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
    for (Map.Entry<UUID, SearchSession> entry : List.copyOf(searches.entrySet())) {
      Player player = org.bukkit.Bukkit.getPlayer(entry.getKey());
      entry.getValue().task().cancel();
      if (player != null) player.hideBossBar(entry.getValue().bar());
    }
    for (Player player : List.copyOf(org.bukkit.Bukkit.getOnlinePlayers())) {
      menus.close(player, "escapefromcraftov_loot");
    }
    itemSource.close();
    views.clear();
    searches.clear();
    containers.clear();
  }

  private record ContainerKey(CraftovGameId gameId, String lootPointId) {
    private ContainerKey {
      Objects.requireNonNull(gameId);
      Objects.requireNonNull(lootPointId);
    }
  }

  private record LootView(ContainerKey key, int page) {}
  private record SearchSession(ContainerKey key, CraftovLootPoint point, String worldName,
      long startedAtNanos, long durationNanos, BossBar bar, ScheduledTask task) {}
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
