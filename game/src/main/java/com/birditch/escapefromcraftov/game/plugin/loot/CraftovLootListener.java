package com.birditch.escapefromcraftov.game.plugin.loot;

import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovLootPoint;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.item.ItemRarity;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
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
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
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
  private final ItemRegistry items;
  private final InstanceWorldIndex worlds;
  private final Messages messages;
  private final int inventorySize;
  private final TrMenuBridge menus;
  private final CraftovScheduler scheduler;
  private final AutoCloseable itemSource;
  private final Map<ContainerKey, LootContainerState> containers = new HashMap<>();
  private final Map<ContainerKey, CraftovLootPoint> resolvedPoints = new HashMap<>();
  private final Map<UUID, LootView> views = new HashMap<>();
  private final Map<UUID, SearchSession> searches = new HashMap<>();
  private final Map<PlayerContainerKey, LootRevealProgress> revealProgress = new HashMap<>();
  private final Map<UUID, RevealSession> reveals = new HashMap<>();

  public CraftovLootListener(CraftovGameService games, LootService loot, CraftovItemStacks itemStacks,
      ItemRegistry items, InstanceWorldIndex worlds, Messages messages, int inventorySize,
      TrMenuBridge menus, CraftovScheduler scheduler) {
    this.games = Preconditions.checkNotNull(games, "games");
    this.loot = Preconditions.checkNotNull(loot, "loot");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    this.items = Preconditions.checkNotNull(items, "items");
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
    Block targetBlock = targetBlock(event, player);
    Location target = targetBlock == null ? null : targetBlock.getLocation().add(0.5, 0.5, 0.5);
    if (target == null) return;
    CraftovLootPoint point = resolve(game, targetBlock, target).orElse(null);
    if (point == null) {
      Optional<LootContainerType> type = containerType(targetBlock.getType());
      if (type.isPresent() && LootZoneResolver.contains(game.lootZones(), targetBlock.getX(),
          targetBlock.getY(), targetBlock.getZ())) {
        event.setCancelled(true);
        player.sendActionBar(messages.raw("loot.inactive"));
      }
      return;
    }
    event.setCancelled(true);
    beginSearch(player, game, point);
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    cancelSearch(event.getPlayer(), false);
    stopView(event.getPlayer(), false, false);
    menus.close(event.getPlayer(), "escapefromcraftov_loot");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDamage(EntityDamageEvent event) {
    if (!(event.getEntity() instanceof Player player) || event.getFinalDamage() <= 0) return;
    if (searches.containsKey(player.getUniqueId())) cancelSearch(player, true);
    if (views.containsKey(player.getUniqueId())) stopView(player, true, true);
  }

  @EventHandler
  public void onInventoryClose(InventoryCloseEvent event) {
    if (!(event.getPlayer() instanceof Player player)
        || !views.containsKey(player.getUniqueId())) return;
    // TrMenu may close and reopen its packet window while refreshing a dynamic
    // page. Recheck on the next tick so only a real close ends the live task.
    scheduler.runLater(Duration.ZERO, () -> {
      if (views.containsKey(player.getUniqueId())
          && !menus.isViewing(player, "escapefromcraftov_loot")) {
        stopView(player, false, false);
      }
    });
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onMove(PlayerMoveEvent event) {
    Player player = event.getPlayer();
    LootView view = views.get(player.getUniqueId());
    if (view == null) return;
    CraftovGame game = games.find(view.key().gameId()).orElse(null);
    CraftovLootPoint point = game == null ? null : point(game, view.key());
    if (point == null || !validViewLocation(game, player, point, view.key(), event.getTo())) {
      stopView(player, true, true);
    }
  }

  public void take(Player player, int visibleSlot) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkArgument(visibleSlot >= 0 && visibleSlot < PAGE_SIZE,
        "visibleSlot must be from 0..44");
    ValidatedView validated = validateView(player);
    int containerSlot = validated.view().page() * PAGE_SIZE + visibleSlot;
    LootContainerState container = validated.container();
    LootRevealProgress progress = revealProgress.get(
        new PlayerContainerKey(player.getUniqueId(), validated.view().key()));
    if (progress == null || !progress.isRevealed(containerSlot)) {
      player.sendActionBar(messages.raw("loot.unidentified"));
      return;
    }
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
    refreshViewers(validated.view().key());
  }

  public void page(Player player, int direction) {
    Preconditions.checkArgument(direction == -1 || direction == 1,
        "direction must be -1 or 1");
    ValidatedView validated = validateView(player);
    int totalPages = Math.max(1, (inventorySize + PAGE_SIZE - 1) / PAGE_SIZE);
    int page = Math.max(0, Math.min(totalPages - 1, validated.view().page() + direction));
    views.put(player.getUniqueId(), validated.view().withPage(page));
    openOrStop(player);
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
    reveals.entrySet().removeIf(entry -> {
      if (!entry.getValue().key().gameId().equals(event.gameId())) return false;
      entry.getValue().task().cancel();
      return true;
    });
    containers.keySet().removeIf(key -> key.gameId().equals(event.gameId()));
    resolvedPoints.keySet().removeIf(key -> key.gameId().equals(event.gameId()));
    revealProgress.keySet().removeIf(key -> key.container().gameId().equals(event.gameId()));
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
    ContainerKey key = new ContainerKey(game.id(), point.id());
    resolvedPoints.putIfAbsent(key, point);
    PlayerContainerKey progressKey = new PlayerContainerKey(playerId, key);
    LootRevealProgress previous = revealProgress.get(progressKey);
    LootContainerState existing = containers.get(key);
    if (previous != null && existing != null) {
      cancelSearch(player, false);
      stopView(player, false, false);
      views.put(playerId, new LootView(key, 0, point.containerType(), point.tier()));
      if (!openOrStop(player)) return;
      startReveal(player, key, point, existing, previous);
      player.sendActionBar(messages.raw("loot.reveal-resumed"));
      notifyTeammatesSearching(game, player, point);
      return;
    }
    SearchSession current = searches.get(playerId);
    if (current != null) {
      if (current.key().equals(key)) player.sendActionBar(messages.raw("loot.already-searching"));
      return;
    }
    stopView(player, false, false);
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
    notifyTeammatesSearching(game, player, point);
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
    LootContainerState container = containers.computeIfAbsent(expected.key(),
        _ -> createContainer(game, expected.point(), player));
    PlayerContainerKey progressKey = new PlayerContainerKey(player.getUniqueId(), expected.key());
    LootRevealProgress progress = revealProgress.computeIfAbsent(progressKey,
        _ -> new LootRevealProgress(container.occupiedSlots()));
    views.put(player.getUniqueId(), new LootView(expected.key(), 0,
        expected.point().containerType(), expected.point().tier()));
    if (!openOrStop(player)) return;
    startReveal(player, expected.key(), expected.point(), container, progress);
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

  private void startReveal(Player player, ContainerKey key, CraftovLootPoint point,
      LootContainerState container, LootRevealProgress progress) {
    UUID playerId = player.getUniqueId();
    RevealSession old = reveals.remove(playerId);
    if (old != null) old.task().cancel();
    long now = System.nanoTime();
    int nextSlot = progress.nextPresentSlot(container::hasItem);
    long nextAt = nextSlot < 0 ? Long.MAX_VALUE
        : now + revealDelayNanos(point, container.item(nextSlot));
    RevealSession[] holder = new RevealSession[1];
    ScheduledTask task = scheduler.runTimer(Duration.ZERO, Duration.ofMillis(100),
        () -> tickReveal(playerId, holder[0]));
    RevealSession session = new RevealSession(key, point, container, progress, nextAt, task);
    holder[0] = session;
    reveals.put(playerId, session);
  }

  private void tickReveal(UUID playerId, RevealSession expected) {
    if (expected == null || reveals.get(playerId) != expected) return;
    Player player = org.bukkit.Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) {
      stopView(playerId);
      return;
    }
    LootView view = views.get(playerId);
    if (view == null || !view.key().equals(expected.key())
        || !menus.isViewing(player, "escapefromcraftov_loot")) {
      stopView(playerId);
      return;
    }
    CraftovGame game = games.find(expected.key().gameId()).orElse(null);
    if (!validViewLocation(game, player, expected.point(), expected.key())) {
      stopView(player, true, true);
      return;
    }
    int slot = expected.progress().nextPresentSlot(expected.container()::hasItem);
    if (slot < 0) {
      completeReveal(playerId, player, expected);
      return;
    }
    long now = System.nanoTime();
    if (now < expected.nextRevealAtNanos()) return;
    ItemStack item = expected.container().item(slot);
    if (item == null || item.isEmpty()) {
      expected.nextRevealAtNanos(now);
      return;
    }
    expected.progress().reveal(slot);
    ItemRarity rarity = rarity(item);
    if (!openOrStop(player)) {
      expected.progress().rollbackReveal(slot);
      return;
    }
    playRevealFeedback(player, item, rarity, expected.progress());
    int next = expected.progress().nextPresentSlot(expected.container()::hasItem);
    if (next < 0) {
      completeReveal(playerId, player, expected);
    } else {
      expected.nextRevealAtNanos(
          now + revealDelayNanos(expected.point(), expected.container().item(next)));
    }
  }

  private void completeReveal(UUID playerId, Player player, RevealSession expected) {
    if (!reveals.remove(playerId, expected)) return;
    expected.task().cancel();
    player.sendActionBar(messages.raw("loot.reveal-complete"));
  }

  private long revealDelayNanos(CraftovLootPoint point, ItemStack item) {
    return Duration.ofMillis(LootRevealPolicy.delayMillis(point.containerType(), point.tier(),
        rarity(item))).toNanos();
  }

  private void playRevealFeedback(Player player, ItemStack item, ItemRarity rarity,
      LootRevealProgress progress) {
    Component name = item.hasItemMeta() && item.getItemMeta().hasItemName()
        ? Objects.requireNonNull(item.getItemMeta().itemName())
        : Component.translatable(item.getType().translationKey());
    player.sendActionBar(messages.raw("loot.revealed",
        Placeholder.component("item", name),
        Placeholder.unparsed("current", Integer.toString(progress.completedCount())),
        Placeholder.unparsed("total", Integer.toString(progress.totalCount()))));
    Sound sound = rarity.ordinal() >= ItemRarity.EPIC.ordinal()
        ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
    float pitch = Math.min(1.8f, 0.85f + rarity.ordinal() * 0.12f);
    player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, 0.55f, pitch);
  }

  private ItemRarity rarity(ItemStack item) {
    if (item == null || item.isEmpty()) return ItemRarity.COMMON;
    return itemStacks.read(item).flatMap(tagged -> items.find(tagged.itemId()))
        .map(definition -> definition.rarity()).orElse(ItemRarity.COMMON);
  }

  private void stopView(Player player, boolean notify, boolean closeMenu) {
    UUID playerId = player.getUniqueId();
    RevealSession removed = reveals.remove(playerId);
    if (removed != null) removed.task().cancel();
    views.remove(playerId);
    if (closeMenu) menus.close(player, "escapefromcraftov_loot");
    if (notify) player.sendActionBar(messages.raw(
        removed == null ? "loot.view-closed" : "loot.reveal-cancelled"));
  }

  private void stopView(UUID playerId) {
    RevealSession removed = reveals.remove(playerId);
    if (removed != null) removed.task().cancel();
    views.remove(playerId);
  }

  private void notifyTeammatesSearching(CraftovGame game, Player player,
      CraftovLootPoint point) {
    CraftovPlayer searching = game.players().stream()
        .filter(candidate -> candidate.playerId().equals(player.getUniqueId())).findFirst()
        .orElse(null);
    if (searching == null || searching.teamId().isEmpty()) return;
    int teamId = searching.teamId().getAsInt();
    for (CraftovPlayer candidate : game.players()) {
      if (candidate.playerId().equals(player.getUniqueId()) || candidate.teamId().isEmpty()
          || candidate.teamId().getAsInt() != teamId || candidate.extracted()) continue;
      Player teammate = org.bukkit.Bukkit.getPlayer(candidate.playerId());
      if (teammate == null || !teammate.isOnline()) continue;
      teammate.sendActionBar(messages.raw("loot.teammate-searching",
          Placeholder.unparsed("player", player.getName()),
          Placeholder.unparsed("container", containerName(point.containerType()))));
    }
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

  private boolean openOrStop(Player player) {
    try {
      open(player);
      return true;
    } catch (RuntimeException failure) {
      stopView(player, false, true);
      player.sendMessage(messages.raw("loot.open-failed"));
      return false;
    }
  }

  private void open(Player player) {
    LootView view = views.get(player.getUniqueId());
    if (view == null || !menus.open(player, "escapefromcraftov_loot",
        Integer.toString(view.page() + 1),
        Integer.toString(Math.max(1, (inventorySize + PAGE_SIZE - 1) / PAGE_SIZE)),
        containerToken(view.containerType()), Integer.toString(view.tier()))) {
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
    int containerSlot = view.page() * PAGE_SIZE + visible;
    ItemStack item = container.item(containerSlot);
    if (item == null) return air();
    LootRevealProgress progress = revealProgress.get(
        new PlayerContainerKey(player.getUniqueId(), view.key()));
    return progress != null && progress.isRevealed(containerSlot) ? item : unknownItem();
  }

  private void refreshViewers(ContainerKey key) {
    for (Map.Entry<UUID, LootView> entry : List.copyOf(views.entrySet())) {
      if (!entry.getValue().key().equals(key)) continue;
      Player viewer = org.bukkit.Bukkit.getPlayer(entry.getKey());
      if (viewer == null || !viewer.isOnline()
          || !menus.isViewing(viewer, "escapefromcraftov_loot")) continue;
      openOrStop(viewer);
    }
  }

  private ValidatedView validateView(Player player) {
    LootView view = views.get(player.getUniqueId());
    if (view == null) throw new IllegalStateException("Loot 搜索会话已结束");
    if (!menus.isViewing(player, "escapefromcraftov_loot")) {
      stopView(player, false, false);
      throw new IllegalStateException("请先打开物资搜索界面");
    }
    CraftovGame game = games.find(view.key().gameId()).orElse(null);
    CraftovLootPoint point = game == null ? null : point(game, view.key());
    if (point == null || !validViewLocation(game, player, point, view.key())) {
      views.remove(player.getUniqueId());
      RevealSession reveal = reveals.remove(player.getUniqueId());
      if (reveal != null) reveal.task().cancel();
      menus.close(player, "escapefromcraftov_loot");
      throw new IllegalStateException("你已离开物资容器或当前行动");
    }
    LootContainerState container = containers.get(view.key());
    if (container == null) throw new IllegalStateException("Loot 容器已关闭");
    return new ValidatedView(view, container);
  }

  private boolean validViewLocation(CraftovGame game, Player player, CraftovLootPoint point,
      ContainerKey key) {
    return validViewLocation(game, player, point, key, player.getLocation());
  }

  private boolean validViewLocation(CraftovGame game, Player player, CraftovLootPoint point,
      ContainerKey key, Location location) {
    if (location == null || !activeMember(game, player)
        || location.getWorld() == null
        || worlds.gameByWorld(location.getWorld().getName()).filter(key.gameId()::equals).isEmpty()) {
      return false;
    }
    return point.distanceSquared(location.getX(), location.getY(), location.getZ())
        <= INTERACTION_DISTANCE_SQUARED;
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

  private static Optional<CraftovLootPoint> resolve(CraftovGame game, Block block,
      Location target) {
    Optional<CraftovLootPoint> authored = nearest(game, target);
    if (authored.isPresent()) return authored;
    Optional<LootContainerType> type = containerType(block.getType());
    if (type.isEmpty()) return Optional.empty();
    return LootZoneResolver.resolve(game.id(), game.lootZones(), block.getX(), block.getY(),
        block.getZ(), type.get());
  }

  private CraftovLootPoint point(CraftovGame game, ContainerKey key) {
    return game.lootPoints().stream()
        .filter(value -> value.id().equals(key.lootPointId())).findFirst()
        .orElseGet(() -> resolvedPoints.get(key));
  }

  private static Optional<LootContainerType> containerType(Material material) {
    if (material.name().endsWith("_SHULKER_BOX")) {
      return Optional.of(LootContainerType.HIDDEN_CACHE);
    }
    return Optional.ofNullable(switch (material) {
      case BARREL -> LootContainerType.BARREL;
      case CHEST, TRAPPED_CHEST -> LootContainerType.CHEST;
      case ENDER_CHEST -> LootContainerType.VAULT;
      case DISPENSER, DROPPER -> LootContainerType.DUNGEON_CHEST;
      case HOPPER -> LootContainerType.HIDDEN_CACHE;
      default -> null;
    });
  }

  private static Block targetBlock(PlayerInteractEvent event, Player player) {
    Block clicked = event.getClickedBlock();
    return clicked != null ? clicked : player.getTargetBlockExact(5);
  }

  private static ItemStack air() {
    return new ItemStack(Material.AIR);
  }

  private static ItemStack unknownItem() {
    ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
    item.editMeta(meta -> {
      meta.itemName(Component.text("未识别物资", NamedTextColor.DARK_GRAY)
          .decoration(TextDecoration.ITALIC, false));
      meta.lore(List.of(
          Component.text("正在辨识内容……", NamedTextColor.GRAY)
              .decoration(TextDecoration.ITALIC, false),
          Component.text("揭示前无法取出", NamedTextColor.RED)
              .decoration(TextDecoration.ITALIC, false)));
    });
    return item;
  }

  private static String containerToken(LootContainerType type) {
    return switch (type) {
      case CHEST -> "SUPPLY";
      case BARREL -> "BARREL";
      case DUNGEON_CHEST -> "WEAPON";
      case POTION_CACHE -> "MEDICAL";
      case HIDDEN_CACHE -> "HIDDEN";
      case VAULT -> "VAULT";
      case SUPPLY_DROP -> "AIRDROP";
    };
  }

  @Override
  public void close() throws Exception {
    for (Map.Entry<UUID, SearchSession> entry : List.copyOf(searches.entrySet())) {
      Player player = org.bukkit.Bukkit.getPlayer(entry.getKey());
      entry.getValue().task().cancel();
      if (player != null) player.hideBossBar(entry.getValue().bar());
    }
    for (RevealSession reveal : List.copyOf(reveals.values())) reveal.task().cancel();
    for (Player player : List.copyOf(org.bukkit.Bukkit.getOnlinePlayers())) {
      menus.close(player, "escapefromcraftov_loot");
    }
    itemSource.close();
    views.clear();
    searches.clear();
    reveals.clear();
    revealProgress.clear();
    containers.clear();
    resolvedPoints.clear();
  }

  private record ContainerKey(CraftovGameId gameId, String lootPointId) {
    private ContainerKey {
      Objects.requireNonNull(gameId);
      Objects.requireNonNull(lootPointId);
    }
  }

  private record PlayerContainerKey(UUID playerId, ContainerKey container) {
    private PlayerContainerKey {
      Objects.requireNonNull(playerId);
      Objects.requireNonNull(container);
    }
  }

  private record LootView(ContainerKey key, int page, LootContainerType containerType, int tier) {
    private LootView withPage(int newPage) {
      return new LootView(key, newPage, containerType, tier);
    }
  }
  private record SearchSession(ContainerKey key, CraftovLootPoint point, String worldName,
      long startedAtNanos, long durationNanos, BossBar bar, ScheduledTask task) {}
  private record ValidatedView(LootView view, LootContainerState container) {}

  private static final class RevealSession {
    private final ContainerKey key;
    private final CraftovLootPoint point;
    private final LootContainerState container;
    private final LootRevealProgress progress;
    private final ScheduledTask task;
    private long nextRevealAtNanos;

    private RevealSession(ContainerKey key, CraftovLootPoint point, LootContainerState container,
        LootRevealProgress progress, long nextRevealAtNanos, ScheduledTask task) {
      this.key = key;
      this.point = point;
      this.container = container;
      this.progress = progress;
      this.nextRevealAtNanos = nextRevealAtNanos;
      this.task = task;
    }

    private ContainerKey key() { return key; }
    private CraftovLootPoint point() { return point; }
    private LootContainerState container() { return container; }
    private LootRevealProgress progress() { return progress; }
    private ScheduledTask task() { return task; }
    private long nextRevealAtNanos() { return nextRevealAtNanos; }
    private void nextRevealAtNanos(long value) { nextRevealAtNanos = value; }
  }

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

    private synchronized boolean hasItem(int slot) {
      if (slot < 0 || slot >= contents.size()) return false;
      ItemStack item = contents.get(slot);
      return item != null && !item.isEmpty();
    }

    private synchronized List<Integer> occupiedSlots() {
      List<Integer> slots = new ArrayList<>();
      for (int slot = 0; slot < contents.size(); slot++) {
        ItemStack item = contents.get(slot);
        if (item != null && !item.isEmpty()) slots.add(slot);
      }
      return List.copyOf(slots);
    }

    private void remove(int slot) {
      contents.set(slot, null);
    }

    private void restore(int slot, ItemStack item) {
      contents.set(slot, item.clone());
    }
  }
}
