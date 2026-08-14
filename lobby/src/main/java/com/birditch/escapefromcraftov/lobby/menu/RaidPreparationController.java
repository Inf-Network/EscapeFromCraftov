package com.birditch.escapefromcraftov.lobby.menu;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventoryService;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventorySnapshot;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.network.InstanceDirectory;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.bukkit.MapNames;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.bukkit.TrMenuBridge;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.lobby.LobbyConfig;
import com.birditch.escapefromcraftov.lobby.LobbyActionCatalog;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * TrMenu-backed raid preparation screen. This controller owns only the current
 * UI selection and its inventory summary; {@code LobbyCommand} remains the
 * authority for map, party, permission and matchmaking validation.
 */
public final class RaidPreparationController implements Listener, AutoCloseable {

  public static final String MENU_ID = "escapefromcraftov_lobby_preparation";

  private final PlayerInventoryService inventories;
  private final ItemRegistry itemRegistry;
  private final InstanceDirectory directory;
  private final CraftovScheduler scheduler;
  private final LobbyConfig config;
  private final Messages messages;
  private final TrMenuBridge menus;
  private final Logger logger;
  private final AutoCloseable itemSource;
  private final Map<UUID, PreparationView> views = new HashMap<>();
  private final Map<UUID, Long> loadVersions = new HashMap<>();

  public RaidPreparationController(PlayerInventoryService inventories, ItemRegistry itemRegistry,
      InstanceDirectory directory, CraftovScheduler scheduler, LobbyConfig config, Messages messages,
      TrMenuBridge menus, Logger logger) {
    this.inventories = Preconditions.checkNotNull(inventories, "inventories");
    this.itemRegistry = Preconditions.checkNotNull(itemRegistry, "itemRegistry");
    this.directory = Preconditions.checkNotNull(directory, "directory");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.config = Preconditions.checkNotNull(config, "config");
    this.messages = Preconditions.checkNotNull(messages, "messages");
    this.menus = Preconditions.checkNotNull(menus, "menus");
    this.logger = Preconditions.checkNotNull(logger, "logger");
    this.itemSource = menus.registerItemSource("CRAFTOVPREPARATION", this::menuItem);
  }

  /** Loads the authoritative LOADOUT rows before displaying their summary. */
  public void open(Player player) {
    Preconditions.checkNotNull(player, "player");
    UUID playerId = player.getUniqueId();
    long version = loadVersions.merge(playerId, 1L, Long::sum);
    Selection selection = Optional.ofNullable(views.get(playerId))
        .map(PreparationView::selection).orElse(defaultSelection());
    CompletableFuture<LobbyActionCatalog> catalog = loadCatalog();
    inventories.load(playerId).thenCombine(catalog, LoadedPreparation::new)
        .whenComplete((loaded, error) -> scheduler.runSync(() -> afterInventoryLoad(
            player, version, selection, loaded == null ? null : loaded.inventory(),
            loaded == null ? LobbyActionCatalog.empty() : loaded.catalog(), error)));
  }

  public void cycleMap(Player player) {
    PreparationView view = requireView(player);
    List<CraftovMapId> maps = view.catalog().availableMaps(config);
    CraftovMapId nextMap = next(maps, view.selection().mapId());
    replaceAndRefresh(player, view.withSelection(
        normalizeSelection(new Selection(nextMap, view.selection().mode(),
            view.selection().difficulty()), view.catalog())));
  }

  /** Canonical catalog path; on old snapshots it retains the legacy mode cycle. */
  public void cycleTier(Player player) {
    PreparationView view = requireView(player);
    List<RaidActionProfile> tiers = view.catalog().tiers(view.selection().mapId());
    if (tiers.isEmpty()) {
      if (view.catalog().requiresCanonical(view.selection().mapId())) {
        player.sendMessage(messages.raw("menu.preparation.tier.unavailable"));
        return;
      }
      cycleLegacyMode(player, view);
      return;
    }
    RaidActionProfile current = view.selection().tierId() == null ? null
        : view.catalog().find(view.selection().mapId(), view.selection().tierId()).orElse(null);
    RaidActionProfile selected = next(tiers, current);
    RaidActionProfile.LegacyAlias alias = LobbyActionCatalog.legacyAlias(selected);
    replaceAndRefresh(player, view.withSelection(new Selection(view.selection().mapId(),
        alias.mode(), alias.difficulty(), selected.id())));
  }

  public void cycleMode(Player player) {
    PreparationView view = requireView(player);
    if (view.catalog().requiresCanonical(view.selection().mapId())) {
      cycleTier(player);
      return;
    }
    cycleLegacyMode(player, view);
  }

  private void cycleLegacyMode(Player player, PreparationView view) {
    replaceAndRefresh(player, view.withSelection(new Selection(
        view.selection().mapId(),
        next(List.of(RaidMode.values()), view.selection().mode()),
        view.selection().difficulty())));
  }

  public void cycleDifficulty(Player player) {
    PreparationView view = requireView(player);
    if (view.catalog().requiresCanonical(view.selection().mapId())) {
      player.sendMessage(messages.raw(view.catalog().tiers(view.selection().mapId()).isEmpty()
          ? "menu.preparation.tier.unavailable"
          : "menu.preparation.tier.merged-hint"));
      return;
    }
    replaceAndRefresh(player, view.withSelection(new Selection(
        view.selection().mapId(), view.selection().mode(),
        next(List.of(RaidDifficulty.values()), view.selection().difficulty()))));
  }

  /**
   * Returns the selected queue parameters. An empty loadout requires one extra
   * click, making an intentional zero-to-hero run possible without silently
   * sending a new player in empty-handed.
   */
  public Optional<Selection> confirmStart(Player player) {
    PreparationView view = requireView(player);
    if (view.catalog().requiresCanonical(view.selection().mapId())
        && selectedTier(view) == null) {
      player.sendMessage(messages.raw("menu.preparation.tier.unavailable"));
      return Optional.empty();
    }
    if (view.itemCount() == 0 && !view.emptyLoadoutConfirmed()) {
      replaceAndRefresh(player, view.confirmEmptyLoadout());
      player.sendMessage(messages.raw("menu.preparation.empty-confirm"));
      return Optional.empty();
    }
    menus.close(player, MENU_ID);
    return Optional.of(view.selection());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    menus.close(player, MENU_ID);
    views.remove(player.getUniqueId());
    loadVersions.remove(player.getUniqueId());
  }

  private void afterInventoryLoad(Player player, long version, Selection selection,
      @Nullable PlayerInventorySnapshot snapshot, LobbyActionCatalog catalog,
      @Nullable Throwable error) {
    UUID playerId = player.getUniqueId();
    if (!player.isOnline() || loadVersions.getOrDefault(playerId, 0L) != version) return;
    if (error != null || snapshot == null) {
      logger.log(Level.SEVERE, "加载行动准备出战栏失败:" + playerId, error);
      player.sendMessage(messages.raw("menu.preparation.load-failed"));
      return;
    }
    List<StoredItem> loadout = snapshot.at(InventoryLocation.LOADOUT);
    views.put(playerId, new PreparationView(normalizeSelection(selection, catalog), catalog,
        loadout.size(), totalQuantity(loadout), totalValue(loadout),
        totalWeightGrams(loadout), false));
    openMenu(player);
  }

  private ItemStack menuItem(Player player, String rawId) {
    PreparationView view = views.get(player.getUniqueId());
    if (view == null) return new ItemStack(Material.AIR);
    return switch (rawId.toLowerCase(Locale.ROOT)) {
      case "map" -> mapItem(view);
      case "mode", "tier" -> modeItem(view);
      case "difficulty" -> difficultyItem(view);
      case "loadout" -> loadoutItem(view);
      case "start" -> startItem(view);
      default -> new ItemStack(Material.AIR);
    };
  }

  private ItemStack mapItem(PreparationView view) {
    Component map = MapNames.display(messages, view.selection().mapId());
    return item(Material.FILLED_MAP, messages.raw("menu.preparation.map.name"),
        messages.lines("menu.preparation.map.lore", Placeholder.component("map", map)), true);
  }

  private ItemStack modeItem(PreparationView view) {
    RaidActionProfile profile = selectedTier(view);
    if (profile != null) {
      return item(profile.id().value().equals("top_secret")
              ? Material.NETHERITE_SWORD : Material.DIAMOND_SWORD,
          messages.raw("menu.preparation.tier.name"),
          messages.lines("menu.preparation.tier.lore",
              Placeholder.unparsed("tier", profile.displayName()),
              Placeholder.unparsed("tier_id", profile.id().value())), true);
    }
    if (view.catalog().requiresCanonical(view.selection().mapId())) {
      return item(Material.BARRIER, messages.raw("menu.preparation.tier.unavailable-name"),
          messages.lines("menu.preparation.tier.unavailable-lore"), false);
    }
    RaidMode mode = view.selection().mode();
    Component modeName = messages.raw("menu.preparation.mode." + mode.id());
    return item(mode == RaidMode.NORMAL ? Material.IRON_SWORD : Material.NETHERITE_SWORD,
        messages.raw("menu.preparation.mode.name"),
        messages.lines("menu.preparation.mode.lore", Placeholder.component("mode", modeName)),
        mode == RaidMode.CONFIDENTIAL);
  }

  private ItemStack difficultyItem(PreparationView view) {
    RaidActionProfile profile = selectedTier(view);
    if (profile != null) {
      return item(Material.COMPASS, messages.raw("menu.preparation.tier.details-name"),
          messages.lines("menu.preparation.tier.details-lore",
              Placeholder.unparsed("tier", profile.displayName()),
              Placeholder.unparsed("minimum", Money.of(profile.minimumLoadoutValue()).format()),
              Placeholder.unparsed("fee", Money.of(profile.entryFee()).format())), false);
    }
    if (view.catalog().requiresCanonical(view.selection().mapId())) {
      return item(Material.BARRIER, messages.raw("menu.preparation.tier.unavailable-name"),
          messages.lines("menu.preparation.tier.unavailable-lore"), false);
    }
    RaidDifficulty difficulty = view.selection().difficulty();
    Material material = switch (difficulty) {
      case EASY -> Material.LIME_DYE;
      case NORMAL -> Material.YELLOW_DYE;
      case HARD -> Material.RED_DYE;
    };
    Component difficultyName = messages.raw("menu.preparation.difficulty." + difficulty.id());
    return item(material, messages.raw("menu.preparation.difficulty.name"),
        messages.lines("menu.preparation.difficulty.lore",
            Placeholder.component("difficulty", difficultyName)), difficulty == RaidDifficulty.HARD);
  }

  private ItemStack loadoutItem(PreparationView view) {
    String stateKey = view.itemCount() == 0
        ? "menu.preparation.loadout.empty" : "menu.preparation.loadout.ready";
    return item(view.itemCount() == 0 ? Material.ENDER_CHEST : Material.CHEST,
        messages.raw("menu.preparation.loadout.name"),
        messages.lines("menu.preparation.loadout.lore",
            Placeholder.unparsed("stacks", Integer.toString(view.stackCount())),
            Placeholder.unparsed("items", Integer.toString(view.itemCount())),
            Placeholder.unparsed("value", view.totalValue().format()),
            Placeholder.unparsed("weight", formatWeight(view.totalWeightGrams())),
            Placeholder.component("state", messages.raw(stateKey))), view.itemCount() > 0);
  }

  private ItemStack startItem(PreparationView view) {
    boolean empty = view.itemCount() == 0;
    RaidActionProfile profile = selectedTier(view);
    boolean unavailable = view.catalog().requiresCanonical(view.selection().mapId())
        && profile == null;
    String key = unavailable ? "menu.preparation.start.tier-unavailable" : profile != null
        ? (empty
            ? (view.emptyLoadoutConfirmed()
                ? "menu.preparation.start.tier-empty-confirmed"
                : "menu.preparation.start.tier-empty")
            : "menu.preparation.start.tier-ready")
        : empty
        ? (view.emptyLoadoutConfirmed()
            ? "menu.preparation.start.empty-confirmed" : "menu.preparation.start.empty")
        : "menu.preparation.start.ready";
    return item(unavailable ? Material.BARRIER : empty ? Material.FIRE_CHARGE : Material.EMERALD,
        messages.raw("menu.preparation.start.name"),
        messages.lines(key,
            Placeholder.component("map", MapNames.display(messages, view.selection().mapId())),
            Placeholder.component("mode", messages.raw(
                "menu.preparation.mode." + view.selection().mode().id())),
            Placeholder.component("difficulty", messages.raw(
                "menu.preparation.difficulty." + view.selection().difficulty().id())),
            Placeholder.unparsed("tier", profile == null ? "" : profile.displayName())),
        !empty && !unavailable);
  }

  private @Nullable RaidActionProfile selectedTier(PreparationView view) {
    RaidTierId tierId = view.selection().tierId();
    return tierId == null ? null
        : view.catalog().find(view.selection().mapId(), tierId).orElse(null);
  }

  private PreparationView requireView(Player player) {
    Preconditions.checkNotNull(player, "player");
    if (!menus.isViewing(player, MENU_ID)) {
      throw new IllegalStateException("请先打开行动准备菜单再进行操作");
    }
    PreparationView view = views.get(player.getUniqueId());
    if (view == null) throw new IllegalStateException("行动准备数据仍在加载");
    return view;
  }

  private void replaceAndRefresh(Player player, PreparationView view) {
    views.put(player.getUniqueId(), view);
    openMenu(player);
  }

  private void openMenu(Player player) {
    if (!menus.open(player, MENU_ID)) {
      throw new IllegalStateException("TrMenu 无法打开行动准备菜单");
    }
  }

  private Selection defaultSelection() {
    return new Selection(config.defaultMap(), RaidMode.NORMAL, RaidDifficulty.NORMAL);
  }

  private CompletableFuture<LobbyActionCatalog> loadCatalog() {
    try {
      return directory.activeServers().orTimeout(750, TimeUnit.MILLISECONDS)
          .thenApply(LobbyActionCatalog::fromServers);
    } catch (RuntimeException error) {
      return CompletableFuture.failedFuture(error);
    }
  }

  private static Selection normalizeSelection(Selection selection,
      LobbyActionCatalog catalog) {
    List<RaidActionProfile> profiles = catalog.tiers(selection.mapId());
    if (profiles.isEmpty()) {
      return new Selection(selection.mapId(), selection.mode(), selection.difficulty());
    }
    RaidActionProfile selected = selection.tierId() == null ? null
        : catalog.find(selection.mapId(), selection.tierId()).orElse(null);
    if (selected == null) selected = profiles.getFirst();
    RaidActionProfile.LegacyAlias alias = LobbyActionCatalog.legacyAlias(selected);
    return new Selection(selection.mapId(), alias.mode(), alias.difficulty(), selected.id());
  }

  private Money totalValue(List<StoredItem> loadout) {
    long units = 0;
    for (StoredItem stored : loadout) {
      ItemDefinition definition = itemRegistry.find(stored.itemId()).orElse(null);
      if (definition == null) continue;
      units = Math.addExact(units,
          Math.multiplyExact(definition.basePrice().units(), stored.quantity()));
    }
    return Money.of(units);
  }

  private static int totalQuantity(List<StoredItem> loadout) {
    int total = 0;
    for (StoredItem stored : loadout) total = Math.addExact(total, stored.quantity());
    return total;
  }

  private long totalWeightGrams(List<StoredItem> loadout) {
    long total = 0;
    for (StoredItem stored : loadout) {
      ItemDefinition definition = itemRegistry.find(stored.itemId()).orElse(null);
      if (definition == null) continue;
      total = Math.addExact(total,
          Math.multiplyExact((long) definition.weightGrams(), stored.quantity()));
    }
    return total;
  }

  private static String formatWeight(long grams) {
    if (grams < 1_000) return grams + " g";
    return String.format(Locale.ROOT, "%.2f kg", grams / 1_000.0);
  }

  private static <T> T next(List<T> values, T current) {
    Preconditions.checkArgument(!values.isEmpty(), "selection values must not be empty");
    int index = values.indexOf(current);
    return values.get(index < 0 || index + 1 == values.size() ? 0 : index + 1);
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

  @Override
  public void close() throws Exception {
    for (Player player : List.copyOf(org.bukkit.Bukkit.getOnlinePlayers())) {
      menus.close(player, MENU_ID);
    }
    itemSource.close();
    views.clear();
    loadVersions.clear();
  }

  public record Selection(
      CraftovMapId mapId, RaidMode mode, RaidDifficulty difficulty,
      @Nullable RaidTierId tierId) {

    public Selection(CraftovMapId mapId, RaidMode mode, RaidDifficulty difficulty) {
      this(mapId, mode, difficulty, null);
    }

    public Selection {
      Preconditions.checkNotNull(mapId, "mapId");
      Preconditions.checkNotNull(mode, "mode");
      Preconditions.checkNotNull(difficulty, "difficulty");
    }
  }

  private record PreparationView(
      Selection selection,
      LobbyActionCatalog catalog,
      int stackCount,
      int itemCount,
      Money totalValue,
      long totalWeightGrams,
      boolean emptyLoadoutConfirmed) {

    private PreparationView withSelection(Selection replacement) {
      return new PreparationView(replacement, catalog, stackCount, itemCount, totalValue,
          totalWeightGrams, false);
    }

    private PreparationView confirmEmptyLoadout() {
      return new PreparationView(selection, catalog, stackCount, itemCount, totalValue,
          totalWeightGrams, true);
    }
  }

  private record LoadedPreparation(PlayerInventorySnapshot inventory,
                                   LobbyActionCatalog catalog) {}
}
