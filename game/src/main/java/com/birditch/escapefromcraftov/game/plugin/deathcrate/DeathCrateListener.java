package com.birditch.escapefromcraftov.game.plugin.deathcrate;

import com.birditch.escapefromcraftov.api.combat.CombatService;
import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.bukkit.TrMenuBridge;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import com.birditch.escapefromcraftov.game.plugin.carry.RaidCarryController;
import com.birditch.escapefromcraftov.game.plugin.ownership.LootAccess;
import com.birditch.escapefromcraftov.game.plugin.ownership.LootClaim;
import com.birditch.escapefromcraftov.game.plugin.ownership.LootOwnershipController;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Shared, take-only virtual inventories represented by protected barrel markers. */
public final class DeathCrateListener implements Listener, AutoCloseable {

  public static final String MENU_ID = "escapefromcraftov_death_crate";

  private static final int PAGE_SIZE = 45;
  private static final double VIEW_DISTANCE_SQUARED = 5.5 * 5.5;
  private static final int SEARCH_RADIUS = 8;
  private static final int[] SEARCH_Y = {0, 1, -1, 2, 3};

  private final CraftovGameService games;
  private final CombatService combat;
  private final InstanceWorldIndex worlds;
  private final CraftovItemStacks itemStacks;
  private final Messages messages;
  private final TrMenuBridge menus;
  private final Logger logger;
  private final RaidCarryController carry;
  private final LootOwnershipController lootOwnership;
  private final AutoCloseable itemSource;
  private final Map<UUID, DeathCrate> crates = new HashMap<>();
  private final Map<MarkerKey, UUID> cratesByMarker = new HashMap<>();
  private final Map<UUID, CrateView> views = new HashMap<>();

  public DeathCrateListener(CraftovGameService games, CombatService combat,
      InstanceWorldIndex worlds,
      CraftovItemStacks itemStacks, Messages messages, TrMenuBridge menus, Logger logger,
      RaidCarryController carry, LootOwnershipController lootOwnership) {
    this.games = Preconditions.checkNotNull(games, "games");
    this.combat = Preconditions.checkNotNull(combat, "combat");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    this.messages = Preconditions.checkNotNull(messages, "messages");
    this.menus = Preconditions.checkNotNull(menus, "menus");
    this.logger = Preconditions.checkNotNull(logger, "logger");
    this.carry = Preconditions.checkNotNull(carry, "carry");
    this.lootOwnership = Preconditions.checkNotNull(lootOwnership, "lootOwnership");
    this.itemSource = menus.registerItemSource("CRAFTOVCOREDEATH", this::menuItem);
  }

  /** Runs after InstanceWorldBridge's HIGHEST duplicate-identity normalization. */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onDeath(PlayerDeathEvent event) {
    Player victim = event.getEntity();
    CraftovGame game = gameOf(victim).orElse(null);
    if (!activeMember(game, victim.getUniqueId())) return;
    List<ItemStack> recognized = event.getDrops().stream()
        .filter(itemStacks::isCraftovItem).toList();
    if (recognized.isEmpty()) return;
    LootClaim claim = lootOwnership.playerDeathClaim(game.id(), victim.getUniqueId());

    normalizeIdentities(recognized);
    Set<ItemStack> captured = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    int crateCount = 0;
    for (int first = 0; first < recognized.size(); first += DeathCrateState.CAPACITY) {
      int end = Math.min(recognized.size(), first + DeathCrateState.CAPACITY);
      List<ItemStack> batch = recognized.subList(first, end);
      Marker marker = placeMarker(victim.getLocation()).orElse(null);
      if (marker == null) {
        logger.warning("阵亡箱附近没有可替换方块，物品保留为原版掉落: "
            + victim.getUniqueId() + "/" + game.id().value());
        victim.sendMessage(messages.raw("death-crate.placement-failed"));
        continue;
      }
      // A physical AI-drop claim must not survive after this death crate's
      // newer player-kill claim supersedes it.
      batch.forEach(lootOwnership::clearProtection);
      UUID crateId = UUID.randomUUID();
      DeathCrate crate = new DeathCrate(crateId, game.id(), victim.getUniqueId(),
          marker, new DeathCrateState<>(batch, ItemStack::clone), claim);
      crates.put(crateId, crate);
      cratesByMarker.put(marker.key(), crateId);
      captured.addAll(batch);
      crateCount++;
    }
    recognized.stream().filter(stack -> !captured.contains(stack))
        .forEach(stack -> lootOwnership.protectDrop(stack, claim));
    if (captured.isEmpty()) return;
    event.getDrops().removeIf(captured::contains);
    victim.sendMessage(messages.raw("death-crate.created",
        Placeholder.unparsed("items", Integer.toString(captured.size())),
        Placeholder.unparsed("crates", Integer.toString(crateCount))));
  }

  /** Claims marker clicks before the NORMAL-priority Loot Zone listener sees a barrel. */
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
  public void onInteract(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
    UUID crateId = cratesByMarker.get(MarkerKey.of(event.getClickedBlock()));
    if (crateId == null) return;
    event.setCancelled(true);
    if (event.getHand() != EquipmentSlot.HAND) return;
    Player player = event.getPlayer();
    DeathCrate crate = crates.get(crateId);
    if (crate == null) return;
    LootAccess access = access(player, crate);
    if (!access.allowed()) {
      deny(player, access);
      return;
    }
    if (access.kind() == LootAccess.Kind.PRIORITY) {
      player.sendActionBar(messages.raw("death-crate.priority-access",
          Placeholder.unparsed("seconds", Integer.toString(access.secondsRemaining()))));
    }
    views.put(player.getUniqueId(), new CrateView(crateId, 0));
    if (!openMenu(player, crate, 0)) {
      views.remove(player.getUniqueId());
      player.sendMessage(messages.raw("death-crate.open-failed"));
    }
  }

  public void take(Player player, int visibleSlot) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkArgument(visibleSlot >= 0 && visibleSlot < PAGE_SIZE,
        "visibleSlot must be from 0.." + (PAGE_SIZE - 1));
    ValidatedView validated = validateView(player);
    if (validated == null) return;
    int slot = validated.view().page() * PAGE_SIZE + visibleSlot;
    if (slot >= DeathCrateState.CAPACITY) return;
    ItemStack item = validated.crate().state().item(slot).orElse(null);
    if (item == null) return;
    if (!carry.canAccept(player, item)) {
      player.sendMessage(messages.raw("death-crate.inventory-full"));
      return;
    }
    ItemStack taken = validated.crate().state().take(slot).orElse(null);
    if (taken == null) return;
    Map<Integer, ItemStack> leftovers = player.getInventory().addItem(taken.clone());
    if (!leftovers.isEmpty()) {
      validated.crate().state().restore(slot, taken);
      throw new IllegalStateException("阵亡箱容量检查与实际写入不一致");
    }
    player.sendActionBar(messages.raw("death-crate.taken"));
    if (validated.crate().state().isEmpty()) {
      player.sendMessage(messages.raw("death-crate.emptied"));
      removeCrate(validated.crate().id());
      return;
    }
    refreshViewers(validated.crate());
  }

  public void page(Player player, int direction) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkArgument(direction == -1 || direction == 1,
        "direction must be -1 or 1");
    ValidatedView validated = validateView(player);
    if (validated == null) return;
    int pages = validated.crate().state().pageCount(PAGE_SIZE);
    int page = Math.max(0, Math.min(pages - 1, validated.view().page() + direction));
    views.put(player.getUniqueId(), new CrateView(validated.crate().id(), page));
    if (!openMenu(player, validated.crate(), page)) {
      views.remove(player.getUniqueId());
      player.sendMessage(messages.raw("death-crate.open-failed"));
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInventoryClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player) || !menus.isViewing(player, MENU_ID)) {
      return;
    }
    // TrMenu dispatches icon actions while this cancellation prevents cursor,
    // shift-click and number-key transfers into the virtual crate.
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInventoryDrag(InventoryDragEvent event) {
    if (event.getWhoClicked() instanceof Player player && menus.isViewing(player, MENU_ID)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onMove(PlayerMoveEvent event) {
    CrateView view = views.get(event.getPlayer().getUniqueId());
    if (view == null) return;
    DeathCrate crate = crates.get(view.crateId());
    if (crate != null && closeEnough(event.getTo(), crate.marker())) return;
    views.remove(event.getPlayer().getUniqueId());
    menus.close(event.getPlayer(), MENU_ID);
    event.getPlayer().sendActionBar(messages.raw("death-crate.too-far"));
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    views.remove(event.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
  public void onBreak(BlockBreakEvent event) {
    if (cratesByMarker.containsKey(MarkerKey.of(event.getBlock()))) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityExplode(EntityExplodeEvent event) {
    event.blockList().removeIf(block -> cratesByMarker.containsKey(MarkerKey.of(block)));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockExplode(BlockExplodeEvent event) {
    event.blockList().removeIf(block -> cratesByMarker.containsKey(MarkerKey.of(block)));
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onPistonExtend(BlockPistonExtendEvent event) {
    if (event.getBlocks().stream().anyMatch(block ->
        cratesByMarker.containsKey(MarkerKey.of(block)))) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onPistonRetract(BlockPistonRetractEvent event) {
    if (event.getBlocks().stream().anyMatch(block ->
        cratesByMarker.containsKey(MarkerKey.of(block)))) event.setCancelled(true);
  }

  @Subscribe
  public void onCraftovEnded(CraftovEndedEvent event) {
    List<UUID> ended = crates.values().stream()
        .filter(crate -> crate.gameId().equals(event.gameId()))
        .map(DeathCrate::id).toList();
    ended.forEach(this::removeCrate);
    lootOwnership.clearGame(event.gameId());
  }

  public int activeCrateCount() {
    return crates.size();
  }

  private ItemStack menuItem(Player player, String rawId) {
    CrateView view = views.get(player.getUniqueId());
    DeathCrate crate = view == null ? null : crates.get(view.crateId());
    if (crate == null) return emptySlot();
    String id = rawId.toLowerCase(Locale.ROOT);
    if (!id.startsWith("slot-")) return emptySlot();
    int visibleSlot;
    try {
      visibleSlot = Integer.parseInt(id.substring(5));
    } catch (NumberFormatException ignored) {
      return emptySlot();
    }
    if (visibleSlot < 0 || visibleSlot >= PAGE_SIZE) return emptySlot();
    int slot = view.page() * PAGE_SIZE + visibleSlot;
    if (slot >= DeathCrateState.CAPACITY) return emptySlot();
    ItemStack display = crate.state().item(slot).orElse(null);
    if (display == null) return emptySlot();
    display.editMeta(meta -> {
      List<Component> lore = meta.lore() == null
          ? new ArrayList<>() : new ArrayList<>(meta.lore());
      lore.add(Component.empty());
      lore.add(Component.text("点击取出到本局背包", NamedTextColor.GREEN)
          .decoration(TextDecoration.ITALIC, false));
      LootAccess access = access(player, crate);
      lore.add((switch (access.kind()) {
        case PRIORITY -> Component.text(
            "你的小队优先 · " + access.secondsRemaining() + " 秒", NamedTextColor.GOLD);
        case PUBLIC -> Component.text("本局公开物资", NamedTextColor.GRAY);
        case PROTECTED, NOT_ACCESSIBLE -> Component.text("当前无权取出", NamedTextColor.RED);
      }).decoration(TextDecoration.ITALIC, false));
      meta.lore(lore);
    });
    return display;
  }

  private ValidatedView validateView(Player player) {
    if (!menus.isViewing(player, MENU_ID)) {
      player.sendMessage(messages.raw("death-crate.open-first"));
      return null;
    }
    CrateView view = views.get(player.getUniqueId());
    DeathCrate crate = view == null ? null : crates.get(view.crateId());
    LootAccess access = crate == null ? new LootAccess(LootAccess.Kind.NOT_ACCESSIBLE, 0)
        : access(player, crate);
    if (crate == null || !access.allowed()) {
      views.remove(player.getUniqueId());
      menus.close(player, MENU_ID);
      deny(player, access);
      return null;
    }
    if (!closeEnough(player.getLocation(), crate.marker())) {
      views.remove(player.getUniqueId());
      menus.close(player, MENU_ID);
      player.sendMessage(messages.raw("death-crate.too-far"));
      return null;
    }
    return new ValidatedView(view, crate);
  }

  private LootAccess access(Player player, DeathCrate crate) {
    CraftovGameId gameId = worlds.gameByWorld(player.getWorld().getName()).orElse(null);
    if (!crate.gameId().equals(gameId)) {
      return new LootAccess(LootAccess.Kind.NOT_ACCESSIBLE, 0);
    }
    CraftovGame game = games.find(crate.gameId()).orElse(null);
    LootAccess claimAccess = game == null
        ? new LootAccess(LootAccess.Kind.NOT_ACCESSIBLE, 0)
        : lootOwnership.access(game, player.getUniqueId(), crate.claim());
    return DeathCrateAccessPolicy.filter(combat.stateOf(player.getUniqueId()), claimAccess);
  }

  private boolean canOpen(Player player, DeathCrate crate) {
    return access(player, crate).allowed();
  }

  private void deny(Player player, LootAccess access) {
    if (access.kind() == LootAccess.Kind.PROTECTED) {
      player.sendMessage(messages.raw("death-crate.protected",
          Placeholder.unparsed("seconds", Integer.toString(access.secondsRemaining()))));
      return;
    }
    player.sendMessage(messages.raw("death-crate.not-accessible"));
  }

  private Optional<CraftovGame> gameOf(Player player) {
    return worlds.gameByWorld(player.getWorld().getName()).flatMap(games::find);
  }

  private void normalizeIdentities(List<ItemStack> stacks) {
    Set<UUID> identities = new HashSet<>();
    for (ItemStack stack : stacks) {
      var tagged = itemStacks.read(stack).orElse(null);
      if (tagged == null || identities.add(tagged.instanceId())) continue;
      UUID identity;
      do {
        identity = itemStacks.assignFreshIdentity(stack).orElseThrow();
      } while (!identities.add(identity));
    }
  }

  private Optional<Marker> placeMarker(Location death) {
    World world = death.getWorld();
    int originX = death.getBlockX();
    int originY = death.getBlockY();
    int originZ = death.getBlockZ();
    for (int radius = 0; radius <= SEARCH_RADIUS; radius++) {
      for (int dy : SEARCH_Y) {
        for (int dx = -radius; dx <= radius; dx++) {
          for (int dz = -radius; dz <= radius; dz++) {
            if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
            int y = originY + dy;
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
            Block block = world.getBlockAt(originX + dx, y, originZ + dz);
            MarkerKey key = MarkerKey.of(block);
            if (cratesByMarker.containsKey(key) || !replaceable(block)) continue;
            BlockData original = block.getBlockData().clone();
            block.setType(Material.BARREL, false);
            return Optional.of(new Marker(key, original));
          }
        }
      }
    }
    return Optional.empty();
  }

  private void refreshViewers(DeathCrate crate) {
    int pages = crate.state().pageCount(PAGE_SIZE);
    List<Map.Entry<UUID, CrateView>> current = views.entrySet().stream()
        .filter(entry -> entry.getValue().crateId().equals(crate.id())).toList();
    for (Map.Entry<UUID, CrateView> entry : current) {
      Player player = Bukkit.getPlayer(entry.getKey());
      if (player == null || !menus.isViewing(player, MENU_ID) || !canOpen(player, crate)) {
        views.remove(entry.getKey());
        continue;
      }
      int page = Math.min(entry.getValue().page(), pages - 1);
      views.put(entry.getKey(), new CrateView(crate.id(), page));
      if (!openMenu(player, crate, page)) views.remove(entry.getKey());
    }
  }

  private boolean openMenu(Player player, DeathCrate crate, int page) {
    int pages = crate.state().pageCount(PAGE_SIZE);
    return menus.open(player, MENU_ID, Integer.toString(page + 1), Integer.toString(pages),
        Integer.toString(crate.state().itemCount()));
  }

  private void removeCrate(UUID crateId) {
    DeathCrate removed = crates.remove(crateId);
    if (removed == null) return;
    cratesByMarker.remove(removed.marker().key(), crateId);
    List<UUID> viewers = views.entrySet().stream()
        .filter(entry -> entry.getValue().crateId().equals(crateId))
        .map(Map.Entry::getKey).toList();
    for (UUID viewerId : viewers) {
      views.remove(viewerId);
      Player player = Bukkit.getPlayer(viewerId);
      if (player != null && menus.isViewing(player, MENU_ID)) menus.close(player, MENU_ID);
    }
    restore(removed.marker());
  }

  private static void restore(Marker marker) {
    World world = Bukkit.getWorld(marker.key().worldId());
    if (world == null) return;
    Block block = world.getBlockAt(marker.key().x(), marker.key().y(), marker.key().z());
    block.setBlockData(marker.original().clone(), false);
  }

  private static boolean replaceable(Block block) {
    Material type = block.getType();
    return type.isAir() || switch (type) {
      case SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, DEAD_BUSH, SNOW,
          FIRE, SOUL_FIRE, VINE, GLOW_LICHEN -> true;
      default -> false;
    };
  }

  private static boolean activeMember(CraftovGame game, UUID playerId) {
    return game != null && game.state() == CraftovGameState.RUNNING
        && game.players().stream().anyMatch(player ->
            player.playerId().equals(playerId) && !player.extracted());
  }

  private static boolean closeEnough(Location location, Marker marker) {
    if (location == null || !location.getWorld().getUID().equals(marker.key().worldId())) {
      return false;
    }
    double x = marker.key().x() + 0.5;
    double y = marker.key().y() + 0.5;
    double z = marker.key().z() + 0.5;
    double dx = location.getX() - x;
    double dy = location.getY() - y;
    double dz = location.getZ() - z;
    return dx * dx + dy * dy + dz * dz <= VIEW_DISTANCE_SQUARED;
  }

  private static ItemStack emptySlot() {
    ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
    item.editMeta(meta -> meta.itemName(Component.text("空", NamedTextColor.DARK_GRAY)
        .decoration(TextDecoration.ITALIC, false)));
    return item;
  }

  @Override
  public void close() throws Exception {
    for (UUID crateId : List.copyOf(crates.keySet())) removeCrate(crateId);
    views.clear();
    itemSource.close();
  }

  private record DeathCrate(UUID id, CraftovGameId gameId, UUID ownerId,
                            Marker marker, DeathCrateState<ItemStack> state, LootClaim claim) {

    private DeathCrate {
      Preconditions.checkNotNull(id, "id");
      Preconditions.checkNotNull(gameId, "gameId");
      Preconditions.checkNotNull(ownerId, "ownerId");
      Preconditions.checkNotNull(marker, "marker");
      Preconditions.checkNotNull(state, "state");
      Preconditions.checkNotNull(claim, "claim");
    }
  }

  private record CrateView(UUID crateId, int page) {}

  private record ValidatedView(CrateView view, DeathCrate crate) {}

  private record Marker(MarkerKey key, BlockData original) {}

  private record MarkerKey(UUID worldId, int x, int y, int z) {

    private static MarkerKey of(Block block) {
      return new MarkerKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
  }
}
