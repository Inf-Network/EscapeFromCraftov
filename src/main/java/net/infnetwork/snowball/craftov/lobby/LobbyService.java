package net.infnetwork.snowball.craftov.lobby;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.gui.UiItems;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.map.WorldService;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.profile.ProfileService;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The operations hangar: join flow, loadout persistence, stations and the entrance to the private range. */
public final class LobbyService implements Listener {

    public static final String WORLD = "craftov_lobby";
    static final int[][] STATIONS_XZ = {{0, -8}, {-8, -4}, {-8, 4}, {0, 8}, {8, 4}, {8, -2}, {-14, 0}, {14, 6}};
    private static final String[][] STATIONS = {
            {"deploy", "开始行动", "选择地图 · 难度 · 单三排", "craftov:ui/icon_deploy"},
            {"stash", "仓库", "整理物资 · 配装", "craftov:ui/icon_stash"},
            {"trader", "商人", "购买武器装备 · 回收物资", "craftov:ui/icon_trader"},
            {"market", "交易行", "玩家寄售 · 系统收购", "craftov:ui/icon_market"},
            {"operator", "干员", "选择干员与技能", "craftov:ui/icon_operator"},
            {"escort", "护航大厅", "发布或接取护航委托", "craftov:ui/icon_escort"},
            {"collection", "收藏室", "收藏品图鉴", "craftov:ui/icon_collection"},
            {"rank", "排位 · 战绩", "排行榜 · 行动报告", "craftov:ui/icon_rank"},
    };

    /** The range entrance's spot in the hangar, and the weapon rack's on every range plot (shifted). */
    static final double RACK_X = 9.5;
    static final double RACK_Z = -4.4;

    private final CraftovPlugin plugin;
    private @Nullable World world;
    private @Nullable TextDisplay leaderboard;
    /**
     * Full light for the lobby's labels. The pack's text font is a TrueType font, and the client lights
     * TrueType glyphs on a text display by the light where it stands (bitmap glyphs it draws at full
     * brightness), so under the hall's lamps the labels came out a dim brown (#163).
     */
    static final Display.Brightness LABEL_LIGHT = new Display.Brightness(15, 15);
    private final Map<UUID, Long> lastMenuClick = new HashMap<>();

    public LobbyService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        File folder = new File(Bukkit.getWorldContainer(), WORLD);
        boolean fresh = !folder.exists();
        world = WorldService.creator(WORLD).createWorld();
        if (world == null) {
            plugin.getLogger().severe("Could not create lobby world");
            return;
        }
        WorldService.applyRules(world, false);
        world.setTime(6000);
        world.setSpawnLocation(0, LobbyBuilder.Y + 1, 0);
        // Stations and the range entrance are non-persistent entities, which the server discards when
        // their chunk unloads (an empty server); keep the whole hangar loaded so they always exist.
        int r = LobbyBuilder.R + 2;
        for (int cx = Math.floorDiv(-r, 16); cx <= Math.floorDiv(r, 16); cx++) {
            for (int cz = Math.floorDiv(-r, 16); cz <= Math.floorDiv(r, 16); cz++) {
                world.getChunkAt(cx, cz).addPluginChunkTicket(plugin);
            }
        }
        if (fresh || !new File(folder, "craftov_lobby.built").exists()) {
            LobbyBuilder.build(world);
            try {
                new File(folder, "craftov_lobby.built").createNewFile();
            } catch (java.io.IOException ignored) {
            }
        }
        if (LobbyBuilder.hasLane(world)) {
            // A hangar built before #164 still has the old firing lane in its corner: plain hangar now.
            LobbyBuilder.clearLane(world);
        }
        spawnStations();
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshLeaderboard, 40L, 20L * 30);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickPlayers, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickRange, 10L, 10L);
    }

    public @Nullable World world() {
        return world;
    }

    public boolean isLobby(World w) {
        return w != null && w.equals(world);
    }

    public Location spawn() {
        World w = world != null ? world : Bukkit.getWorlds().get(0);
        return new Location(w, 0.5, LobbyBuilder.Y + 1, 12.5, 180f, 0f);
    }

    /** True while the player is in their private shooting range (see {@link RangeService}). */
    public boolean inRange(Player player) {
        return plugin.range().isIn(player);
    }

    public boolean isTarget(Entity entity) {
        return entity.getPersistentDataContainer().has(Keys.key("target"));
    }

    // ------------------------------------------------------------------ stations

    private void spawnStations() {
        if (world == null) {
            return;
        }
        // Left over from before a reload, and the dummies older builds stood in the hangar (#164).
        for (Entity entity : world.getEntities()) {
            if (entity.getPersistentDataContainer().has(Keys.key("station")) || isTarget(entity)) {
                entity.remove();
            }
        }
        for (int i = 0; i < STATIONS.length && i < STATIONS_XZ.length; i++) {
            String[] s = STATIONS[i];
            Location base = new Location(world, STATIONS_XZ[i][0] + 0.5, LobbyBuilder.Y + 2, STATIONS_XZ[i][1] + 0.5);
            ItemStack icon = UiItems.button(s[3], s[1], List.of(), "station");
            world.spawn(base.clone().add(0, 0.6, 0), ItemDisplay.class, d -> {
                d.setItemStack(icon);
                d.setBillboard(Display.Billboard.VERTICAL);
                d.setPersistent(false);
                d.getPersistentDataContainer().set(Keys.key("station"), PersistentDataType.STRING, s[0]);
            });
            world.spawn(base.clone().add(0, 1.55, 0), TextDisplay.class, d -> {
                d.text(Text.mm("<b><text>" + s[1] + "</b>\n<muted>" + s[2]));
                d.setBillboard(Display.Billboard.CENTER);
                d.setBrightness(LABEL_LIGHT);
                d.setPersistent(false);
                d.setBackgroundColor(org.bukkit.Color.fromARGB(110, 10, 12, 16));
                d.getPersistentDataContainer().set(Keys.key("station"), PersistentDataType.STRING, s[0]);
            });
            world.spawn(base.clone().add(0, -1, 0), Interaction.class, it -> {
                it.setInteractionWidth(1.6f);
                it.setInteractionHeight(3.2f);
                it.setPersistent(false);
                it.getPersistentDataContainer().set(Keys.key("station"), PersistentDataType.STRING, s[0]);
            });
        }
        leaderboard = world.spawn(new Location(world, 14.5, LobbyBuilder.Y + 3.2, 8.5), TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setBrightness(LABEL_LIGHT);
            d.setPersistent(false);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(140, 10, 12, 16));
            d.getPersistentDataContainer().set(Keys.key("station"), PersistentDataType.STRING, "rank_board");
        });
        // The entrance to the private range, where its weapon rack stands on every plot.
        Location rack = new Location(world, RACK_X, LobbyBuilder.Y + 1, RACK_Z);
        world.spawn(rack.clone().add(0, 1.2, 0), ItemDisplay.class, d -> {
            d.setItemStack(UiItems.button("craftov:ui/tab_weapon", "靶场武器架", List.of(), "station"));
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setPersistent(false);
            d.getPersistentDataContainer().set(Keys.key("station"), PersistentDataType.STRING, "range_rack");
        });
        world.spawn(rack.clone().add(0, 2.1, 0), TextDisplay.class, d -> {
            d.text(Text.mm("<b><text>靶场入口</b>\n<muted>右键进入个人独立靶场\n<dim>免费试枪 · 离开时自动归还原装备"));
            d.setBillboard(Display.Billboard.CENTER);
            d.setBrightness(LABEL_LIGHT);
            d.setPersistent(false);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(110, 10, 12, 16));
            d.getPersistentDataContainer().set(Keys.key("station"), PersistentDataType.STRING, "range_rack");
        });
        world.spawn(rack, Interaction.class, it -> {
            it.setInteractionWidth(1.2f);
            it.setInteractionHeight(2.2f);
            it.setPersistent(false);
            it.getPersistentDataContainer().set(Keys.key("station"), PersistentDataType.STRING, "range_rack");
        });
        refreshLeaderboard();
    }

    /**
     * Where a station stands, at floor level ("range_rack" for the range entrance), or null for one
     * the hangar does not have. The new-player guide points here.
     */
    public @Nullable Location station(String id) {
        if (world == null) {
            return null;
        }
        if ("range_rack".equals(id)) {
            return new Location(world, RACK_X, LobbyBuilder.Y + 1, RACK_Z);
        }
        for (int i = 0; i < STATIONS.length && i < STATIONS_XZ.length; i++) {
            if (STATIONS[i][0].equals(id)) {
                return new Location(world, STATIONS_XZ[i][0] + 0.5, LobbyBuilder.Y + 1, STATIONS_XZ[i][1] + 0.5);
            }
        }
        return null;
    }

    private void refreshLeaderboard() {
        if (leaderboard == null || !leaderboard.isValid()) {
            return;
        }
        StringBuilder sb = new StringBuilder("<b><accent>排位赛 · 赛季榜</b>\n");
        int i = 1;
        for (ProfileService.Summary s : plugin.profiles().leaderboard((a, b) -> Integer.compare(b.rankPoints(), a.rankPoints()), 10)) {
            sb.append("<muted>").append(i++).append(". <white>").append(Text.escape(s.name())).append(" <muted>")
                    .append(plugin.ranks().tierName(s.rankPoints())).append(" <accent>").append(s.rankPoints()).append('\n');
        }
        if (i == 1) {
            sb.append("<muted>暂无数据");
        }
        leaderboard.text(Text.mm(sb.toString().trim()));
    }

    // ------------------------------------------------------------------ player flow

    /** Returns to the lobby with the items the player currently carries (extraction, called off, out of the raid world). */
    public void enterLobbyKeepInventory(Player player) {
        plugin.weapons().flush(player);
        // The cursor item comes home too: back into the inventory, or the mailbox. An armory item on
        // it is left there, emptied of the player's own items, for stripTestItems to take.
        plugin.carry().reclaimCursorHome(player);
        int[] stripped = stripTestItems(player);
        // Whatever comes home from a raid (extracted, called off, out of the raid world) is no longer insured.
        plugin.insurance().strip(player);
        Profile profile = plugin.profiles().get(player);
        profile.loadout = plugin.carry().snapshot(player);
        enterLobby(player);
        if (stripped[0] > 0) {
            Text.send(player, "<accent>试验场物资已收回（" + stripped[0] + " 件），它们不能带出对局。"
                    + (stripped[1] > 0 ? "<muted>装在里面的 " + stripped[1] + " 件自己的物品放不下，已送到仓库邮箱。" : ""));
        }
    }

    /**
     * Proving-ground armory items never leave their raid: they are taken away on the way back to the
     * lobby. Whatever the player packed into an armory rig or backpack is theirs and goes back into the
     * inventory, or to the stash mailbox when it does not fit. Returns {armory items taken, items mailed}.
     */
    public int[] stripTestItems(Player player) {
        PlayerInventory inv = player.getInventory();
        List<ItemRecord> handBack = new ArrayList<>();
        int taken = 0;
        // An equipped armory rig or backpack is packed first, so its cells are emptied before they lock.
        for (int slot : new int[]{CarryService.LEGS, CarryService.FEET}) {
            if (ItemFactory.isTest(inv.getItem(slot))) {
                ItemStack packed = plugin.carry().unequip(player, slot);
                if (packed != null) {
                    taken += 1 + splitTest(plugin.items().contents(packed), handBack);
                }
            }
        }
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (ItemFactory.isTest(stack)) {
                if (slot == inv.getHeldItemSlot()) {
                    plugin.weapons().reset(player);
                }
                taken += 1 + splitTest(plugin.items().contents(stack), handBack);
                inv.setItem(slot, null);
            } else if (ItemFactory.hasContents(stack)) {
                // The player's own rig or backpack with armory items packed inside: keep it, minus those.
                List<ItemRecord> own = new ArrayList<>();
                int test = splitTest(plugin.items().contents(stack), own);
                if (test > 0) {
                    plugin.items().setContents(stack, own);
                    taken += test;
                }
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (ItemFactory.isTest(cursor)) {
            taken += 1 + splitTest(plugin.items().contents(cursor), handBack);
            player.setItemOnCursor(null);
        }
        // The player is still in the raid world here, where give's own overflow would drop to the
        // floor, so what does not fit is mailed directly.
        int mailed = 0;
        for (ItemRecord record : handBack) {
            if (plugin.carry().handBack(player, record) != null) {
                mailed++;
            }
        }
        return new int[]{taken, mailed};
    }

    /** Copies the records that may be kept into {@code keep}; returns how many test items were left out. */
    private static int splitTest(List<ItemRecord> records, List<ItemRecord> keep) {
        int test = 0;
        for (ItemRecord record : records) {
            ItemRecord kept = ItemFactory.persistent(record);
            if (kept == null) {
                test++;
            } else {
                keep.add(kept);
            }
        }
        return test;
    }

    public void enterLobby(Player player) {
        // onDisable sends everybody home from the raids and the range, and by then the plugin
        // already counts as disabled: scheduling would throw and cut the shutdown short. The
        // tutorial hint is not needed then anyway.
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    plugin.tutorial().show(player, plugin.profiles().get(player));
                }
            }, 40L);
        }
        plugin.downed().clear(player);
        plugin.weapons().reset(player);
        plugin.hud().onLobby(player);
        // The raid's camera holds (RaidManager and ReplayService, onStopSpectating) may refuse this
        // for someone still on the entry camera or the death view. The game mode change below lets
        // go of any camera all the same: Paper switches the mode first and then drops the camera,
        // and the holds only keep spectators. Keep the two together.
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setSpectatorTarget(null);
        }
        player.setGameMode(GameMode.ADVENTURE);
        player.teleport(spawn());
        plugin.health().setupPlayer(player, true);
        Profile profile = plugin.profiles().get(player);
        plugin.carry().apply(player, profile.loadout, UiItems.lobbyMenu());
        plugin.carry().ensureMelee(player);
        // What did not fit went to the mailbox: the loadout is what was placed, at once, so entering
        // the lobby again before the periodic save cannot mail those items a second time.
        saveLoadout(player);
        player.setFallDistance(0);
        player.setFireTicks(0);
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
    }

    /**
     * Persists the lobby inventory as the next raid's loadout. Whatever is held on the cursor goes
     * back into the inventory first (or to the mailbox), so it is part of the loadout. Does nothing on
     * the range: what the player holds there is loaners, and the loadout saved on entry has to survive
     * until they leave.
     */
    public void captureLoadout(Player player) {
        if (plugin.range().isIn(player)) {
            return;
        }
        plugin.carry().spill(player, plugin.carry().reclaimCursor(player));
        saveLoadout(player);
    }

    /** The loadout as it is, cursor left alone (the periodic save must not snatch an item being dragged). */
    private void saveLoadout(Player player) {
        if (plugin.range().isIn(player)) {
            return;
        }
        if (plugin.registry().isEmpty()) {
            return; // without item definitions the snapshot would come out empty and wipe the loadout
        }
        Profile profile = plugin.profiles().get(player);
        plugin.weapons().flush(player);
        profile.loadout = plugin.carry().snapshot(player);
        plugin.profiles().markDirty(player.getUniqueId());
    }

    private void tickPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isLobby(player.getWorld()) && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR
                    && plugin.raids().raidOf(player) == null) {
                plugin.carry().ensureMelee(player);
                plugin.carry().refreshLocks(player);
                // Last: both steps above can mail what no longer has a cell, and a loadout saved
                // before them would still hold it, so it would come back on the next lobby entry.
                saveLoadout(player);
            }
        }
    }

    /** Range loaners never leave the range: they are taken back as soon as the player steps out. */
    private void tickRange() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!inRange(player) && stripRangeItems(player) > 0) {
                Notify.info(player, "<accent>已离开靶场，靶场武器已收回");
            }
        }
    }

    /** Removes every shooting-range item from the player's inventory and cursor. Returns how many. */
    public int stripRangeItems(Player player) {
        return stripRangeItems(player, false);
    }

    /**
     * Removes the shooting-range items from the player's inventory and cursor; with {@code keepGear}
     * the loaner rig and backpack the range put on stay where they are worn (the rack's hand-back
     * button: the player is still on the range). Returns how many were removed.
     */
    public int stripRangeItems(Player player, boolean keepGear) {
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        int removed = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            if (keepGear && (i == CarryService.LEGS || i == CarryService.FEET)) {
                continue;
            }
            if (ItemFactory.isRange(inv.getItem(i))) {
                if (i == inv.getHeldItemSlot()) {
                    plugin.weapons().reset(player);
                }
                inv.setItem(i, null);
                removed++;
            }
        }
        if (ItemFactory.isRange(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            removed++;
        }
        return removed;
    }

    private void giveStarter(Player player, Profile profile) {
        ConfigurationSection starter = plugin.getConfig().getConfigurationSection("starter");
        profile.receivedStarter = true;
        profile.money = plugin.getConfig().getLong("starter.money", 150_000);
        if (starter == null) {
            return;
        }
        ConfigurationSection loadout = starter.getConfigurationSection("loadout");
        if (loadout != null) {
            for (String key : loadout.getKeys(false)) {
                String raw = loadout.getString(key, "");
                String[] parts = raw.split(":");
                ItemDef def = plugin.registry().get(parts[0]);
                if (def == null) {
                    continue;
                }
                int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                ItemStack stack = def.isGun() && parts.length > 2 ? plugin.items().loadedGun(def, parts[2]) : plugin.items().create(def, amount);
                ItemRecord record = plugin.items().toRecord(stack);
                if (record != null) {
                    profile.loadout.put(Integer.parseInt(key), record);
                }
            }
        }
        for (String raw : starter.getStringList("stash")) {
            String[] parts = raw.split(":");
            ItemDef def = plugin.registry().get(parts[0]);
            if (def != null) {
                ItemRecord record = plugin.items().toRecord(plugin.items().create(def, parts.length > 1 ? Integer.parseInt(parts[1]) : 1));
                if (record != null) {
                    profile.stash.add(record);
                }
            }
        }
        Text.send(player, "<accent>欢迎来到 Craftov！<muted>已为你发放新手物资与 ◈" + Text.money(profile.money) + " 块币。");
    }

    @EventHandler
    public void onSpawnLocation(io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent event) {
        if (world != null) {
            event.setSpawnLocation(spawn());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        event.joinMessage(Text.mm("<muted>+ " + Text.escape(player.getName())));
        Profile profile = plugin.profiles().get(player);
        if (!profile.receivedStarter) {
            giveStarter(player, profile);
        }
        if ((profile.activeRaid != null || profile.deployment != null) && plugin.raids().raidOf(player) == null) {
            // The server went down mid-raid without a clean stop (or the stop could not void it):
            // voided now, the way a stop voids it, before the lobby puts the loadout on them.
            plugin.raids().voidLost(player);
        }
        if (player.hasPermission("craftov.admin") && !plugin.maps().broken().isEmpty()) {
            Text.send(player, "<danger>地图文件有错误，未加载：" + Text.escape(String.join("、", plugin.maps().broken()))
                    + " <muted>(详见控制台，修好后 /cv reload)");
        }
        plugin.escorts().reclaim(player, profile);
        plugin.health().setupPlayer(player, false);
        stripRangeItems(player);
        upgradeItems(player);
        // A moment later, so it is not lost among the join messages.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                plugin.mail().remind(player);
            }
        }, 60L);
        if (plugin.raids().tryRejoin(player)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                enterLobby(player);
                plugin.hud().onJoin(player);
                plugin.tutorial().briefOnJoin(player);
            }
        });
    }

    /** Rebuilds stacks made by an older Craftov build so they pick up new item components. */
    private void upgradeItems(Player player) {
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack upgraded = plugin.items().upgrade(inv.getItem(i));
            if (upgraded != null) {
                inv.setItem(i, upgraded);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        event.quitMessage(Text.mm("<muted>- " + Text.escape(player.getName())));
        if (plugin.raids().raidOf(player) == null && isLobby(player.getWorld())
                && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
            captureLoadout(player);
        }
        plugin.match().onQuit(player);
        plugin.hud().onQuit(player);
        net.infnetwork.snowball.craftov.util.Notify.forget(player);
        player.getInventory().clear();
        plugin.profiles().unload(player.getUniqueId());
        lastMenuClick.remove(player.getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        event.setRespawnLocation(spawn());
        Bukkit.getScheduler().runTask(plugin, () -> enterLobby(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (isLobby(event.getEntity().getWorld())) {
            if (event.getEntity() instanceof Player player && event.getCause() == EntityDamageEvent.DamageCause.VOID) {
                player.teleport(spawn());
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isLobby(event.getPlayer().getWorld()) && event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof org.bukkit.event.player.PlayerTeleportEvent) {
            return;
        }
        if (event.getTo().getY() < 40 && isLobby(event.getTo().getWorld())) {
            event.getPlayer().teleport(spawn());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUseMenu(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL) {
            return;
        }
        String tag = ItemFactory.uiTag(event.getItem());
        if (tag == null) {
            return;
        }
        event.setCancelled(true);
        if (!event.getAction().isRightClick()) {
            return;
        }
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        if (now - lastMenuClick.getOrDefault(player.getUniqueId(), 0L) < 250) {
            return;
        }
        lastMenuClick.put(player.getUniqueId(), now);
        if (UiItems.LOBBY_MENU.equals(tag)) {
            plugin.menus().openMain(player);
        } else if (RangeService.EXIT.equals(tag)) {
            plugin.range().leave(player, true);
        } else if (UiItems.RAID_TERMINAL.equals(tag)) {
            // Also while downed (DownedService cancels the click first, which this handler ignores
            // on purpose): the terminal locks what needs free hands, the secure container among them.
            plugin.menus().openTerminal(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onStation(PlayerInteractEntityEvent event) {
        String station = event.getRightClicked().getPersistentDataContainer().get(Keys.key("station"), PersistentDataType.STRING);
        if (station == null) {
            return;
        }
        event.setCancelled(true);
        plugin.menus().openStation(event.getPlayer(), station);
    }

    /**
     * Targets never catch fire. The training ground is open to the sky, and its bare-headed zombie
     * targets stood in flames in daylight although they are told not to burn (the range's targets
     * are indoors and wear helmets).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTargetCombust(org.bukkit.event.entity.EntityCombustEvent event) {
        if (isTarget(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTargetHit(EntityDamageEvent event) {
        if (event.getEntity() instanceof LivingEntity living && isTarget(living)) {
            event.setCancelled(true);
            living.setHealth(living.getAttribute(Attribute.MAX_HEALTH).getValue());
        }
    }

    /** Explosions never break blocks in the lobby or on raid maps. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (isLobby(event.getLocation().getWorld()) || plugin.raids().isRaidWorld(event.getLocation().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (isLobby(event.getBlock().getWorld()) || plugin.raids().isRaidWorld(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }
}
