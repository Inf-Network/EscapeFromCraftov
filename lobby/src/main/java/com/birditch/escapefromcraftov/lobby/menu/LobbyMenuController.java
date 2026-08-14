package com.birditch.escapefromcraftov.lobby.menu;

import com.birditch.escapefromcraftov.api.cosmetics.Cosmetic;
import com.birditch.escapefromcraftov.api.cosmetics.CosmeticId;
import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.inventory.LoadoutSlot;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventorySnapshot;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.item.ItemCategory;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.item.ItemRarity;
import com.birditch.escapefromcraftov.api.settings.SettingKey;
import com.birditch.escapefromcraftov.bukkit.TrMenuBridge;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import com.birditch.escapefromcraftov.lobby.CosmeticApplier;
import com.birditch.escapefromcraftov.lobby.LobbyVisibility;
import com.birditch.escapefromcraftov.vendor.MilitaryVendorService;
import com.birditch.escapefromcraftov.vendor.VendorPrices;
import com.birditch.escapefromcraftov.vendor.VendorTrade;
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
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Backend for every Lobby TrMenu menu. PostgreSQL/Hibernate services remain
 * authoritative; TrMenu owns rendering, virtual slots and click cancellation.
 */
public final class LobbyMenuController implements Listener, AutoCloseable {

  private static final int LOADOUT_SLOTS = 9;
  private static final List<LoadoutSlot> NAMED_LOADOUT_SLOTS = List.of(LoadoutSlot.values());
  private static final int WAREHOUSE_PAGE_SIZE = 27;
  private static final String SETTINGS_MENU = "escapefromcraftov_lobby_settings";
  private static final String COSMETICS_MENU = "escapefromcraftov_lobby_cosmetics";
  private static final String WAREHOUSE_MENU = "escapefromcraftov_lobby_warehouse";
  private static final String VENDOR_MENU = "escapefromcraftov_lobby_vendor";
  private static final String VENDOR_SELL_MENU = "escapefromcraftov_lobby_vendor_sell";
  private static final int VENDOR_SELL_PAGE_SIZE = 27;

  private final MenuDeps deps;
  private final TrMenuBridge menus;
  private final AutoCloseable itemSource;
  private final Map<UUID, WarehouseView> warehouseViews = new HashMap<>();
  private final Map<UUID, VendorSellView> vendorSellViews = new HashMap<>();
  private final Set<UUID> warehouseBusy = new HashSet<>();
  private final Set<UUID> vendorBusy = new HashSet<>();
  private final Set<UUID> menuBusy = new HashSet<>();

  public LobbyMenuController(MenuDeps deps, TrMenuBridge menus) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
    this.menus = Preconditions.checkNotNull(menus, "menus");
    this.itemSource = menus.registerItemSource("CRAFTOVCORELOBBY", this::menuItem);
  }

  public void openMain(Player player) {
    requireReady(player);
    open(player, "escapefromcraftov_lobby_main");
  }

  public void openSettings(Player player) {
    requireReady(player);
    open(player, SETTINGS_MENU);
  }

  public void openCosmetics(Player player) {
    requireReady(player);
    open(player, COSMETICS_MENU);
  }

  public int cosmeticCount() {
    return deps.cosmetics().catalog().size();
  }

  public void openWarehouse(Player player, int requestedPage) {
    requireReady(player);
    deps.inventories().load(player.getUniqueId()).whenComplete((snapshot, error) ->
        deps.scheduler().runSync(() -> afterWarehouseLoad(player, requestedPage, snapshot, error)));
  }

  public void openVendor(Player player) {
    requireReady(player);
    open(player, VENDOR_MENU);
  }

  public void openVendorSell(Player player, int requestedPage) {
    requireReady(player);
    deps.inventories().load(player.getUniqueId()).whenComplete((snapshot, error) ->
        deps.scheduler().runSync(() ->
            afterVendorSellLoad(player, requestedPage, snapshot, error)));
  }

  public void vendorSellPage(Player player, int direction) {
    Preconditions.checkArgument(direction == -1 || direction == 1,
        "direction must be -1 or 1");
    requireViewing(player, VENDOR_SELL_MENU);
    VendorSellView view = requireVendorSellView(player);
    openVendorSell(player, view.page() + direction);
  }

  public void buyVendorItem(Player player, int offerIndex, int quantity) {
    requireReady(player);
    requireNotQueued(player);
    requireViewing(player, VENDOR_MENU);
    VendorPrices.requirePurchaseQuantity(quantity);
    List<ItemDefinition> offers = vendorOffers();
    if (offerIndex < 0 || offerIndex >= offers.size()) {
      throw new IllegalArgumentException("军需处商品编号不存在: " + offerIndex);
    }
    ItemDefinition definition = offers.get(offerIndex);
    if (quantity > definition.stackSize()) {
      player.sendMessage(deps.messages().raw("menu.vendor.quantity-limited",
          Placeholder.unparsed("maximum", Integer.toString(
              maximumVendorQuantity(definition.stackSize())))));
      return;
    }
    UUID playerId = player.getUniqueId();
    if (!vendorBusy.add(playerId)) {
      player.sendMessage(deps.messages().raw("menu.vendor.busy"));
      return;
    }
    UUID operationId = UUID.randomUUID();
    try {
      deps.vendor().buy(operationId, playerId, definition.id(), quantity)
          .whenComplete((result, error) -> deps.scheduler().runSync(() ->
              afterVendorBuy(player, definition, quantity, result, error)));
    } catch (RuntimeException error) {
      vendorBusy.remove(playerId);
      throw error;
    }
  }

  public void sellVendorItem(Player player, int visibleSlot) {
    requireReady(player);
    requireNotQueued(player);
    requireViewing(player, VENDOR_SELL_MENU);
    if (visibleSlot < 0 || visibleSlot >= VENDOR_SELL_PAGE_SIZE) {
      throw new IllegalArgumentException("出售格子必须是 0..26");
    }
    VendorSellView view = requireVendorSellView(player);
    StoredItem stored = vendorSellAt(view, visibleSlot).orElse(null);
    UUID playerId = player.getUniqueId();
    if (stored == null || !vendorBusy.add(playerId)) return;
    UUID operationId = UUID.randomUUID();
    try {
      deps.vendor().sell(operationId, playerId, stored.storageId())
          .whenComplete((result, error) -> deps.scheduler().runSync(() ->
              afterVendorSell(player, view.page(), result, error)));
    } catch (RuntimeException error) {
      vendorBusy.remove(playerId);
      throw error;
    }
  }

  public void toggleSetting(Player player, String rawSetting) {
    requireReady(player);
    requireViewing(player, SETTINGS_MENU);
    SettingKey key = switch (rawSetting.toLowerCase(Locale.ROOT)) {
      case "visibility" -> SettingKey.LOBBY_PLAYER_VISIBILITY;
      case "queue_messages", "queue-messages" -> SettingKey.QUEUE_STATUS_MESSAGES;
      default -> throw new IllegalArgumentException("未知大厅设置: " + rawSetting);
    };
    UUID playerId = player.getUniqueId();
    if (!menuBusy.add(playerId)) return;
    boolean newValue = !deps.settings().get(playerId, key);
    try {
      deps.settings().set(playerId, key, newValue).whenComplete((_, error) ->
          deps.scheduler().runSync(() -> afterToggle(player, key, newValue, error)));
    } catch (RuntimeException error) {
      menuBusy.remove(playerId);
      throw error;
    }
  }

  public void equipCosmetic(Player player, int index) {
    requireReady(player);
    requireViewing(player, COSMETICS_MENU);
    List<Cosmetic> catalog = deps.cosmetics().catalog();
    if (index < 0 || index >= catalog.size()) {
      throw new IllegalArgumentException("外观编号不存在: " + index);
    }
    UUID playerId = player.getUniqueId();
    if (!menuBusy.add(playerId)) return;
    try {
      deps.cosmetics().equip(playerId, catalog.get(index).id())
          .whenComplete((_, error) -> deps.scheduler().runSync(() -> afterEquip(player, error)));
    } catch (RuntimeException error) {
      menuBusy.remove(playerId);
      throw error;
    }
  }

  public void unequipCosmetic(Player player) {
    requireReady(player);
    requireViewing(player, COSMETICS_MENU);
    UUID playerId = player.getUniqueId();
    if (!menuBusy.add(playerId)) return;
    try {
      deps.cosmetics().unequip(playerId).whenComplete((_, error) ->
          deps.scheduler().runSync(() -> afterUnequip(player, error)));
    } catch (RuntimeException error) {
      menuBusy.remove(playerId);
      throw error;
    }
  }

  public void warehousePage(Player player, int direction) {
    Preconditions.checkArgument(direction == -1 || direction == 1,
        "direction must be -1 or 1");
    requireViewing(player, WAREHOUSE_MENU);
    WarehouseView view = requireWarehouseView(player);
    openWarehouse(player, view.page() + direction);
  }

  public void moveWarehouseItem(Player player, int visibleSlot) {
    requireReady(player);
    requireNotQueued(player);
    requireViewing(player, WAREHOUSE_MENU);
    if (visibleSlot < 0 || visibleSlot >= LOADOUT_SLOTS + WAREHOUSE_PAGE_SIZE) {
      throw new IllegalArgumentException("仓库格子必须是 0..35");
    }
    WarehouseView view = requireWarehouseView(player);
    StoredItem item = storedAt(view, visibleSlot).orElse(null);
    UUID playerId = player.getUniqueId();
    if (item == null) return;
    InventoryLocation target = visibleSlot < LOADOUT_SLOTS
        ? InventoryLocation.WAREHOUSE : InventoryLocation.LOADOUT;
    @Nullable LoadoutSlot loadoutSlot = target == InventoryLocation.LOADOUT
        ? availableSlot(item, view.snapshot()) : null;
    if (!warehouseBusy.add(playerId)) return;
    var movement = target == InventoryLocation.LOADOUT
        ? deps.inventories().move(playerId, item.storageId(), target, loadoutSlot)
        : deps.inventories().move(playerId, item.storageId(), target);
    movement
        .whenComplete((_, error) -> deps.scheduler().runSync(() -> {
          warehouseBusy.remove(playerId);
          if (!player.isOnline()) return;
          if (error != null) {
            deps.logger().log(Level.WARNING, "仓库物品移动失败:" + playerId, error);
            player.sendMessage(deps.messages().raw("menu.warehouse.move-failed"));
          } else {
            player.sendMessage(target == InventoryLocation.LOADOUT
                ? deps.messages().raw("menu.warehouse.equipped",
                    Placeholder.unparsed("slot", slotName(loadoutSlot)))
                : deps.messages().raw("menu.warehouse.stored"));
          }
          if (menus.isViewing(player, WAREHOUSE_MENU)) openWarehouse(player, view.page());
        }));
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    menus.close(event.getPlayer(), SETTINGS_MENU, COSMETICS_MENU, WAREHOUSE_MENU,
        VENDOR_MENU, VENDOR_SELL_MENU, "escapefromcraftov_lobby_main");
    warehouseViews.remove(playerId);
    vendorSellViews.remove(playerId);
    warehouseBusy.remove(playerId);
    vendorBusy.remove(playerId);
    menuBusy.remove(playerId);
  }

  private ItemStack menuItem(Player player, String rawId) {
    String id = rawId.toLowerCase(Locale.ROOT);
    return switch (id) {
      case "setting-visibility" -> settingItem(player, SettingKey.LOBBY_PLAYER_VISIBILITY);
      case "setting-queue" -> settingItem(player, SettingKey.QUEUE_STATUS_MESSAGES);
      case "vendor-balance" -> vendorBalanceItem(player);
      default -> {
        if (id.startsWith("cosmetic-")) yield cosmeticItem(player, index(id, "cosmetic-"));
        if (id.startsWith("warehouse-")) yield warehouseItem(player, index(id, "warehouse-"));
        if (id.startsWith("vendor-offer-")) {
          yield vendorOfferItem(index(id, "vendor-offer-"));
        }
        if (id.startsWith("vendor-sell-")) {
          yield vendorSellItem(player, index(id, "vendor-sell-"));
        }
        yield air();
      }
    };
  }

  private ItemStack settingItem(Player player, SettingKey key) {
    boolean enabled = deps.settings().get(player.getUniqueId(), key);
    String base = "menu.settings." + key.name().toLowerCase(Locale.ROOT);
    List<Component> lore = new ArrayList<>(deps.messages().lines(base + ".lore"));
    lore.add(deps.messages().raw(enabled ? "menu.settings.state-on" : "menu.settings.state-off"));
    lore.add(deps.messages().raw("menu.settings.hint"));
    return item(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
        deps.messages().raw(base + ".name"), lore, enabled);
  }

  private ItemStack cosmeticItem(Player player, int index) {
    List<Cosmetic> catalog = deps.cosmetics().catalog();
    if (index < 0 || index >= catalog.size()) return air();
    Cosmetic cosmetic = catalog.get(index);
    boolean equipped = deps.cosmetics().equipped(player.getUniqueId())
        .map(Cosmetic::id).filter(cosmetic.id()::equals).isPresent();
    List<Component> lore = new ArrayList<>();
    if (equipped) lore.add(deps.messages().raw("menu.cosmetics.equipped"));
    lore.add(deps.messages().raw("menu.cosmetics.hint"));
    Material material = Optional.ofNullable(Material.matchMaterial(cosmetic.materialKey()))
        .orElse(Material.LEATHER_HELMET);
    return item(material,
        Component.text(cosmetic.displayName()).decoration(TextDecoration.ITALIC, false),
        lore, equipped);
  }

  private ItemStack warehouseItem(Player player, int visibleSlot) {
    WarehouseView view = warehouseViews.get(player.getUniqueId());
    if (view == null) return air();
    StoredItem stored = storedAt(view, visibleSlot).orElse(null);
    if (stored == null) {
      return visibleSlot < LOADOUT_SLOTS
          ? emptyLoadoutSlot(NAMED_LOADOUT_SLOTS.get(visibleSlot)) : air();
    }
    List<ItemStack> stacks = deps.itemStacks().fromStored(stored);
    if (stacks.isEmpty()) return air();
    ItemStack stack = stacks.getFirst();
    if (visibleSlot < LOADOUT_SLOTS) {
      List<Component> lore = new ArrayList<>(Optional.ofNullable(stack.lore()).orElse(List.of()));
      lore.add(Component.empty());
      if (stored.loadoutSlot() == null) {
        lore.add(Component.text("旧版兼容配装 · 将按物品类型自动装备", NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));
      } else {
        lore.add(Component.text("配装位: " + slotName(stored.loadoutSlot()), NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));
      }
      lore.add(Component.text("点击放回长期仓库", NamedTextColor.GRAY)
          .decoration(TextDecoration.ITALIC, false));
      stack.lore(lore);
    }
    return stack;
  }

  private static ItemStack emptyLoadoutSlot(LoadoutSlot slot) {
    Material material = switch (slot) {
      case MAIN_HAND -> Material.IRON_SWORD;
      case OFF_HAND -> Material.SHIELD;
      case HEAD -> Material.IRON_HELMET;
      case BODY_ARMOR -> Material.IRON_CHESTPLATE;
      case CHEST_RIG -> Material.LEATHER_CHESTPLATE;
      case BACKPACK -> Material.BUNDLE;
      case QUICK_USE_1 -> Material.REDSTONE;
      case QUICK_USE_2 -> Material.GLOWSTONE_DUST;
      case SECURE_CONTAINER -> Material.ENDER_CHEST;
    };
    List<Component> lore = new ArrayList<>();
    if (slot == LoadoutSlot.SECURE_CONTAINER) {
      lore.add(Component.text("内建安全箱 · 3 格", NamedTextColor.LIGHT_PURPLE));
      lore.add(Component.text("局内使用 /craftov secure", NamedTextColor.GRAY));
    } else if (slot == LoadoutSlot.CHEST_RIG) {
      lore.add(Component.text("从下方仓库点击胸挂即可装备", NamedTextColor.GRAY));
      lore.add(Component.text("胸挂容量会增加局内可用格数", NamedTextColor.AQUA));
    } else if (slot == LoadoutSlot.BACKPACK) {
      lore.add(Component.text("从下方仓库点击背包即可装备", NamedTextColor.GRAY));
      lore.add(Component.text("背包容量会增加局内可用格数", NamedTextColor.AQUA));
    } else {
      lore.add(Component.text("从下方仓库点击兼容物品即可装备", NamedTextColor.GRAY));
    }
    return item(material,
        Component.text("空 · " + slotName(slot), NamedTextColor.DARK_GRAY), lore, false);
  }

  private ItemStack vendorBalanceItem(Player player) {
    String balance = deps.profiles().find(player.getUniqueId())
        .map(profile -> profile.balance().format()).orElse("读取中");
    return item(Material.GOLD_INGOT,
        deps.messages().raw("menu.vendor.balance-name"),
        List.of(deps.messages().raw("menu.vendor.balance-lore",
            Placeholder.unparsed("balance", balance))), false);
  }

  private ItemStack vendorOfferItem(int offerIndex) {
    List<ItemDefinition> offers = vendorOffers();
    if (offerIndex < 0 || offerIndex >= offers.size()) return air();
    ItemDefinition definition = offers.get(offerIndex);
    UUID displayIdentity = UUID.nameUUIDFromBytes(
        ("vendor-display:" + definition.id().value()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    List<ItemStack> rendered = deps.itemStacks().fromInstance(
        new ItemInstance(displayIdentity, definition.id(), 1));
    if (rendered.isEmpty()) return air();
    ItemStack stack = rendered.getFirst();
    List<Component> lore = new ArrayList<>(List.of(
        Component.text("稀有度: " + rarityName(definition.rarity()), rarityColor(definition.rarity()))
            .decoration(TextDecoration.ITALIC, false),
        Component.text("买入: " + VendorPrices.buy(definition, 1).format() + " 金币",
                NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
        Component.text("卖出: " + VendorPrices.sell(definition, 1).format() + " 金币/个",
                NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
        Component.empty()));
    lore.add(Component.text(definition.stackSize() >= 8
            ? "左键购买 ×1  ·  右键购买 ×8" : "左键购买 ×1",
        NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
    if (definition.stackSize() >= 16) {
      lore.add(Component.text("Shift+左键购买 ×16", NamedTextColor.YELLOW)
          .decoration(TextDecoration.ITALIC, false));
    }
    stack.editMeta(meta -> meta.lore(lore));
    return stack;
  }

  private ItemStack vendorSellItem(Player player, int visibleSlot) {
    VendorSellView view = vendorSellViews.get(player.getUniqueId());
    if (view == null) return air();
    StoredItem stored = vendorSellAt(view, visibleSlot).orElse(null);
    if (stored == null) return air();
    ItemDefinition definition = deps.itemRegistry().find(stored.itemId()).orElse(null);
    if (definition == null || !definition.sellable()) return air();
    List<ItemStack> stacks = deps.itemStacks().fromStored(stored);
    if (stacks.isEmpty()) return air();
    ItemStack stack = stacks.getFirst();
    stack.setAmount(Math.min(stored.quantity(), stack.getMaxStackSize()));
    List<Component> lore = new ArrayList<>(Optional.ofNullable(stack.lore()).orElse(List.of()));
    lore.add(Component.empty());
    lore.add(Component.text("整组数量: ×" + stored.quantity(), NamedTextColor.GRAY)
        .decoration(TextDecoration.ITALIC, false));
    lore.add(Component.text("出售获得: "
            + VendorPrices.sell(definition, stored.quantity()).format() + " 金币",
        NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
    lore.add(Component.text("点击出售整组（不可撤销）", NamedTextColor.YELLOW)
        .decoration(TextDecoration.ITALIC, false));
    stack.lore(lore);
    return stack;
  }

  private void afterWarehouseLoad(Player player, int requestedPage,
      @Nullable PlayerInventorySnapshot snapshot, @Nullable Throwable error) {
    if (!player.isOnline()) return;
    if (error != null || snapshot == null) {
      deps.logger().log(Level.SEVERE, "加载仓库失败:" + player.getUniqueId(), error);
      player.sendMessage(deps.messages().raw("menu.warehouse.load-failed"));
      return;
    }
    int size = snapshot.at(InventoryLocation.WAREHOUSE).size();
    int totalPages = Math.max(1, (size + WAREHOUSE_PAGE_SIZE - 1) / WAREHOUSE_PAGE_SIZE);
    int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
    warehouseViews.put(player.getUniqueId(), new WarehouseView(snapshot, page, totalPages));
    open(player, WAREHOUSE_MENU, Integer.toString(page + 1),
        Integer.toString(totalPages));
  }

  private void afterVendorSellLoad(Player player, int requestedPage,
      @Nullable PlayerInventorySnapshot snapshot, @Nullable Throwable error) {
    if (!player.isOnline()) return;
    if (error != null || snapshot == null) {
      deps.logger().log(Level.SEVERE, "加载军需处出售仓库失败:" + player.getUniqueId(), error);
      player.sendMessage(deps.messages().raw("menu.vendor.load-failed"));
      return;
    }
    List<StoredItem> sellable = snapshot.at(InventoryLocation.WAREHOUSE).stream()
        .filter(stored -> deps.itemRegistry().find(stored.itemId())
            .map(ItemDefinition::sellable).orElse(false))
        .toList();
    int totalPages = Math.max(1,
        (sellable.size() + VENDOR_SELL_PAGE_SIZE - 1) / VENDOR_SELL_PAGE_SIZE);
    int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
    vendorSellViews.put(player.getUniqueId(),
        new VendorSellView(sellable, page, totalPages));
    open(player, VENDOR_SELL_MENU, Integer.toString(page + 1),
        Integer.toString(totalPages));
  }

  private void afterVendorBuy(Player player, ItemDefinition definition, int quantity,
      @Nullable Result<VendorTrade> result, @Nullable Throwable error) {
    UUID playerId = player.getUniqueId();
    vendorBusy.remove(playerId);
    if (error != null || result == null) {
      deps.logger().log(Level.SEVERE, "军需处购买失败:" + playerId, error);
      if (player.isOnline()) player.sendMessage(deps.messages().raw("menu.vendor.failed"));
      return;
    }
    if (!result.isSuccess()) {
      if (player.isOnline()) {
        player.sendMessage(deps.messages().raw(
            result.error().filter(MilitaryVendorService.INSUFFICIENT_FUNDS::equals).isPresent()
                ? "menu.vendor.insufficient" : "menu.vendor.failed"));
      }
      return;
    }
    VendorTrade trade = result.value().orElseThrow();
    deps.profiles().find(playerId)
        .ifPresent(profile -> profile.setBalance(trade.balanceAfter()));
    if (!player.isOnline()) return;
    player.sendMessage(deps.messages().raw("menu.vendor.bought",
        Placeholder.unparsed("item", definition.displayName()),
        Placeholder.unparsed("quantity", Integer.toString(quantity)),
        Placeholder.unparsed("price", trade.total().format()),
        Placeholder.unparsed("balance", trade.balanceAfter().format())));
    if (menus.isViewing(player, VENDOR_MENU)) openVendor(player);
  }

  private void afterVendorSell(Player player, int page,
      @Nullable Result<VendorTrade> result, @Nullable Throwable error) {
    UUID playerId = player.getUniqueId();
    vendorBusy.remove(playerId);
    if (error != null || result == null) {
      deps.logger().log(Level.SEVERE, "军需处出售失败:" + playerId, error);
      if (player.isOnline()) player.sendMessage(deps.messages().raw("menu.vendor.failed"));
      return;
    }
    if (!result.isSuccess()) {
      if (player.isOnline()) {
        String failure = result.error().orElse("");
        player.sendMessage(deps.messages().raw(
            failure.equals(MilitaryVendorService.ITEM_NOT_FOUND)
                || failure.equals(MilitaryVendorService.ITEM_NOT_IN_WAREHOUSE)
                ? "menu.vendor.item-changed" : "menu.vendor.failed"));
        if (menus.isViewing(player, VENDOR_SELL_MENU)) openVendorSell(player, page);
      }
      return;
    }
    VendorTrade trade = result.value().orElseThrow();
    deps.profiles().find(playerId)
        .ifPresent(profile -> profile.setBalance(trade.balanceAfter()));
    ItemDefinition definition = deps.itemRegistry().require(trade.itemId());
    if (!player.isOnline()) return;
    player.sendMessage(deps.messages().raw("menu.vendor.sold",
        Placeholder.unparsed("item", definition.displayName()),
        Placeholder.unparsed("quantity", Integer.toString(trade.quantity())),
        Placeholder.unparsed("price", trade.total().format()),
        Placeholder.unparsed("balance", trade.balanceAfter().format())));
    if (menus.isViewing(player, VENDOR_SELL_MENU)) openVendorSell(player, page);
  }

  private void afterToggle(Player player, SettingKey key, boolean newValue,
      @Nullable Throwable error) {
    menuBusy.remove(player.getUniqueId());
    if (error != null) {
      deps.logger().log(Level.SEVERE, "保存玩家设置失败:" + player.getName(), error);
      player.sendMessage(deps.messages().raw("menu.action-failed"));
      return;
    }
    if (!player.isOnline()) return;
    if (key == SettingKey.LOBBY_PLAYER_VISIBILITY) {
      LobbyVisibility.apply(deps.plugin(), deps.settings(), player);
    }
    String nameKey = "menu.settings." + key.name().toLowerCase(Locale.ROOT) + ".name";
    player.sendMessage(deps.messages().raw(newValue ? "settings.toggled-on" : "settings.toggled-off",
        Placeholder.component("setting", deps.messages().raw(nameKey))));
    if (menus.isViewing(player, SETTINGS_MENU)) openSettings(player);
  }

  private void afterEquip(Player player, @Nullable Throwable error) {
    menuBusy.remove(player.getUniqueId());
    if (error != null) {
      deps.logger().log(Level.SEVERE, "保存装扮失败:" + player.getName(), error);
      player.sendMessage(deps.messages().raw("menu.action-failed"));
      return;
    }
    if (!player.isOnline()) return;
    Cosmetic equipped = deps.cosmetics().equipped(player.getUniqueId()).orElse(null);
    CosmeticApplier.apply(player, equipped);
    if (equipped != null) player.sendMessage(deps.messages().raw("cosmetics.equipped",
        Placeholder.unparsed("cosmetic", equipped.displayName())));
    if (menus.isViewing(player, COSMETICS_MENU)) openCosmetics(player);
  }

  private void afterUnequip(Player player, @Nullable Throwable error) {
    menuBusy.remove(player.getUniqueId());
    if (error != null) {
      deps.logger().log(Level.SEVERE, "保存装扮失败:" + player.getName(), error);
      player.sendMessage(deps.messages().raw("menu.action-failed"));
      return;
    }
    if (!player.isOnline()) return;
    CosmeticApplier.apply(player, null);
    player.sendMessage(deps.messages().raw("cosmetics.unequipped"));
    if (menus.isViewing(player, COSMETICS_MENU)) openCosmetics(player);
  }

  private Optional<StoredItem> storedAt(WarehouseView view, int visibleSlot) {
    if (visibleSlot < 0 || visibleSlot >= LOADOUT_SLOTS + WAREHOUSE_PAGE_SIZE) {
      return Optional.empty();
    }
    List<StoredItem> values;
    int index;
    if (visibleSlot < LOADOUT_SLOTS) {
      values = loadoutDisplay(view.snapshot());
      index = visibleSlot;
    } else {
      values = view.snapshot().at(InventoryLocation.WAREHOUSE);
      index = view.page() * WAREHOUSE_PAGE_SIZE + visibleSlot - LOADOUT_SLOTS;
    }
    return index >= 0 && index < values.size()
        ? Optional.ofNullable(values.get(index)) : Optional.empty();
  }

  private LoadoutSlot availableSlot(StoredItem stored, PlayerInventorySnapshot snapshot) {
    List<StoredItem> loadout = snapshot.at(InventoryLocation.LOADOUT);
    if (loadout.size() >= LOADOUT_SLOTS) {
      throw new IllegalStateException("出战栏已满，先放回一件物品");
    }
    ItemCategory category = deps.itemRegistry().require(stored.itemId()).category();
    Set<LoadoutSlot> occupied = new HashSet<>();
    for (StoredItem existing : loadout) {
      if (existing.loadoutSlot() != null) occupied.add(existing.loadoutSlot());
    }
    for (LoadoutSlot slot : NAMED_LOADOUT_SLOTS) {
      if (slot.accepts(category) && !occupied.contains(slot)) return slot;
    }
    boolean hasCompatiblePosition = NAMED_LOADOUT_SLOTS.stream()
        .anyMatch(slot -> slot.accepts(category));
    if (!hasCompatiblePosition) {
      throw new IllegalArgumentException("该物品不能放入当前具名配装位");
    }
    throw new IllegalStateException("该类型的具名配装位已被占用");
  }

  /** Named rows occupy their exact cell; legacy null rows fill the remaining cells unchanged. */
  private static List<StoredItem> loadoutDisplay(PlayerInventorySnapshot snapshot) {
    StoredItem[] cells = new StoredItem[LOADOUT_SLOTS];
    List<StoredItem> compatibilityRows = new ArrayList<>();
    for (StoredItem stored : snapshot.at(InventoryLocation.LOADOUT)) {
      LoadoutSlot slot = stored.loadoutSlot();
      if (slot != null && cells[slot.ordinal()] == null) {
        cells[slot.ordinal()] = stored;
      } else {
        compatibilityRows.add(stored);
      }
    }
    int legacyIndex = 0;
    for (int index = 0; index < cells.length && legacyIndex < compatibilityRows.size(); index++) {
      if (cells[index] == null) cells[index] = compatibilityRows.get(legacyIndex++);
    }
    List<StoredItem> displayed = new ArrayList<>(LOADOUT_SLOTS);
    for (StoredItem stored : cells) displayed.add(stored);
    return displayed;
  }

  private static String slotName(@Nullable LoadoutSlot slot) {
    if (slot == null) return "旧版兼容位";
    return switch (slot) {
      case MAIN_HAND -> "主手";
      case OFF_HAND -> "副手";
      case HEAD -> "头盔";
      case BODY_ARMOR -> "护甲";
      case CHEST_RIG -> "胸挂";
      case BACKPACK -> "背包";
      case QUICK_USE_1 -> "快捷位 1";
      case QUICK_USE_2 -> "快捷位 2";
      case SECURE_CONTAINER -> "安全箱";
    };
  }

  private Optional<StoredItem> vendorSellAt(VendorSellView view, int visibleSlot) {
    if (visibleSlot < 0 || visibleSlot >= VENDOR_SELL_PAGE_SIZE) return Optional.empty();
    int index = view.page() * VENDOR_SELL_PAGE_SIZE + visibleSlot;
    return index < view.items().size() ? Optional.of(view.items().get(index)) : Optional.empty();
  }

  private List<ItemDefinition> vendorOffers() {
    // The military vendor sells combat supplies. Valuable raid finds remain
    // loot/market goods; offering them at a fixed price would erase the
    // extraction economy and make high-tier containers meaningless.
    return deps.itemRegistry().all().stream().filter(definition -> switch (definition.category()) {
      case MELEE_WEAPON, RANGED_WEAPON, PROJECTILE, ARMOR, HELMET, CHEST_RIG, SHIELD,
          BACKPACK, HEALING, FOOD, POTION, CONSUMABLE -> true;
      default -> false;
    }).toList();
  }

  private WarehouseView requireWarehouseView(Player player) {
    requireReady(player);
    WarehouseView view = warehouseViews.get(player.getUniqueId());
    if (view == null) throw new IllegalStateException("请先打开物资仓库");
    return view;
  }

  private VendorSellView requireVendorSellView(Player player) {
    requireReady(player);
    VendorSellView view = vendorSellViews.get(player.getUniqueId());
    if (view == null) throw new IllegalStateException("请先打开军需处出售页面");
    return view;
  }

  private void requireReady(Player player) {
    Preconditions.checkNotNull(player, "player");
    if (!deps.dataGate().isReady(player.getUniqueId())) {
      menus.close(player, "escapefromcraftov_lobby_main", SETTINGS_MENU, COSMETICS_MENU,
          WAREHOUSE_MENU, VENDOR_MENU, VENDOR_SELL_MENU);
      player.sendMessage(deps.messages().raw("queue.data-loading"));
      throw new IllegalStateException("玩家数据仍在加载");
    }
  }

  private void requireNotQueued(Player player) {
    if (deps.dataGate().isRaidMutationLocked(player.getUniqueId())) {
      throw new IllegalStateException("全队行动预检期间不能修改出战物资或余额，请稍候");
    }
    if (deps.matchmaking().ticket(player.getUniqueId()).isPresent()) {
      throw new IllegalStateException("匹配期间不能修改出战物资或交易，请先输入 /leavequeue");
    }
  }

  /** Main-thread signal used to avoid snapshotting an already-running mutation. */
  public boolean hasPendingRaidMutation(UUID playerId) {
    UUID checked = Preconditions.checkNotNull(playerId, "playerId");
    return warehouseBusy.contains(checked) || vendorBusy.contains(checked);
  }

  private void requireViewing(Player player, String menu) {
    if (!menus.isViewing(player, menu)) {
      throw new IllegalStateException("请先打开对应菜单再进行操作");
    }
  }

  private void open(Player player, String menu, String... arguments) {
    if (!menus.open(player, menu, arguments)) {
      throw new IllegalStateException("TrMenu 无法打开大厅菜单: " + menu);
    }
  }

  private static int index(String id, String prefix) {
    try {
      return Integer.parseInt(id.substring(prefix.length()));
    } catch (NumberFormatException error) {
      return -1;
    }
  }

  private static ItemStack item(Material material, Component name, List<Component> lore,
      boolean glint) {
    ItemStack stack = new ItemStack(material);
    stack.editMeta(meta -> {
      meta.itemName(name.decoration(TextDecoration.ITALIC, false));
      meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
      if (glint) meta.setEnchantmentGlintOverride(true);
    });
    return stack;
  }

  private static ItemStack air() {
    return new ItemStack(Material.AIR);
  }

  private static String rarityName(ItemRarity rarity) {
    return switch (rarity) {
      case COMMON -> "普通";
      case UNCOMMON -> "优良";
      case RARE -> "稀有";
      case EPIC -> "史诗";
      case LEGENDARY -> "传说";
      case ARTIFACT -> "机密";
    };
  }

  private static NamedTextColor rarityColor(ItemRarity rarity) {
    return switch (rarity) {
      case COMMON -> NamedTextColor.WHITE;
      case UNCOMMON -> NamedTextColor.GREEN;
      case RARE -> NamedTextColor.AQUA;
      case EPIC -> NamedTextColor.LIGHT_PURPLE;
      case LEGENDARY -> NamedTextColor.GOLD;
      case ARTIFACT -> NamedTextColor.RED;
    };
  }

  private static int maximumVendorQuantity(int stackSize) {
    if (stackSize >= 16) return 16;
    if (stackSize >= 8) return 8;
    return 1;
  }

  @Override
  public void close() throws Exception {
    for (Player player : List.copyOf(org.bukkit.Bukkit.getOnlinePlayers())) {
      menus.close(player, "escapefromcraftov_lobby_main", SETTINGS_MENU, COSMETICS_MENU,
          WAREHOUSE_MENU, VENDOR_MENU, VENDOR_SELL_MENU);
    }
    itemSource.close();
    warehouseViews.clear();
    vendorSellViews.clear();
    warehouseBusy.clear();
    vendorBusy.clear();
    menuBusy.clear();
  }

  private record WarehouseView(PlayerInventorySnapshot snapshot, int page, int totalPages) {}

  private record VendorSellView(List<StoredItem> items, int page, int totalPages) {}
}
