package net.infnetwork.snowball.craftov.lobby;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.gui.UiItems;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.map.WorldService;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Private shooting ranges. Entering saves the player's lobby inventory and moves them to a copy of
 * the hangar's firing line in a separate world, one copy per player, far from all others and with
 * other players hidden. Leaving wipes everything they hold and restores the saved inventory, so
 * nothing handed out on the range can ever reach the lobby, the stash or a raid.
 */
public final class RangeService implements Listener {

    public static final String WORLD = "craftov_range";
    public static final String EXIT = "range_exit";
    private static final int SPACING = 256;
    // The hangar's firing line, copied block for block into every plot.
    private static final int X0 = 6;
    private static final int X1 = LobbyBuilder.R + 1;
    private static final int Z0 = -LobbyBuilder.R - 1;
    private static final int Z1 = -2;
    private static final int Y0 = LobbyBuilder.Y - 1;
    private static final int Y1 = LobbyBuilder.Y + 8;

    private final CraftovPlugin plugin;
    private @Nullable World world;
    private final Map<UUID, Integer> plots = new HashMap<>();
    private final Map<Integer, List<Entity>> plotEntities = new HashMap<>();
    private final BitSet used = new BitSet();

    public RangeService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        world = WorldService.creator(WORLD).createWorld();
        if (world == null) {
            plugin.getLogger().severe("Could not create the range world");
            return;
        }
        WorldService.applyRules(world, false);
        world.setTime(6000);
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Player)) {
                entity.remove();
            }
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    public boolean isRangeWorld(@Nullable World w) {
        return w != null && w.equals(world);
    }

    /** True while the player is in their private range. */
    public boolean isIn(Player player) {
        return plots.containsKey(player.getUniqueId()) && isRangeWorld(player.getWorld());
    }

    private int offset(int plot) {
        return plot * SPACING;
    }

    // ------------------------------------------------------------------ enter / leave

    public void enter(Player player) {
        World lobby = plugin.lobby().world();
        if (world == null || lobby == null || plots.containsKey(player.getUniqueId()) || plugin.raids().raidOf(player) != null
                || !plugin.lobby().isLobby(player.getWorld())) {
            return;
        }
        // The lobby inventory is the real loadout: save it before anything is handed out.
        plugin.lobby().captureLoadout(player);
        plugin.profiles().saveNow(player.getUniqueId());
        plugin.match().cancelQueue(player.getUniqueId(), "进入靶场");
        plugin.weapons().reset(player);
        int plot = used.nextClearBit(0);
        used.set(plot);
        plots.put(player.getUniqueId(), plot);
        build(lobby, plot);
        player.closeInventory();
        player.getInventory().clear();
        player.setItemOnCursor(null);
        player.getInventory().setItem(CarryService.TERMINAL_SLOT, exitItem());
        player.getInventory().setItem(CarryService.MELEE_SLOT, plugin.items().create("knife_starter", 1));
        // Wide open hands: the range ignores rig and backpack limits.
        plugin.carry().refreshLocks(player);
        player.teleport(new Location(world, offset(plot) + 14.5, LobbyBuilder.Y + 1, -5.5, 180f, 0f));
        player.setGameMode(GameMode.ADVENTURE);
        hideOthers(player);
        plugin.tutorial().done(player, TutorialService.Step.RANGE);
        Text.send(player, "<#F2C94C>已进入个人靶场。<#9AA0A6>右键武器架领取武器，子弹无限；离开时所有靶场物品自动清空，身上原有的装备会原样归还。");
    }

    /** Ends the session: everything held is discarded and the saved lobby inventory comes back. */
    public void leave(Player player, boolean toLobby) {
        Integer plot = plots.remove(player.getUniqueId());
        if (plot == null) {
            return;
        }
        plugin.weapons().reset(player);
        player.closeInventory();
        player.getInventory().clear();
        player.setItemOnCursor(null);
        release(plot);
        for (Player other : Bukkit.getOnlinePlayers()) {
            player.showPlayer(plugin, other);
            other.showPlayer(plugin, player);
        }
        if (toLobby) {
            plugin.lobby().enterLobby(player);
            player.sendActionBar(Text.mm("<#9AA0A6>已离开靶场，原有装备已归还"));
        }
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plots.containsKey(player.getUniqueId())) {
                leave(player, true);
            }
        }
    }

    private void hideOthers(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != player) {
                player.hidePlayer(plugin, other);
                other.hidePlayer(plugin, player);
            }
        }
    }

    private static org.bukkit.inventory.ItemStack exitItem() {
        return UiItems.button("craftov:ui/icon_cancel", "<#FF8A80><b>离开靶场</b> <#8A8F98>(右键)",
                List.of("<#9AA0A6>清空靶场物品，归还原有装备"), EXIT);
    }

    // ------------------------------------------------------------------ plots

    private void build(World lobby, int plot) {
        if (world == null) {
            return;
        }
        int dx = offset(plot);
        for (int x = X0; x <= X1; x++) {
            for (int z = Z0; z <= Z1; z++) {
                for (int y = Y0; y <= Y1; y++) {
                    world.getBlockAt(x + dx, y, z).setBlockData(lobby.getBlockAt(x, y, z).getBlockData(), false);
                }
            }
        }
        List<Entity> spawned = new ArrayList<>();
        Location rack = new Location(world, dx + 9.5, LobbyBuilder.Y + 1, -4.4);
        spawned.add(world.spawn(rack.clone().add(0, 1.2, 0), ItemDisplay.class, d -> {
            d.setItemStack(UiItems.button("craftov:ui/tab_weapon", "靶场武器架", List.of(), "station"));
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setPersistent(false);
        }));
        spawned.add(world.spawn(rack.clone().add(0, 2.1, 0), TextDisplay.class, d -> {
            d.text(Text.mm("<b><#F2E6C8>靶场武器架</b>\n<#9AA0A6>免费试用 · 子弹无限 · 可装瞄准镜"));
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(110, 10, 12, 16));
        }));
        spawned.add(world.spawn(rack, Interaction.class, it -> {
            it.setInteractionWidth(1.2f);
            it.setInteractionHeight(2.2f);
            it.setPersistent(false);
            it.getPersistentDataContainer().set(Keys.key("station"), PersistentDataType.STRING, "range_rack");
        }));
        for (int[] t : new int[][]{{11, -13}, {15, -16}, {19, -20}}) {
            spawned.add(world.spawn(new Location(world, dx + t[0] + 0.5, LobbyBuilder.Y + 1, t[1] + 0.5, 0, 0), Zombie.class,
                    CreatureSpawnEvent.SpawnReason.CUSTOM, z -> {
                        z.setAI(false);
                        z.setAdult();
                        z.setSilent(true);
                        z.setPersistent(false);
                        z.setShouldBurnInDay(false);
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
                    }));
        }
        plotEntities.put(plot, spawned);
    }

    private void release(int plot) {
        List<Entity> spawned = plotEntities.remove(plot);
        if (spawned != null) {
            spawned.forEach(Entity::remove);
        }
        if (world != null) {
            int dx = offset(plot);
            for (Entity entity : world.getEntities()) {
                // Dropped items, grenades and anything else left behind on this plot.
                if (!(entity instanceof Player) && Math.abs(entity.getLocation().getX() - dx - 14) < SPACING / 2.0) {
                    entity.remove();
                }
            }
        }
        used.clear(plot);
    }

    // ------------------------------------------------------------------ upkeep

    private void tick() {
        if (world == null) {
            return;
        }
        for (Player player : world.getPlayers()) {
            Integer plot = plots.get(player.getUniqueId());
            if (plot == null) {
                // Teleported in by other means (or a stale session): send them home.
                if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
                    player.getInventory().clear();
                    plugin.lobby().enterLobby(player);
                }
                continue;
            }
            Location l = player.getLocation();
            int dx = offset(plot);
            if (l.getX() < dx + X0 - 1 || l.getX() > dx + X1 + 1 || l.getZ() < Z0 - 1 || l.getZ() > Z1 + 1 || l.getY() < Y0 - 4) {
                player.teleport(new Location(world, dx + 14.5, LobbyBuilder.Y + 1, -5.5, 180f, 0f));
            }
        }
        for (UUID uuid : new ArrayList<>(plots.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !isRangeWorld(player.getWorld())) {
                Integer plot = plots.remove(uuid);
                if (plot != null) {
                    release(plot);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        // The saved lobby inventory stays in the profile; the range items are discarded with the session.
        if (plots.containsKey(event.getPlayer().getUniqueId())) {
            leave(event.getPlayer(), false);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isRangeWorld(event.getPlayer().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && isRangeWorld(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExplode(EntityExplodeEvent event) {
        if (isRangeWorld(event.getLocation().getWorld())) {
            event.blockList().clear();
        }
    }
}
