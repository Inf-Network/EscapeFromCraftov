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
import com.birditch.escapefromcraftov.api.inventory.GridPlacementFailure;
import com.birditch.escapefromcraftov.api.inventory.GridPosition;
import com.birditch.escapefromcraftov.api.inventory.GridRotation;
import com.birditch.escapefromcraftov.api.inventory.ItemGrid;
import com.birditch.escapefromcraftov.api.inventory.ItemGridService;
import com.birditch.escapefromcraftov.api.item.ItemCategory;
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
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

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
  private final Map<UUID, PendingFocusedOpen> pendingFocusedOpens = new HashMap<>();

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
    open(player, view, null);
  }

  private void openFocused(Player player, int physicalSlot, String focusGridId) {
    MenuView view = currentView(player);
    if (view == null || !equipmentRegionAvailable(player, view, physicalSlot, focusGridId)) {
      return;
    }
    open(player, view, focusGridId);
  }

  private void open(Player player, MenuView view, @Nullable String focusGridId) {
    pendingFocusedOpens.remove(player.getUniqueId());
    MenuSession session = new MenuSession(UUID.randomUUID().toString(), view, focusGridId);
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
        PlacementPreview preview = placementPreview(action.session(), cell.gridId(),
            cell.position(), sourcePlacement(action.session(), current));
        if (preview != null && !preview.accepted()) {
          rejectPreview(player, preview, cell.gridId());
          refreshMenuBestEffort(player, action.session());
          return;
        }
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
    SelectedPlacement source = sourcePlacement(action.session(), selection);
    PlacementPreview preview = placementPreview(action.session(), cell.gridId(), cell.position(),
        source);
    if (preview != null && !preview.accepted()) {
      rejectPreview(player, preview, cell.gridId());
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
    GridRotation targetRotation = placement.rotation() == GridRotation.DEFAULT
        ? GridRotation.CLOCKWISE_90 : GridRotation.DEFAULT;
    var previewResult = grids.transfer(action.view().layout(), cell.gridId(), cell.gridId(),
        placement.item().instanceId(), placement.anchor(), targetRotation);
    if (!previewResult.accepted()) {
      int width = placement.footprint().width(targetRotation);
      int height = placement.footprint().height(targetRotation);
      rejectPreview(player, new PlacementPreview(false,
          previewResult.rejection().orElse(GridPlacementFailure.INVALID_GRID), width, height),
          cell.gridId());
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
    player.sendActionBar(Component.text("放置被拒绝：布局或物品状态已变化，请重试",
        NamedTextColor.RED));
    refreshMenuBestEffort(player, session);
  }

  private ValidatedAction validateAction(Player player, String nonce, int displayIndex) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(nonce, "nonce");
    if (displayIndex < 0 || displayIndex >= RaidCarryMenuProjection.SECURE_SLOT) return null;
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
      if (menus.refresh(player, MENU_ID, session.nonce)) return;
      logger.warning("TrMenu 未能刷新携行整理菜单: " + player.getUniqueId());
    } catch (RuntimeException failure) {
      logger.log(Level.WARNING, "刷新携行整理菜单失败，但权威布局未受影响", failure);
    }
    closeBlocked(player.getUniqueId());
    player.sendActionBar(Component.text("携行界面刷新失败，请重新打开", NamedTextColor.RED));
  }

  /** Dynamic source is a pure clone of the cached server view and never changes selection/state. */
  private ItemStack menuItem(Player player, String rawId) {
    MenuSession session = sessions.get(player.getUniqueId());
    if (session == null) return decoration();
    String id = rawId.toLowerCase(Locale.ROOT);
    if (id.equals("summary")) return summary(session);
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
      case HEADER -> headerItem(session, cell.gridId());
      case BORDER -> borderItem(session, cell.gridId());
      case LOCKED -> named(Material.BARRIER, "未解锁槽位", NamedTextColor.DARK_GRAY,
          List.of(Component.text("装备更大的胸挂或背包可解锁", NamedTextColor.GRAY)));
      case SECURE -> secureEntry();
      case SUMMARY -> summary(session);
      case CLOSE, DECORATION -> decoration();
    };
  }

  private ItemStack headerItem(MenuSession session, String gridId) {
    RaidCarryGridPlanner.Region region = session.view.regions().stream()
        .filter(candidate -> candidate.id().equals(gridId)).findFirst().orElseThrow();
    int occupied = 0;
    ItemGrid grid = session.view.layout().find(gridId).orElseThrow();
    for (var shapeCell : region.shape().cells()) {
      if (grids.placementAt(grid, shapeCell.position()).isPresent()) occupied++;
    }
    boolean focused = gridId.equals(session.focusGridId);
    String name = focused ? "▶ 已打开：" + regionLabel(gridId) : regionLabel(gridId) + "区域";
    List<Component> lore = new ArrayList<>();
    lore.add(Component.text("边界 " + region.shape().width() + "×" + region.shape().height()
        + " · 可用 " + (region.cells().size() - occupied) + "/" + region.cells().size()
        + " 格", NamedTextColor.WHITE));
    lore.add(Component.text("下方同色网格属于这个区域", NamedTextColor.GRAY));
    if (focused) {
      lore.add(Component.text("已从装备栏右键直达", NamedTextColor.GREEN));
    }
    return named(headerMaterial(gridId, focused), name, regionColor(gridId), lore);
  }

  private ItemStack borderItem(MenuSession session, String gridId) {
    boolean focused = gridId.equals(session.focusGridId);
    return named(headerMaterial(gridId, focused), "━ " + regionLabel(gridId) + "边界",
        regionColor(gridId), List.of(Component.text("同色标题条标出区域边界", NamedTextColor.GRAY)));
  }

  private ItemStack regionItem(MenuSession session,
      RaidCarryMenuProjection.DisplayCell cell) {
    ItemGrid grid = session.view.layout().find(cell.gridId()).orElseThrow();
    GridItemPlacement placement = grids.placementAt(grid, cell.position()).orElse(null);
    String region = regionLabel(cell.gridId());
    NamedTextColor color = regionColor(cell.gridId());
    if (placement == null) {
      SelectedPlacement selected = sourcePlacement(session, session.selection);
      PlacementPreview preview = placementPreview(session, cell.gridId(), cell.position(), selected);
      if (preview != null) {
        String name = preview.accepted()
            ? "✓ 可放置 · " + region : "✕ 放不下 · " + region;
        NamedTextColor previewColor = preview.accepted()
            ? NamedTextColor.GREEN : NamedTextColor.RED;
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("所选物品需要 " + preview.width() + "×" + preview.height()
            + " 个连续网格", NamedTextColor.WHITE));
        lore.add(Component.text(preview.accepted()
            ? "左键确认移动到这里"
            : failureDetail(preview.failure(), preview.width(), preview.height(), region),
            preview.accepted() ? NamedTextColor.GREEN : NamedTextColor.RED));
        lore.add(coordinate(cell));
        return named(preview.accepted() ? Material.LIME_STAINED_GLASS_PANE
            : Material.RED_STAINED_GLASS_PANE, name, previewColor, lore);
      }
      return named(regionMaterial(cell.gridId()), region + " · 1 个可用网格", color,
          List.of(Component.text("左键选物品后，这里会预演能否放入", NamedTextColor.GRAY),
              Component.text("大件需要连续的宽×高网格，不是只看空格总数",
                  NamedTextColor.YELLOW), coordinate(cell)));
    }
    boolean selected = session.selection != null
        && session.selection.instanceId().equals(placement.item().instanceId());
    if (!placement.anchor().equals(cell.position())) {
      List<Component> lore = new ArrayList<>();
      lore.add(Component.text(region + " · 被物品覆盖", color));
      lore.add(Component.text("锚点 " + placement.anchor().column() + ","
          + placement.anchor().row(), NamedTextColor.DARK_GRAY));
      lore.add(Component.text("属于 " + placement.footprint().width(placement.rotation())
          + "×" + placement.footprint().height(placement.rotation()) + " 物品",
          NamedTextColor.GRAY));
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

  private ItemStack summary(MenuSession session) {
    MenuView view = session.view;
    List<Component> lore = new ArrayList<>(List.of(
        Component.text("重量 " + weightLabel(view), NamedTextColor.WHITE),
        Component.text("容量 " + capacityLabel(view), NamedTextColor.WHITE),
        Component.text("移动速度 " + Math.round(view.snapshot().movementMultiplier() * 100)
            + "%", NamedTextColor.GRAY),
        Component.empty()));
    if (session.focusGridId != null) {
      lore.add(Component.text("当前直达 " + regionLabel(session.focusGridId),
          regionColor(session.focusGridId)));
    }
    lore.addAll(List.of(
        Component.text("左键选物品，再点目标格移动", NamedTextColor.YELLOW),
        Component.text("右键可旋转允许旋转的物品", NamedTextColor.AQUA),
        Component.text("右下角末影箱可打开行动安全箱", NamedTextColor.LIGHT_PURPLE)));
    return named(Material.COMPASS, "携行状态", NamedTextColor.GOLD, lore);
  }

  private static ItemStack secureEntry() {
    ItemStack entry = named(Material.ENDER_CHEST, "行动安全箱 · 3 格",
        NamedTextColor.LIGHT_PURPLE,
        List.of(Component.text("点击打开安全箱", NamedTextColor.GREEN),
            Component.text("存入物品即使阵亡或超时也会保留", NamedTextColor.YELLOW),
            Component.text("背包物资可先移到口袋并拿在主手", NamedTextColor.GRAY),
            Component.text("在安全箱内用主手物品点击“存入”", NamedTextColor.GRAY)));
    NamespacedKey model = NamespacedKey.fromString("escapefromcraftov:secure_case");
    if (model != null) entry.editMeta(meta -> meta.setItemModel(model));
    return entry;
  }

  private @Nullable SelectedPlacement sourcePlacement(MenuSession session,
      @Nullable Selection selection) {
    if (selection == null || selection.version() != session.view.version()) return null;
    for (RaidCarryGridPlanner.Region region : session.view.regions()) {
      ItemGrid grid = session.view.layout().find(region.id()).orElseThrow();
      for (GridItemPlacement placement : grid.placements()) {
        if (placement.item().instanceId().equals(selection.instanceId())) {
          return new SelectedPlacement(region.id(), placement);
        }
      }
    }
    return null;
  }

  private @Nullable PlacementPreview placementPreview(MenuSession session, String targetGridId,
      GridPosition targetAnchor,
      @Nullable SelectedPlacement source) {
    if (source == null) return null;
    GridItemPlacement placement = source.placement();
    int width = placement.footprint().width(placement.rotation());
    int height = placement.footprint().height(placement.rotation());
    var result = grids.transfer(session.view.layout(), source.gridId(), targetGridId,
        placement.item().instanceId(), targetAnchor, placement.rotation());
    return result.accepted()
        ? new PlacementPreview(true, null, width, height)
        : new PlacementPreview(false,
            result.rejection().orElse(GridPlacementFailure.INVALID_GRID), width, height);
  }

  private void rejectPreview(Player player, PlacementPreview preview, String targetGridId) {
    player.sendActionBar(Component.text("放不下：" + failureDetail(preview.failure(),
        preview.width(), preview.height(), regionLabel(targetGridId)), NamedTextColor.RED));
  }

  static String failureDetail(@Nullable GridPlacementFailure failure, int width, int height,
      String region) {
    String size = width + "×" + height;
    if (failure == null) return "需要 " + size + " 个连续网格";
    return switch (failure) {
      case OUT_OF_BOUNDS -> "需要 " + size + " 连续格；从这里会越过" + region + "边界";
      case OCCUPIED -> "需要 " + size + " 连续格；范围内已有其他物品";
      case COMPARTMENT_BOUNDARY -> "口袋格彼此独立，" + size + " 大件不能跨格放置";
      case BLOCKED_CELL -> "需要 " + size + " 连续格；范围内含不可用格";
      case ROTATION_NOT_ALLOWED -> "这个物品不允许旋转";
      case NO_SPACE -> region + "没有完整的 " + size + " 连续空区";
      case ITEM_NOT_FOUND -> "所选物品已经移动，请重新选择";
      case GRID_NOT_FOUND, INVALID_GRID -> "布局已经变化，请重新打开整理菜单";
      case UNKNOWN_ITEM, INVALID_PHYSICAL_STACK, FOOTPRINT_MISMATCH,
          DUPLICATE_INSTANCE -> "物品身份或尺寸校验失败，请重新选择";
    };
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

  private static Material headerMaterial(String gridId, boolean focused) {
    if (!focused) return regionMaterial(gridId);
    return switch (gridId) {
      case RaidCarryGridPlanner.POCKETS -> Material.EMERALD_BLOCK;
      case RaidCarryGridPlanner.CHEST_RIG -> Material.GOLD_BLOCK;
      case RaidCarryGridPlanner.BACKPACK -> Material.DIAMOND_BLOCK;
      default -> Material.AMETHYST_BLOCK;
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
    if (!(event.getWhoClicked() instanceof Player player)) return;
    if (viewing(player)) {
      // Covers shift-click, number keys, cursor collection and every direct click variant.
      event.setCancelled(true);
      return;
    }
    if (!carry.active(player.getUniqueId()) || !event.isRightClick()
        || !event.getCursor().isEmpty()
        || event.getClickedInventory() != player.getInventory()) return;
    String focusGridId = equipmentRegion(event.getSlot());
    if (focusGridId == null || !equipmentStackMatches(event.getCurrentItem(), focusGridId)) return;
    event.setCancelled(true);
    requestFocusedOpen(player, event.getSlot(), focusGridId);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInteract(PlayerInteractEvent event) {
    if (event.getHand() != EquipmentSlot.HAND
        || event.getAction() != Action.RIGHT_CLICK_AIR
            && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
    requestHeldEquipmentOpen(event.getPlayer(), event::setCancelled);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInteractEntity(PlayerInteractEntityEvent event) {
    if (event.getHand() != EquipmentSlot.HAND) return;
    requestHeldEquipmentOpen(event.getPlayer(), event::setCancelled);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
    onInteractEntity(event);
  }

  private void requestHeldEquipmentOpen(Player player,
      java.util.function.Consumer<Boolean> cancellation) {
    if (!carry.active(player.getUniqueId())) return;
    int physicalSlot = player.getInventory().getHeldItemSlot();
    String focusGridId = equipmentRegion(physicalSlot);
    if (focusGridId == null
        || !equipmentStackMatches(player.getInventory().getItem(physicalSlot), focusGridId)) return;
    cancellation.accept(true);
    requestFocusedOpen(player, physicalSlot, focusGridId);
  }

  private void requestFocusedOpen(Player player, int physicalSlot, String focusGridId) {
    UUID playerId = player.getUniqueId();
    PendingFocusedOpen pending = new PendingFocusedOpen(physicalSlot, focusGridId);
    pendingFocusedOpens.put(playerId, pending);
    // Entity interaction variants can fire together. One next-tick request coalesces them and
    // avoids opening a packet inventory in the middle of a Bukkit inventory interaction.
    scheduler.runLater(Duration.ZERO, () -> {
      if (!pendingFocusedOpens.remove(playerId, pending) || !player.isOnline()) return;
      openFocused(player, pending.physicalSlot(), pending.focusGridId());
    });
  }

  private boolean equipmentRegionAvailable(Player player, MenuView view, int physicalSlot,
      String focusGridId) {
    return focusGridId.equals(equipmentRegion(physicalSlot))
        && view.regions().stream().anyMatch(region -> region.id().equals(focusGridId))
        && equipmentStackMatches(player.getInventory().getItem(physicalSlot), focusGridId);
  }

  private boolean equipmentStackMatches(@Nullable ItemStack stack, String focusGridId) {
    var tagged = itemStacks.read(stack).orElse(null);
    if (tagged == null) return false;
    ItemCategory category = items.require(tagged.itemId()).category();
    return focusGridId.equals(RaidCarryGridPlanner.CHEST_RIG)
        ? category == ItemCategory.CHEST_RIG
        : focusGridId.equals(RaidCarryGridPlanner.BACKPACK)
            && category == ItemCategory.BACKPACK;
  }

  static @Nullable String equipmentRegion(int physicalSlot) {
    return switch (physicalSlot) {
      case RaidCarryLayout.CHEST_RIG_SLOT -> RaidCarryGridPlanner.CHEST_RIG;
      case RaidCarryLayout.BACKPACK_SLOT -> RaidCarryGridPlanner.BACKPACK;
      default -> null;
    };
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
    // Let TrMenu finish its own close lifecycle before deciding whether the player really left.
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
    pendingFocusedOpens.remove(playerId);
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
    pendingFocusedOpens.clear();
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
    private @Nullable String focusGridId;

    private MenuSession(String nonce, MenuView view, @Nullable String focusGridId) {
      this.nonce = nonce;
      this.focusGridId = focusGridId;
      update(view);
    }

    private void update(MenuView updated) {
      view = Preconditions.checkNotNull(updated, "updated");
      if (focusGridId != null && view.regions().stream()
          .noneMatch(region -> region.id().equals(focusGridId))) focusGridId = null;
      projection = RaidCarryMenuProjection.project(view.regions(),
          view.lockedPhysicalSlots().size());
    }
  }

  private record Selection(UUID instanceId, long version) {}

  private record SelectedPlacement(String gridId, GridItemPlacement placement) {}

  private record PlacementPreview(
      boolean accepted,
      @Nullable GridPlacementFailure failure,
      int width,
      int height) {

    private PlacementPreview {
      Preconditions.checkArgument(accepted == (failure == null),
          "accepted previews must not have a failure");
      Preconditions.checkArgument(width > 0 && height > 0,
          "preview dimensions must be positive");
    }
  }

  private record PendingFocusedOpen(int physicalSlot, String focusGridId) {}

  private record ValidatedAction(MenuSession session, MenuView view) {}
}
