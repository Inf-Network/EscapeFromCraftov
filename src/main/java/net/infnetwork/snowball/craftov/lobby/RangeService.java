package net.infnetwork.snowball.craftov.lobby;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.gui.UiItems;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.map.WorldService;
import net.infnetwork.snowball.craftov.profile.ProfileService;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Private shooting ranges, the one place outside a raid to shoot. Entering saves the player's lobby
 * inventory and moves them to a firing line built on a copy of the hangar's north-east corner in a
 * separate world, one plot per player, far from all others and with other players hidden, wearing a
 * loaner rig and backpack. The corner's lane has steel silhouettes; two lanes built beside it
 * ({@link RangeBuilder}) have bullseyes and silhouettes moving on rails ({@link RangeTargets}).
 * Leaving wipes everything they hold and restores the saved inventory, so
 * nothing handed out on the range can ever reach the lobby, the stash or a raid. The saved
 * inventory is kept here as well as in the profile and written back on leaving, so nothing that
 * overwrites the profile's loadout during the session can cost the player their gear.
 */
public final class RangeService implements Listener {

    public static final String WORLD = "craftov_range";
    public static final String EXIT = "range_exit";
    private static final int SPACING = 256;
    // The hangar's north-east corner, copied block for block into every plot under its firing lane.
    private static final int X0 = 6;
    private static final int X1 = LobbyBuilder.R + 1;
    private static final int Z0 = -LobbyBuilder.R - 1;
    private static final int Z1 = -2;
    private static final int Y0 = LobbyBuilder.Y - 1;
    private static final int Y1 = LobbyBuilder.Y + 8;

    private final CraftovPlugin plugin;
    private @Nullable World world;
    private final Map<UUID, Integer> plots = new HashMap<>();
    /** The lobby loadout each player brought in, handed back to the profile when they leave. */
    private final Map<UUID, Map<Integer, ItemRecord>> saved = new HashMap<>();
    private final Map<Integer, List<Entity>> plotEntities = new HashMap<>();
    private final BitSet used = new BitSet();
    private final RangeTargets targets;

    public RangeService(CraftovPlugin plugin) {
        this.plugin = plugin;
        this.targets = new RangeTargets(plugin);
    }

    public RangeTargets targets() {
        return targets;
    }

    /** The range world, or null before it is created. */
    public @Nullable World world() {
        return world;
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
        targets.start();
    }

    public boolean isRangeWorld(@Nullable World w) {
        return w != null && w.equals(world);
    }

    /** True while the player is in their private range. */
    public boolean isIn(Player player) {
        return plots.containsKey(player.getUniqueId()) && isRangeWorld(player.getWorld());
    }

    /** The plot the player is on while on the range, else null. */
    public @Nullable Integer plotOf(Player player) {
        return isRangeWorld(player.getWorld()) ? plots.get(player.getUniqueId()) : null;
    }

    /** The weapon rack on the player's own plot, or null when they are not on the range. */
    public @Nullable Location rack(Player player) {
        Integer plot = plots.get(player.getUniqueId());
        return plot == null || world == null ? null
                : new Location(world, offset(plot) + LobbyService.RACK_X, LobbyBuilder.Y + 1, LobbyService.RACK_Z);
    }

    private int offset(int plot) {
        return plot * SPACING;
    }

    // ------------------------------------------------------------------ enter / leave

    public void enter(Player player) {
        World lobby = plugin.lobby().world();
        if (world == null || lobby == null || plots.containsKey(player.getUniqueId()) || plugin.raids().raidOf(player) != null
                || plugin.raids().launching(player.getUniqueId()) || !plugin.lobby().isLobby(player.getWorld())) {
            return;
        }
        // The lobby inventory is the real loadout: save it before anything is handed out.
        plugin.lobby().captureLoadout(player);
        plugin.profiles().saveNow(player.getUniqueId());
        saved.put(player.getUniqueId(), copy(plugin.profiles().get(player).loadout));
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
        wearLoanerGear(player);
        player.teleport(new Location(world, offset(plot) + 14.5, LobbyBuilder.Y + 1, -5.5, 180f, 0f));
        player.setGameMode(GameMode.ADVENTURE);
        hideOthers(player);
        plugin.tutorial().done(player, TutorialService.Step.RANGE);
        Text.send(player, "<accent>已进入个人靶场。<muted>右键武器架领取武器，子弹无限；身上是靶场专用的胸挂和背包，"
                + "武器架上的配件都装得下。离开时所有靶场物品自动清空，身上原有的装备会原样归还。");
    }

    /**
     * Puts on a loaner rig and backpack, the roomiest of each, so everything on the rack fits at
     * once: without them a player had two gun keys and no cell for a single attachment. They are
     * range items like the loaners, so they leave with the session and have no stored form, and
     * they cannot be taken off here (CarryListener): one taken off would pack the loaners inside,
     * which cannot be packed, and lose them.
     */
    private void wearLoanerGear(Player player) {
        for (Category category : new Category[]{Category.RIG, Category.BACKPACK}) {
            plugin.registry().byCategory(category).stream()
                    .max(Comparator.comparingInt(ItemDef::slots).thenComparingLong(ItemDef::value))
                    .ifPresent(def -> plugin.carry().equip(player, plugin.items().markRange(plugin.items().create(def, 1))));
        }
        plugin.carry().refreshLocks(player);
    }

    /** Ends the session: everything held is discarded and the saved lobby inventory comes back. */
    public void leave(Player player, boolean toLobby) {
        if (!end(player) || !toLobby) {
            return;
        }
        plugin.lobby().enterLobby(player);
        Notify.hint(player, "已离开靶场，原有装备已归还");
    }

    /**
     * Ends the session where the player stands: the loaners are discarded, the plot is freed, the
     * others can see them again and the loadout saved on entry goes back into the profile. False
     * when there was no session.
     */
    private boolean end(Player player) {
        Integer plot = plots.remove(player.getUniqueId());
        if (plot == null) {
            return false;
        }
        plugin.weapons().reset(player);
        player.closeInventory();
        player.getInventory().clear();
        player.setItemOnCursor(null);
        release(plot);
        // A trial operator, the range's own skill cooldown and a sprint still running stay behind.
        plugin.operators().leftRange(player);
        for (Player other : Bukkit.getOnlinePlayers()) {
            player.showPlayer(plugin, other);
            other.showPlayer(plugin, player);
        }
        restoreLoadout(player.getUniqueId());
        return true;
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plots.containsKey(player.getUniqueId())) {
                leave(player, true);
            }
        }
    }

    /** Writes the loadout saved on entry back to the profile, whatever happened to it meanwhile. */
    private void restoreLoadout(UUID uuid) {
        Map<Integer, ItemRecord> loadout = saved.remove(uuid);
        if (loadout != null) {
            plugin.profiles().edit(uuid, () -> "range session ended: loadout = " + ProfileService.json(loadout),
                    profile -> profile.loadout = loadout);
        }
    }

    private static Map<Integer, ItemRecord> copy(Map<Integer, ItemRecord> loadout) {
        Map<Integer, ItemRecord> out = new HashMap<>();
        loadout.forEach((slot, record) -> out.put(slot, record.copy()));
        return out;
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
        return UiItems.button("craftov:ui/icon_cancel", "<danger><b>离开靶场</b> <muted>(右键)",
                List.of("<muted>清空靶场物品，归还原有装备"), EXIT);
    }

    // ------------------------------------------------------------------ plots

    private void build(World lobby, int plot) {
        if (world == null) {
            return;
        }
        int dx = offset(plot);
        hold(plot, true);
        for (int x = X0; x <= X1; x++) {
            for (int z = Z0; z <= Z1; z++) {
                for (int y = Y0; y <= Y1; y++) {
                    world.getBlockAt(x + dx, y, z).setBlockData(lobby.getBlockAt(x, y, z).getBlockData(), false);
                }
            }
        }
        // The lobby itself has no lane any more (#164): the range is where shooting is done.
        LobbyBuilder.lane(world, dx);
        RangeBuilder.build(world, dx);
        List<Entity> spawned = new ArrayList<>();
        Location rack = new Location(world, dx + LobbyService.RACK_X, LobbyBuilder.Y + 1, LobbyService.RACK_Z);
        spawned.add(world.spawn(rack.clone().add(0, 1.2, 0), ItemDisplay.class, d -> {
            d.setItemStack(UiItems.button("craftov:ui/tab_weapon", "靶场武器架", List.of(), "station"));
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setPersistent(false);
        }));
        spawned.add(world.spawn(rack.clone().add(0, 2.1, 0), TextDisplay.class, d -> {
            d.text(Text.mm("<heavy><text>靶场武器架</heavy>\n<muted>免费试用 · 子弹无限 · 可装瞄准镜"));
            d.setBillboard(Display.Billboard.CENTER);
            d.setBrightness(LobbyService.LABEL_LIGHT);
            d.setPersistent(false);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(110, 10, 12, 16));
        }));
        spawned.add(world.spawn(rack, Interaction.class, it -> {
            it.setInteractionWidth(1.2f);
            it.setInteractionHeight(2.2f);
            it.setPersistent(false);
            it.getPersistentDataContainer().set(Keys.key("station"), PersistentDataType.STRING, "range_rack");
        }));
        targets.spawn(world, plot, dx, spawned);
        calibration(lobby, plot, dx, spawned);
        plotEntities.put(plot, spawned);
    }

    /** Distance (blocks) from the firing position to the calibration target, and its size (tools/art/ui.py). */
    private static final double CALIBRATION_DISTANCE = 50;
    private static final float CALIBRATION_SIZE = 5f;

    /**
     * A 50 m calibration target straight down the lane from where the range puts the player, seen
     * through a window cut in the back walls: a ring 40 mil across the radius, where the 4x reticle's
     * thin centre ends (its posts just touch the ring when the zoom is right), ticks every 5 mil for
     * the 3x hash marks and the 8x mil-dots.
     */
    private void calibration(World lobby, int plot, int dx, List<Entity> spawned) {
        double eye = LobbyBuilder.Y + 1 + 1.62;
        for (int x = 11; x <= 18; x++) {
            for (int y = LobbyBuilder.Y + 1; y <= LobbyBuilder.Y + 3; y++) {
                for (int z = -LobbyBuilder.R - 1; z <= -LobbyBuilder.R + 1; z++) {
                    world.getBlockAt(x + dx, y, z).setType(org.bukkit.Material.AIR, false);
                }
            }
        }
        Location at = new Location(world, dx + 14.5, eye, -5.5 - CALIBRATION_DISTANCE, 0f, 0f);
        org.bukkit.inventory.ItemStack target = org.bukkit.inventory.ItemStack.of(org.bukkit.Material.PAPER);
        target.setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key("craftov", "fx/calibration"));
        spawned.add(world.spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(target);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(CALIBRATION_SIZE, CALIBRATION_SIZE, CALIBRATION_SIZE), new org.joml.Quaternionf()));
            d.setBillboard(Display.Billboard.FIXED);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(2f);
            d.setPersistent(false);
        }));
        // Under the target (a text display grows upwards from where it stands) and a little to the left:
        // above it, the label hung at the height of the 7 m dummy's name tag, which is wide that close
        // and covered it, and the top of the window cut its title; centred, the dummy's body hid its end.
        spawned.add(world.spawn(at.clone().add(-2.0, -CALIBRATION_SIZE / 2 - 1.6, 0), TextDisplay.class, d -> {
            d.text(Text.mm("<heavy><text>50 m 校准靶</heavy>\n<muted>4 倍镜粗线碰到外圈 · 刻度 5 mil"));
            d.setBillboard(Display.Billboard.CENTER);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(3f, 3f, 3f), new org.joml.Quaternionf()));
            d.setBrightness(LobbyService.LABEL_LIGHT);
            d.setViewRange(2f);
            d.setPersistent(false);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(150, 10, 12, 16));
        }));
    }

    /**
     * Keeps the plot's chunks loaded while it is in use, the 50 m calibration target's included. The
     * targets are spawned before the player arrives, and a chunk with no ticket unloads at the end of
     * the tick, discarding them (they are never saved): only those near where the player lands
     * survived. Released with the plot, after its entities are removed.
     */
    private void hold(int plot, boolean held) {
        if (world == null) {
            return;
        }
        int dx = offset(plot);
        int z0 = Math.min(RangeBuilder.FAR, (int) Math.floor(-5.5 - CALIBRATION_DISTANCE)) - 1;
        for (int cx = Math.floorDiv(dx + RangeBuilder.WEST - 1, 16); cx <= Math.floorDiv(dx + X1 + 1, 16); cx++) {
            for (int cz = Math.floorDiv(z0, 16); cz <= Math.floorDiv(RangeBuilder.BACK + 1, 16); cz++) {
                if (held) {
                    world.addPluginChunkTicket(cx, cz, plugin);
                } else {
                    world.removePluginChunkTicket(cx, cz, plugin);
                }
            }
        }
    }

    private void release(int plot) {
        targets.clear(plot);
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
        hold(plot, false);
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
            if (l.getX() < dx + RangeBuilder.WEST - 1 || l.getX() > dx + X1 + 1 || l.getZ() < RangeBuilder.FAR - 1
                    || l.getZ() > RangeBuilder.BACK + 1 || l.getY() < Y0 - 4) {
                player.teleport(new Location(world, dx + 14.5, LobbyBuilder.Y + 1, -5.5, 180f, 0f));
            }
        }
        for (UUID uuid : new ArrayList<>(plots.keySet())) {
            if (Bukkit.getPlayer(uuid) == null) {
                Integer plot = plots.remove(uuid);
                if (plot != null) {
                    release(plot);
                }
                restoreLoadout(uuid);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        // Moved off the range by other means (admin teleport, /cv lobby, /tp). The session ends the
        // moment they arrive: waiting for the next upkeep tick left a moment in which the lobby
        // menus worked on a loadout the range was about to overwrite. Leaving through the exit
        // item ends the session before the teleport, so this does nothing then.
        Player player = event.getPlayer();
        if (isRangeWorld(event.getFrom()) && end(player)) {
            plugin.carry().apply(player, plugin.profiles().get(player).loadout, UiItems.lobbyMenu());
            // Whatever did not fit went to the mailbox: saved at once, as entering the lobby does, so
            // the loadout no longer holds it and entering the lobby next cannot mail it again.
            plugin.lobby().captureLoadout(player);
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

    /**
     * A round, knife or blast reaching a range target (DamageService#deal): before LobbyService
     * cancels the damage and heals it, so a silhouette can count it against its own health.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onTargetDamage(EntityDamageEvent event) {
        if (plugin.damage().isApplying() && isRangeWorld(event.getEntity().getWorld())) {
            targets.damaged(event.getEntity(), event.getDamage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExplode(EntityExplodeEvent event) {
        if (isRangeWorld(event.getLocation().getWorld())) {
            event.blockList().clear();
        }
    }
}
