package net.infnetwork.snowball.craftov.lobby;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.gui.UiItems;
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
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
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
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The operations hangar: join flow, loadout persistence, stations and the shooting range. */
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
            {"rank", "排行榜", "排位赛积分", "craftov:ui/icon_rank"},
    };

    private final CraftovPlugin plugin;
    private @Nullable World world;
    private @Nullable TextDisplay leaderboard;
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
        // Stations, the rack and range targets are non-persistent entities, which the server discards when
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
                d.text(Text.mm("<b><#F2E6C8>" + s[1] + "</b>\n<#9AA0A6>" + s[2]));
                d.setBillboard(Display.Billboard.CENTER);
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
            d.setPersistent(false);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(140, 10, 12, 16));
            d.getPersistentDataContainer().set(Keys.key("station"), PersistentDataType.STRING, "rank_board");
        });
        // Weapon rack at the back of the firing line: loaner guns and optics for the range.
        Location rack = new Location(world, 9.5, LobbyBuilder.Y + 1, -4.4);
        world.spawn(rack.clone().add(0, 1.2, 0), ItemDisplay.class, d -> {
            d.setItemStack(UiItems.button("craftov:ui/tab_weapon", "靶场武器架", List.of(), "station"));
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setPersistent(false);
            d.getPersistentDataContainer().set(Keys.key("station"), PersistentDataType.STRING, "range_rack");
        });
        world.spawn(rack.clone().add(0, 2.1, 0), TextDisplay.class, d -> {
            d.text(Text.mm("<b><#F2E6C8>靶场入口</b>\n<#9AA0A6>右键进入个人独立靶场\n<#6B7079>免费试枪 · 离开时自动归还原装备"));
            d.setBillboard(Display.Billboard.CENTER);
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
        // Shooting range dummies.
        int[][] targets = {{11, -13}, {15, -16}, {19, -20}};
        for (int[] t : targets) {
            world.spawn(new Location(world, t[0] + 0.5, LobbyBuilder.Y + 1, t[1] + 0.5, 0, 0), Zombie.class,
                    CreatureSpawnEvent.SpawnReason.CUSTOM, z -> {
                        z.setAI(false);
                        z.setAdult();
                        z.setSilent(true);
                        z.setPersistent(false);
                        z.setShouldBurnInDay(false);
                        z.setInvulnerable(false);
                        z.getPersistentDataContainer().set(Keys.key("target"), PersistentDataType.BYTE, (byte) 1);
                        z.customName(Text.mm("<#9AA0A6>训练靶 " + Math.abs(t[1] + 9) + "m"));
                        z.setCustomNameVisible(true);
                        ItemDef helmet = plugin.registry().get("helmet_3");
                        ItemDef vest = plugin.registry().get("armor_3");
                        if (helmet != null) {
                            z.getEquipment().setHelmet(plugin.items().create(helmet, 1));
                        }
                        if (vest != null) {
                            z.getEquipment().setChestplate(plugin.items().create(vest, 1));
                        }
                    });
        }
        refreshLeaderboard();
    }

    private void refreshLeaderboard() {
        if (leaderboard == null || !leaderboard.isValid()) {
            return;
        }
        StringBuilder sb = new StringBuilder("<b><#FFC23D>排位赛 · 赛季榜</b>\n");
        int i = 1;
        for (ProfileService.Summary s : plugin.profiles().leaderboard((a, b) -> Integer.compare(b.rankPoints(), a.rankPoints()), 10)) {
            sb.append("<#8A8F98>").append(i++).append(". <white>").append(Text.escape(s.name())).append(" <#8A8F98>")
                    .append(plugin.ranks().tierName(s.rankPoints())).append(" <#F2C94C>").append(s.rankPoints()).append('\n');
        }
        if (i == 1) {
            sb.append("<#8A8F98>暂无数据");
        }
        leaderboard.text(Text.mm(sb.toString().trim()));
    }

    // ------------------------------------------------------------------ player flow

    /** Returns to the lobby with the items the player currently carries (extraction, voided raid). */
    public void enterLobbyKeepInventory(Player player) {
        plugin.weapons().flush(player);
        Profile profile = plugin.profiles().get(player);
        profile.loadout = plugin.carry().snapshot(player);
        enterLobby(player);
    }

    public void enterLobby(Player player) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                plugin.tutorial().show(player, plugin.profiles().get(player));
            }
        }, 40L);
        plugin.downed().clear(player);
        plugin.weapons().reset(player);
        plugin.hud().onLobby(player);
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setSpectatorTarget(null);
        }
        player.setGameMode(GameMode.ADVENTURE);
        player.teleport(spawn());
        plugin.health().setupPlayer(player, true);
        Profile profile = plugin.profiles().get(player);
        plugin.carry().apply(player, profile.loadout, UiItems.lobbyMenu());
        plugin.carry().ensureMelee(player);
        player.setFallDistance(0);
        player.setFireTicks(0);
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
    }

    /** Persists the lobby inventory as the next raid's loadout. */
    public void captureLoadout(Player player) {
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
                captureLoadout(player);
                plugin.carry().refreshLocks(player);
            }
        }
    }

    /** Range loaners never leave the firing line: they are taken back as soon as the player steps out. */
    private void tickRange() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!inRange(player) && stripRangeItems(player) > 0) {
                player.sendActionBar(Text.mm("<#FFB74D>已离开靶场，靶场武器已收回"));
            }
        }
    }

    /** Removes every shooting-range loaner from the player's inventory and cursor. Returns how many. */
    public int stripRangeItems(Player player) {
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        int removed = 0;
        for (int i = 0; i < inv.getSize(); i++) {
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
        Text.send(player, "<#F2C94C>欢迎来到锈港行动！<#9AA0A6>已为你发放新手物资与 ◈" + Text.money(profile.money) + " 块币。");
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
        event.joinMessage(Text.mm("<#8A8F98>+ " + Text.escape(player.getName())));
        Profile profile = plugin.profiles().get(player);
        if (!profile.receivedStarter) {
            giveStarter(player, profile);
        }
        if (profile.activeRaid != null && plugin.raids().raidOf(player) == null) {
            // Server stopped mid-raid without a clean shutdown: the raid is voided, the last
            // lobby loadout is restored.
            profile.activeRaid = null;
            Text.send(player, "<#FFC23D>上一次行动因服务器中断已作废，已恢复出发前的装备。");
        }
        plugin.health().setupPlayer(player, false);
        stripRangeItems(player);
        upgradeItems(player);
        if (plugin.raids().tryRejoin(player)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                enterLobby(player);
                plugin.hud().onJoin(player);
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
        event.quitMessage(Text.mm("<#8A8F98>- " + Text.escape(player.getName())));
        if (plugin.raids().raidOf(player) == null && isLobby(player.getWorld())
                && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
            captureLoadout(player);
        }
        plugin.match().onQuit(player);
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
