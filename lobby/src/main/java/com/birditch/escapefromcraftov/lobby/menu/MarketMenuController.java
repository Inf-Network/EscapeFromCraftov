package com.birditch.escapefromcraftov.lobby.menu;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventorySnapshot;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.bukkit.TrMenuBridge;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import com.birditch.escapefromcraftov.market.HibernatePlayerMarketService;
import com.birditch.escapefromcraftov.market.MarketListing;
import com.birditch.escapefromcraftov.market.MarketMutation;
import com.birditch.escapefromcraftov.market.MarketPage;
import com.birditch.escapefromcraftov.market.MarketTrade;
import com.birditch.escapefromcraftov.market.PlayerMarketService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/// TrMenu-backed player market browser, seller listings and price confirmation.
public final class MarketMenuController implements Listener, AutoCloseable {

  public static final String BROWSE_MENU = "escapefromcraftov_lobby_market";
  public static final String MINE_MENU = "escapefromcraftov_lobby_market_mine";
  public static final String LIST_MENU = "escapefromcraftov_lobby_market_list";
  public static final String CONFIRM_MENU = "escapefromcraftov_lobby_market_confirm";
  private static final int PAGE_SIZE = 36;
  private static final Duration LISTING_LIFETIME = Duration.ofHours(24);

  private final MarketMenuDeps deps;
  private final TrMenuBridge menus;
  private final AutoCloseable itemSource;
  private final Map<UUID, MarketPage> browseViews = new HashMap<>();
  private final Map<UUID, MarketPage> mineViews = new HashMap<>();
  private final Map<UUID, WarehouseView> listViews = new HashMap<>();
  private final Map<UUID, PendingListing> pending = new HashMap<>();
  private final Set<UUID> busy = new HashSet<>();

  public MarketMenuController(MarketMenuDeps deps, TrMenuBridge menus) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
    this.menus = Preconditions.checkNotNull(menus, "menus");
    itemSource = menus.registerItemSource("CRAFTOVMARKET", this::menuItem);
  }

  public void openBrowse(Player player, int page) {
    requireReady(player);
    deps.market().browse(Math.max(0, page), PAGE_SIZE).whenComplete((view, error) ->
        deps.scheduler().runSync(() -> afterPageLoad(player, BROWSE_MENU, view, error)));
  }

  public void openMine(Player player, int page) {
    requireReady(player);
    deps.market().mine(player.getUniqueId(), Math.max(0, page), PAGE_SIZE)
        .whenComplete((view, error) -> deps.scheduler().runSync(() ->
            afterPageLoad(player, MINE_MENU, view, error)));
  }

  public void openList(Player player, int page) {
    requireReady(player);
    deps.market().expireDue().thenCompose(_ ->
        deps.inventories().load(player.getUniqueId())).whenComplete((snapshot, error) ->
        deps.scheduler().runSync(() -> afterWarehouseLoad(player, Math.max(0, page), snapshot, error)));
  }

  public void page(Player player, String view, int direction) {
    Preconditions.checkArgument(direction == -1 || direction == 1, "direction must be -1 or 1");
    switch (view.toLowerCase(Locale.ROOT)) {
      case "browse" -> {
        requireViewing(player, BROWSE_MENU);
        openBrowse(player, requirePage(browseViews, player).page() + direction);
      }
      case "mine" -> {
        requireViewing(player, MINE_MENU);
        openMine(player, requirePage(mineViews, player).page() + direction);
      }
      case "list" -> {
        requireViewing(player, LIST_MENU);
        openList(player, requireWarehouse(player).page() + direction);
      }
      default -> throw new IllegalArgumentException("未知交易行页面: " + view);
    }
  }

  public void buy(Player player, int visibleSlot) {
    requireReady(player);
    requireViewing(player, BROWSE_MENU);
    MarketListing listing = listingAt(requirePage(browseViews, player), visibleSlot).orElse(null);
    UUID playerId = player.getUniqueId();
    if (listing == null || !busy.add(playerId)) return;
    deps.market().buy(UUID.randomUUID(), playerId, listing.listingId())
        .whenComplete((result, error) -> deps.scheduler().runSync(() ->
            afterBuy(player, requirePage(browseViews, player).page(), result, error)));
  }

  public void cancel(Player player, int visibleSlot) {
    requireReady(player);
    requireViewing(player, MINE_MENU);
    MarketListing listing = listingAt(requirePage(mineViews, player), visibleSlot).orElse(null);
    UUID playerId = player.getUniqueId();
    if (listing == null || !busy.add(playerId)) return;
    deps.market().cancel(UUID.randomUUID(), playerId, listing.listingId())
        .whenComplete((result, error) -> deps.scheduler().runSync(() ->
            afterCancel(player, requirePage(mineViews, player).page(), result, error)));
  }

  public void selectForListing(Player player, int visibleSlot) {
    requireReady(player);
    requireViewing(player, LIST_MENU);
    StoredItem item = warehouseAt(requireWarehouse(player), visibleSlot).orElse(null);
    if (item == null) return;
    ItemDefinition definition = deps.itemRegistry().require(item.itemId());
    long suggested;
    try {
      suggested = Math.multiplyExact(definition.basePrice().units(), item.quantity());
    } catch (ArithmeticException ignored) {
      suggested = HibernatePlayerMarketService.MAX_PRICE;
    }
    suggested = Math.max(1, Math.min(suggested, HibernatePlayerMarketService.MAX_PRICE));
    pending.put(player.getUniqueId(), new PendingListing(item, suggested));
    open(player, CONFIRM_MENU);
  }

  public void setPrice(Player player, long price) {
    requireReady(player);
    PendingListing value = requirePending(player);
    long bounded = Math.max(1, Math.min(price, HibernatePlayerMarketService.MAX_PRICE));
    pending.put(player.getUniqueId(), new PendingListing(value.item(), bounded));
    if (menus.isViewing(player, CONFIRM_MENU)) open(player, CONFIRM_MENU);
  }

  public void adjustPrice(Player player, long delta) {
    PendingListing value = requirePending(player);
    long price;
    try {
      price = Math.addExact(value.price(), delta);
    } catch (ArithmeticException ignored) {
      price = delta > 0 ? HibernatePlayerMarketService.MAX_PRICE : 1;
    }
    setPrice(player, price);
  }

  public void confirmListing(Player player) {
    requireReady(player);
    requireViewing(player, CONFIRM_MENU);
    PendingListing value = requirePending(player);
    UUID playerId = player.getUniqueId();
    if (!busy.add(playerId)) return;
    deps.market().listItem(UUID.randomUUID(), UUID.randomUUID(), playerId,
        value.item().storageId(), Money.of(value.price()), LISTING_LIFETIME)
        .whenComplete((result, error) -> deps.scheduler().runSync(() ->
            afterList(player, result, error)));
  }

  private ItemStack menuItem(Player player, String rawId) {
    String id = rawId.toLowerCase(Locale.ROOT);
    if (id.startsWith("browse-")) return marketItem(player, browseViews, index(id, "browse-"), false);
    if (id.startsWith("mine-")) return marketItem(player, mineViews, index(id, "mine-"), true);
    if (id.startsWith("list-")) return warehouseItem(player, index(id, "list-"));
    return switch (id) {
      case "confirm-item" -> pendingItem(player);
      case "confirm-price" -> priceItem(player);
      default -> air();
    };
  }

  private ItemStack marketItem(Player player, Map<UUID, MarketPage> views,
      int slot, boolean cancel) {
    MarketPage page = views.get(player.getUniqueId());
    MarketListing listing = page == null ? null : listingAt(page, slot).orElse(null);
    if (listing == null) return air();
    List<ItemStack> rendered = deps.itemStacks().fromInstance(new ItemInstance(
        listing.instanceId(), listing.itemId(), listing.quantity(), listing.itemData()));
    if (rendered.isEmpty()) return air();
    ItemStack stack = rendered.getFirst();
    List<Component> lore = new ArrayList<>(Optional.ofNullable(stack.lore()).orElse(List.of()));
    lore.add(Component.empty());
    lore.add(Component.text("整组数量: ×" + listing.quantity(), NamedTextColor.GRAY));
    lore.add(Component.text("价格: " + listing.price().format() + " 金币", NamedTextColor.GOLD));
    lore.add(Component.text(cancel ? "点击撤回挂单" : "点击购买", NamedTextColor.YELLOW));
    stack.lore(unitalic(lore));
    return stack;
  }

  private ItemStack warehouseItem(Player player, int slot) {
    WarehouseView view = listViews.get(player.getUniqueId());
    StoredItem stored = view == null ? null : warehouseAt(view, slot).orElse(null);
    if (stored == null) return air();
    List<ItemStack> rendered = deps.itemStacks().fromStored(stored);
    if (rendered.isEmpty()) return air();
    ItemStack stack = rendered.getFirst();
    List<Component> lore = new ArrayList<>(Optional.ofNullable(stack.lore()).orElse(List.of()));
    lore.add(Component.empty());
    lore.add(Component.text("点击为整组物品定价", NamedTextColor.YELLOW));
    stack.lore(unitalic(lore));
    return stack;
  }

  private ItemStack pendingItem(Player player) {
    PendingListing value = pending.get(player.getUniqueId());
    if (value == null) return air();
    List<ItemStack> rendered = deps.itemStacks().fromStored(value.item());
    return rendered.isEmpty() ? air() : rendered.getFirst();
  }

  private ItemStack priceItem(Player player) {
    PendingListing value = pending.get(player.getUniqueId());
    if (value == null) return air();
    return item(Material.GOLD_INGOT, Component.text("卖家定价", NamedTextColor.GOLD), List.of(
        Component.text(value.price() + " 金币", NamedTextColor.YELLOW),
        Component.text("按钮微调，或输入 /lobby market list price <金额>", NamedTextColor.GRAY)));
  }

  private void afterPageLoad(Player player, String menu,
      @Nullable MarketPage view, @Nullable Throwable error) {
    if (!player.isOnline()) return;
    if (error != null || view == null) {
      deps.logger().log(Level.SEVERE, "加载交易行失败:" + player.getUniqueId(), error);
      player.sendMessage(deps.messages().raw("menu.market.load-failed"));
      return;
    }
    (menu.equals(BROWSE_MENU) ? browseViews : mineViews).put(player.getUniqueId(), view);
    open(player, menu, Integer.toString(view.page() + 1), Integer.toString(view.totalPages()));
  }

  private void afterWarehouseLoad(Player player, int requestedPage,
      @Nullable PlayerInventorySnapshot snapshot, @Nullable Throwable error) {
    if (!player.isOnline()) return;
    if (error != null || snapshot == null) {
      deps.logger().log(Level.SEVERE, "加载交易行上架仓库失败:" + player.getUniqueId(), error);
      player.sendMessage(deps.messages().raw("menu.market.load-failed"));
      return;
    }
    List<StoredItem> items = snapshot.at(InventoryLocation.WAREHOUSE);
    int pages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    int page = Math.min(requestedPage, pages - 1);
    listViews.put(player.getUniqueId(), new WarehouseView(items, page, pages));
    open(player, LIST_MENU, Integer.toString(page + 1), Integer.toString(pages));
  }

  private void afterBuy(Player player, int page,
      @Nullable Result<MarketTrade> result, @Nullable Throwable error) {
    busy.remove(player.getUniqueId());
    if (!player.isOnline()) return;
    if (error != null || result == null || !result.isSuccess()) {
      String failure = result == null ? "" : result.error().orElse("");
      String key = failure.equals(PlayerMarketService.INSUFFICIENT_FUNDS)
          ? "menu.market.insufficient" : failure.equals(PlayerMarketService.CANNOT_BUY_OWN)
              ? "menu.market.own-listing" : "menu.market.changed";
      if (error != null) deps.logger().log(Level.SEVERE, "交易行购买失败", error);
      player.sendMessage(deps.messages().raw(key));
      openBrowse(player, page);
      return;
    }
    MarketTrade trade = result.value().orElseThrow();
    deps.profiles().find(player.getUniqueId())
        .ifPresent(profile -> profile.setBalance(trade.buyerBalanceAfter()));
    deps.profiles().find(trade.listing().sellerId())
        .ifPresent(profile -> profile.setBalance(trade.sellerBalanceAfter()));
    player.sendMessage(deps.messages().raw("menu.market.bought",
        Placeholder.unparsed("price", trade.listing().price().format()),
        Placeholder.unparsed("balance", trade.buyerBalanceAfter().format())));
    openBrowse(player, page);
  }

  private void afterCancel(Player player, int page,
      @Nullable Result<MarketMutation> result, @Nullable Throwable error) {
    busy.remove(player.getUniqueId());
    if (!player.isOnline()) return;
    if (error != null || result == null || !result.isSuccess()) {
      if (error != null) deps.logger().log(Level.SEVERE, "交易行撤单失败", error);
      player.sendMessage(deps.messages().raw("menu.market.changed"));
    } else {
      player.sendMessage(deps.messages().raw("menu.market.cancelled"));
    }
    openMine(player, page);
  }

  private void afterList(Player player,
      @Nullable Result<MarketMutation> result, @Nullable Throwable error) {
    busy.remove(player.getUniqueId());
    if (!player.isOnline()) return;
    if (error != null || result == null || !result.isSuccess()) {
      if (error != null) deps.logger().log(Level.SEVERE, "交易行上架失败", error);
      player.sendMessage(deps.messages().raw("menu.market.changed"));
      openList(player, 0);
      return;
    }
    MarketListing listing = result.value().orElseThrow().listing();
    pending.remove(player.getUniqueId());
    player.sendMessage(deps.messages().raw("menu.market.listed",
        Placeholder.unparsed("price", listing.price().format())));
    openMine(player, 0);
  }

  private static Optional<MarketListing> listingAt(MarketPage page, int slot) {
    return slot >= 0 && slot < page.listings().size()
        ? Optional.of(page.listings().get(slot)) : Optional.empty();
  }

  private static Optional<StoredItem> warehouseAt(WarehouseView view, int slot) {
    int index = view.page() * PAGE_SIZE + slot;
    return slot >= 0 && slot < PAGE_SIZE && index < view.items().size()
        ? Optional.of(view.items().get(index)) : Optional.empty();
  }

  private MarketPage requirePage(Map<UUID, MarketPage> views, Player player) {
    requireReady(player);
    MarketPage page = views.get(player.getUniqueId());
    if (page == null) throw new IllegalStateException("请先打开交易行页面");
    return page;
  }

  private WarehouseView requireWarehouse(Player player) {
    WarehouseView view = listViews.get(player.getUniqueId());
    if (view == null) throw new IllegalStateException("请先打开交易行上架页面");
    return view;
  }

  private PendingListing requirePending(Player player) {
    PendingListing value = pending.get(player.getUniqueId());
    if (value == null) throw new IllegalStateException("请先选择要上架的物品");
    return value;
  }

  private void requireReady(Player player) {
    if (!deps.dataGate().isReady(player.getUniqueId())) {
      player.sendMessage(deps.messages().raw("queue.data-loading"));
      throw new IllegalStateException("玩家数据仍在加载");
    }
  }

  private void requireViewing(Player player, String menu) {
    if (!menus.isViewing(player, menu)) throw new IllegalStateException("请先打开对应交易行菜单");
  }

  private void open(Player player, String menu, String... arguments) {
    if (!menus.open(player, menu, arguments)) {
      throw new IllegalStateException("TrMenu 无法打开交易行菜单: " + menu);
    }
  }

  private static int index(String id, String prefix) {
    try { return Integer.parseInt(id.substring(prefix.length())); }
    catch (NumberFormatException ignored) { return -1; }
  }

  private static ItemStack item(Material material, Component name, List<Component> lore) {
    ItemStack stack = new ItemStack(material);
    stack.editMeta(meta -> {
      meta.itemName(name.decoration(TextDecoration.ITALIC, false));
      meta.lore(unitalic(lore));
    });
    return stack;
  }

  private static List<Component> unitalic(List<Component> lines) {
    return lines.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList();
  }

  private static ItemStack air() { return new ItemStack(Material.AIR); }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) { clear(event.getPlayer()); }

  private void clear(Player player) {
    UUID id = player.getUniqueId();
    menus.close(player, BROWSE_MENU, MINE_MENU, LIST_MENU, CONFIRM_MENU);
    browseViews.remove(id);
    mineViews.remove(id);
    listViews.remove(id);
    pending.remove(id);
    busy.remove(id);
  }

  @Override
  public void close() throws Exception {
    for (Player player : List.copyOf(org.bukkit.Bukkit.getOnlinePlayers())) clear(player);
    itemSource.close();
  }

  private record WarehouseView(List<StoredItem> items, int page, int totalPages) {}
  private record PendingListing(StoredItem item, long price) {}
}
