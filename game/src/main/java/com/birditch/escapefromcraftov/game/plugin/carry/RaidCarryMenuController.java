package com.birditch.escapefromcraftov.game.plugin.carry;

import com.birditch.escapefromcraftov.api.event.EventBus;
import com.birditch.escapefromcraftov.api.event.EventSubscription;
import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.combat.PlayerDownedEvent;
import com.birditch.escapefromcraftov.api.event.extraction.PlayerExtractionLockedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.event.game.PlayerLeftCraftovEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.inventory.GridItemPlacement;
import com.birditch.escapefromcraftov.api.inventory.ItemGrid;
import com.birditch.escapefromcraftov.api.inventory.ItemGridService;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.bukkit.TrMenuBridge;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.RaidInventoryMutationGate;
import com.birditch.escapefromcraftov.game.plugin.carry.RaidCarryController.MenuMutation;
import com.birditch.escapefromcraftov.game.plugin.carry.RaidCarryController.MenuMutationKind;
import com.birditch.escapefromcraftov.game.plugin.carry.RaidCarryController.MenuMutationResult;
import com.birditch.escapefromcraftov.game.plugin.carry.RaidCarryController.MenuMutationStatus;
import com.birditch.escapefromcraftov.game.plugin.carry.RaidCarryController.MenuView;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/** TrMenu projection and validated command adapter for the authoritative raid carry grid. */
public final class RaidCarryMenuController implements Listener, AutoCloseable {

  public static final String MENU_ID = "escapefromcraftov_carry";
  private static final String ITEM_SOURCE = "CRAFTOVCORECARRY";

  private final CraftovGameService games;
  private final InstanceWorldIndex worlds;
  private final RaidCarryController carry;
  private final RaidInventoryMutationGate mutationGate;
  private final ItemGridService grids;
  private final ItemRegistry items;
  private final CraftovItemStacks itemStacks;
  private final TrMenuBridge menus;
  private final CraftovScheduler scheduler;
  private final Logger logger;
  private final AutoCloseable itemSource;
  private final AutoCloseable carryLifecycle;
  private final EventSubscription domainSubscription;
  private final Map<UUID, MenuSession> sessions = new HashMap<>();

  public RaidCarryMenuController(EventBus events, CraftovGameService games,
      InstanceWorldIndex worlds, RaidCarryController carry,
      RaidInventoryMutationGate mutationGate, ItemGridService grids,
      ItemRegistry items, CraftovItemStacks itemStacks, TrMenuBridge menus,
      CraftovScheduler scheduler, Logger logger) {
    this.games = Preconditions.checkNotNull(games, "games");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
    this.carry = Preconditions.checkNotNull(carry, "carry");
    this.mutationGate = Preconditions.checkNotNull(mutationGate, "mutationGate");
    this.grids = Preconditions.checkNotNull(grids, "grids");
    this.items = Preconditions.checkNotNull(items, "items");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    this.menus = Preconditions.checkNotNull(menus, "menus");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.logger = Preconditions.checkNotNull(logger, "logger");
    itemSource = menus.registerItemSource(ITEM_SOURCE, this::menuItem);
    carryLifecycle = carry.onDeactivated(this::closeForLifecycle);
    domainSubscription = Preconditions.checkNotNull(events, "events").register(this);
  }

  public void open(Player player) {
    Preconditions.checkNotNull(player, "player");
    MenuView view = currentView(player);
    if (view == null) {
      player.sendMessage(Component.text("只能在进行中的行动里整理携行物资", NamedTextColor.RED));
      return;
    }
    MenuSession session = new MenuSession(UUID.randomUUID().toString(), view);
    sessions.put(player.getUniqueId(), session);
    if (!openMenu(player, session)) {
      sessions.remove(player.getUniqueId());
      player.sendMessage(Component.text("携行整理菜单打开失败", NamedTextColor.RED));
    }
  }

  /** Hidden TrMenu endpoint; {@code displayIndex} never identifies an item or source region. */
  public void left(Player player, String nonce, int displayIndex) {
    ValidatedAction action = validateAction(player, nonce, displayIndex);
    if (action == null) return;
    var cell = action.session().projection.cell(displayIndex);
    if (cell.kind() != RaidCarryMenuProjection.Kind.REGION) return;
    ItemGrid grid = action.view().layout().find(cell.gridId()).orElseThrow();
    GridItemPlacement placement = grids.placementAt(grid, cell.position()).orElse(null);
    if (placement != null) {
      // Covered cells intentionally normalize to their one authoritative anchor identity.
      Selection current = action.session().selection;
      if (current != null && current.version() == action.view().version()
          && current.instanceId().equals(placement.item().instanceId())
          && !placement.anchor().equals(cell.position())) {
        MenuMutation mutation = new MenuMutation(action.view().gameId(), action.view().epoch(),
            action.view().version(), current.instanceId(), MenuMutationKind.MOVE,
            cell.gridId(), cell.position());
        handleMutation(player, action.session(), carry.mutateFromMenu(player, mutation), false);
        return;
      }
      action.session().selection = current != null
              && current.instanceId().equals(placement.item().instanceId())
          ? null : new Selection(placement.item().instanceId(), action.view().version());
      refreshMenuBestEffort(player, action.session());
      return;
    }

    Selection selection = action.session().selection;
    if (selection == null) {
      player.sendActionBar(Component.text("先左键选择一个物品", NamedTextColor.YELLOW));
      return;
    }
    if (selection.version() != action.view().version()) {
      action.session().selection = null;
      player.sendActionBar(Component.text("布局已变化，请重新选择", NamedTextColor.RED));
      refreshMenuBestEffort(player, action.session());
      return;
    }
    MenuMutation mutation = new MenuMutation(action.view().gameId(), action.view().epoch(),
        action.view().version(), selection.instanceId(), MenuMutationKind.MOVE,
        cell.gridId(), cell.position());
    handleMutation(player, action.session(), carry.mutateFromMenu(player, mutation), false);
  }

  /** Hidden TrMenu endpoint; rotation is still re-authorized from the live item definition. */
  public void right(Player player, String nonce, int displayIndex) {
    ValidatedAction action = validateAction(player, nonce, displayIndex);
    if (action == null) return;
    var cell = action.session().projection.cell(displayIndex);
    if (cell.kind() != RaidCarryMenuProjection.Kind.REGION) return;
    ItemGrid grid = action.view().layout().find(cell.gridId()).orElseThrow();
    GridItemPlacement placement = grids.placementAt(grid, cell.position()).orElse(null);
    if (placement == null) return;
    ItemDefinition definition = items.require(placement.item().itemId());
    if (!definition.footprint().rotatable()) {
      player.sendActionBar(Component.text("这个物品不允许旋转", NamedTextColor.RED));
      return;
    }
    MenuMutation mutation = new MenuMutation(action.view().gameId(), action.view().epoch(),
        action.view().version(), placement.item().instanceId(), MenuMutationKind.ROTATE,
        cell.gridId(), placement.anchor());
    handleMutation(player, action.session(), carry.mutateFromMenu(player, mutation), true);
  }

  private void handleMutation(Player player, MenuSession session,
      MenuMutationResult result, boolean clearRejectedSelection) {
    if (result.view() != null) session.update(result.view());
    if (result.status() == MenuMutationStatus.COMMITTED) {
      session.selection = null;
      refreshMenuBestEffort(player, session);
      return;
    }
    if (result.status() == MenuMutationStatus.STALE) {
      session.selection = null;
      player.sendActionBar(Component.text("布局刚刚发生变化，请重试", NamedTextColor.RED));
      refreshMenuBestEffort(player, session);
      return;
    }
    if (result.status() == MenuMutationStatus.INACTIVE || result.view() == null) {
      closeBlocked(player.getUniqueId());
      player.sendActionBar(Component.text("当前不能整理携行物资", NamedTextColor.RED));
      return;
    }
    if (clearRejectedSelection) session.selection = null;
    player.sendActionBar(Component.text("目标位置放不下该物品", NamedTextColor.RED));
    refreshMenuBestEffort(player, session);
  }

  private ValidatedAction validateAction(Player player, String nonce, int displayIndex) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(nonce, "nonce");
    if (displayIndex < 0 || displayIndex >= RaidCarryMenuProjection.SUMMARY_SLOT) return null;
    MenuSession session = sessions.get(player.getUniqueId());
    if (session == null || !session.nonce.equals(nonce) || !viewing(player)) return null;
    MenuView live = currentView(player);
    if (live == null || !live.gameId().equals(session.view.gameId())
        || live.epoch() != session.view.epoch()) {
      closeBlocked(player.getUniqueId());
      return null;
    }
    if (live.version() != session.view.version()) {
      session.update(live);
      session.selection = null;
      player.sendActionBar(Component.text("布局已更新，请重新操作", NamedTextColor.YELLOW));
      refreshMenuBestEffort(player, session);
      return null;
    }
    return new ValidatedAction(session, live);
  }

  private MenuView currentView(Player player) {
    if (!mutationGate.allows(player.getUniqueId())) return null;
    CraftovGame game = worlds.gameByWorld(player.getWorld().getName())
        .flatMap(games::find).orElse(null);
    if (game == null || game.state() != CraftovGameState.RUNNING
        || game.players().stream().noneMatch(member ->
            member.playerId().equals(player.getUniqueId()) && !member.extracted())) return null;
    try {
      return carry.menuView(player).filter(view -> view.gameId().equals(game.id())).orElse(null);
    } catch (RuntimeException invalid) {
      logger.log(Level.WARNING, "无法建立携行整理视图: " + player.getUniqueId(), invalid);
      return null;
    }
  }

  private boolean openMenu(Player player, MenuSession session) {
    // TrMenu arguments are strict safe tokens. Exact formatted metrics stay in the summary icon.
    return menus.open(player, MENU_ID, session.nonce);
  }

  private void refreshMenuBestEffort(Player player, MenuSession session) {
    try {
      if (!openMenu(player, session)) {
        logger.warning("TrMenu 未能刷新携行整理菜单: " + player.getUniqueId());
      }
    } catch (RuntimeException failure) {
      logger.log(Level.WARNING, "刷新携行整理菜单失败，但权威布局未受影响", failure);
    }
  }

  /** Dynamic source is a pure clone of the cached server view and never changes selection/state. */
  private ItemStack menuItem(Player player, String rawId) {
    MenuSession session = sessions.get(player.getUniqueId());
    if (session == null) return decoration();
    String id = rawId.toLowerCase(Locale.ROOT);
    if (id.equals("summary")) return summary(session.view);
    if (!id.startsWith("cell-")) return decoration();
    int displayIndex;
    try {
      displayIndex = Integer.parseInt(id.substring(5));
    } catch (NumberFormatException ignored) {
      return decoration();
    }
    if (displayIndex < 0 || displayIndex >= RaidCarryMenuProjection.SUMMARY_SLOT) {
      return decoration();
    }
    var cell = session.projection.cell(displayIndex);
    return switch (cell.kind()) {
      case REGION -> regionItem(session, cell);
      case LOCKED -> named(Material.BARRIER, "未解锁槽位", NamedTextColor.DARK_GRAY,
          List.of(Component.text("装备更大的胸挂或背包可解锁", NamedTextColor.GRAY)));
      case SUMMARY -> summary(session.view);
      case CLOSE, DECORATION -> decoration();
    };
  }

  private ItemStack regionItem(MenuSession session,
      RaidCarryMenuProjection.DisplayCell cell) {
    ItemGrid grid = session.view.layout().find(cell.gridId()).orElseThrow();
    GridItemPlacement placement = grids.placementAt(grid, cell.position()).orElse(null);
    String region = regionLabel(cell.gridId());
    NamedTextColor color = regionColor(cell.gridId());
    if (placement == null) {
      return named(regionMaterial(cell.gridId()), region + " · 空格", color,
          List.of(Component.text("左键把已选择物品移动到这里", NamedTextColor.GRAY),
              coordinate(cell)));
    }
    boolean selected = session.selection != null
        && session.selection.instanceId().equals(placement.item().instanceId());
    if (!placement.anchor().equals(cell.position())) {
      List<Component> lore = new ArrayList<>();
      lore.add(Component.text(region + " · 被物品覆盖", color));
      lore.add(Component.text("锚点 " + placement.anchor().column() + ","
          + placement.anchor().row(), NamedTextColor.DARK_GRAY));
      lore.add(Component.text("左键可选择对应锚点", NamedTextColor.GRAY));
      if (selected) lore.add(Component.text("已选择", NamedTextColor.GOLD));
      return named(regionMaterial(cell.gridId()), "占用格", color, lore);
    }

    ItemStack display = itemStacks.fromInstance(placement.item()).getFirst().clone();
    display.editMeta(meta -> {
      List<Component> lore = meta.lore() == null
          ? new ArrayList<>() : new ArrayList<>(meta.lore());
      lore.add(Component.empty());
      lore.add(Component.text(region + " · 锚点 " + cell.position().column() + ","
          + cell.position().row(), color).decoration(TextDecoration.ITALIC, false));
      lore.add(Component.text("占用 " + placement.footprint().width(placement.rotation())
          + "×" + placement.footprint().height(placement.rotation()), NamedTextColor.GRAY)
          .decoration(TextDecoration.ITALIC, false));
      lore.add(Component.text("左键选择/取消", NamedTextColor.YELLOW)
          .decoration(TextDecoration.ITALIC, false));
      if (items.require(placement.item().itemId()).footprint().rotatable()) {
        lore.add(Component.text("右键旋转", NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
      }
      if (selected) {
        lore.add(Component.text("已选择：再点一个空格移动", NamedTextColor.GOLD)
            .decoration(TextDecoration.ITALIC, false));
      }
      meta.lore(lore);
    });
    return display;
  }

  private ItemStack summary(MenuView view) {
    return named(Material.COMPASS, "携行状态", NamedTextColor.GOLD, List.of(
        Component.text("重量 " + weightLabel(view), NamedTextColor.WHITE),
        Component.text("容量 " + capacityLabel(view), NamedTextColor.WHITE),
        Component.text("移动速度 " + Math.round(view.snapshot().movementMultiplier() * 100)
            + "%", NamedTextColor.GRAY),
        Component.empty(),
        Component.text("左键选物品，再点目标格移动", NamedTextColor.YELLOW),
        Component.text("右键可旋转允许旋转的物品", NamedTextColor.AQUA)));
  }

  private static String weightLabel(MenuView view) {
    return String.format(Locale.ROOT, "%.1f/%.1f kg",
        view.snapshot().totalWeightGrams() / 1000.0,
        view.capacity().maximumWeightGrams() / 1000.0);
  }

  private static String capacityLabel(MenuView view) {
    return view.snapshot().usedCargoSlots() + "/" + view.capacity().cargoSlots() + " 格";
  }

  private static Component coordinate(RaidCarryMenuProjection.DisplayCell cell) {
    return Component.text("坐标 " + cell.position().column() + ","
        + cell.position().row(),
        NamedTextColor.DARK_GRAY);
  }

  private static String regionLabel(String gridId) {
    return switch (gridId) {
      case RaidCarryGridPlanner.POCKETS -> "口袋";
      case RaidCarryGridPlanner.CHEST_RIG -> "胸挂";
      case RaidCarryGridPlanner.BACKPACK -> "背包";
      case RaidCarryGridPlanner.LEGACY_CONTAINER -> "携行容器";
      default -> "携行区域";
    };
  }

  private static NamedTextColor regionColor(String gridId) {
    return switch (gridId) {
      case RaidCarryGridPlanner.POCKETS -> NamedTextColor.GREEN;
      case RaidCarryGridPlanner.CHEST_RIG -> NamedTextColor.GOLD;
      case RaidCarryGridPlanner.BACKPACK -> NamedTextColor.AQUA;
      default -> NamedTextColor.LIGHT_PURPLE;
    };
  }

  private static Material regionMaterial(String gridId) {
    return switch (gridId) {
      case RaidCarryGridPlanner.POCKETS -> Material.LIME_STAINED_GLASS_PANE;
      case RaidCarryGridPlanner.CHEST_RIG -> Material.ORANGE_STAINED_GLASS_PANE;
      case RaidCarryGridPlanner.BACKPACK -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
      default -> Material.PURPLE_STAINED_GLASS_PANE;
    };
  }

  private static ItemStack decoration() {
    return named(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.BLACK, List.of());
  }

  private static ItemStack named(Material material, String name, NamedTextColor color,
      List<Component> lore) {
    ItemStack stack = new ItemStack(material);
    stack.editMeta(meta -> {
      meta.itemName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
      meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
    });
    return stack;
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInventoryClick(InventoryClickEvent event) {
    if (event.getWhoClicked() instanceof Player player && viewing(player)) {
      // Covers shift-click, number keys, cursor collection and every direct click variant.
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInventoryDrag(InventoryDragEvent event) {
    if (event.getWhoClicked() instanceof Player player && viewing(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler
  public void onInventoryClose(InventoryCloseEvent event) {
    if (!(event.getPlayer() instanceof Player player)) return;
    MenuSession session = sessions.get(player.getUniqueId());
    if (session == null) return;
    String nonce = session.nonce;
    // TrMenu may briefly close its packet inventory during reset/open. Decide next tick.
    scheduler.runLater(Duration.ZERO, () -> {
      MenuSession current = sessions.get(player.getUniqueId());
      if (current != null && current.nonce.equals(nonce) && !viewing(player)) {
        sessions.remove(player.getUniqueId());
      }
    });
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    closeBlocked(event.getPlayer().getUniqueId());
  }

  @EventHandler
  public void onDeath(PlayerDeathEvent event) {
    closeBlocked(event.getEntity().getUniqueId());
  }

  @Subscribe
  public void onDowned(PlayerDownedEvent event) {
    closeBlocked(event.playerId());
  }

  @Subscribe
  public void onExtractionLocked(PlayerExtractionLockedEvent event) {
    closeBlocked(event.playerId());
  }

  @Subscribe
  public void onPlayerLeft(PlayerLeftCraftovEvent event) {
    closeBlocked(event.playerId());
  }

  @Subscribe
  public void onCraftovEnded(CraftovEndedEvent event) {
    List<UUID> affected = sessions.entrySet().stream()
        .filter(entry -> entry.getValue().view.gameId().equals(event.gameId()))
        .map(Map.Entry::getKey).toList();
    affected.forEach(this::closeBlocked);
  }

  private void closeForLifecycle(Player player) {
    closeBlocked(player.getUniqueId());
  }

  private void closeBlocked(UUID playerId) {
    sessions.remove(playerId);
    Player player = Bukkit.getPlayer(playerId);
    if (player == null) return;
    try {
      if (!menus.close(player, MENU_ID)) {
        logger.warning("TrMenu 未确认关闭携行整理菜单: " + playerId);
      }
    } catch (RuntimeException failure) {
      logger.log(Level.WARNING, "关闭携行整理菜单失败: " + playerId, failure);
    }
  }

  private boolean viewing(Player player) {
    try {
      return menus.isViewing(player, MENU_ID);
    } catch (RuntimeException failure) {
      logger.log(Level.WARNING, "读取 TrMenu 携行会话失败", failure);
      return false;
    }
  }

  @Override
  public void close() {
    for (UUID playerId : List.copyOf(sessions.keySet())) closeBlocked(playerId);
    domainSubscription.unsubscribe();
    closeRegistration(carryLifecycle, "注销携行生命周期监听失败");
    closeRegistration(itemSource, "注销 TrMenu 携行动态源失败");
  }

  private void closeRegistration(AutoCloseable registration, String message) {
    try {
      registration.close();
    } catch (Exception failure) {
      logger.log(Level.WARNING, message, failure);
    }
  }

  private static final class MenuSession {
    private final String nonce;
    private MenuView view;
    private RaidCarryMenuProjection.Projection projection;
    private Selection selection;

    private MenuSession(String nonce, MenuView view) {
      this.nonce = nonce;
      update(view);
    }

    private void update(MenuView updated) {
      view = Preconditions.checkNotNull(updated, "updated");
      projection = RaidCarryMenuProjection.project(view.regions(),
          view.lockedPhysicalSlots().size());
    }
  }

  private record Selection(UUID instanceId, long version) {}

  private record ValidatedAction(MenuSession session, MenuView view) {}
}
