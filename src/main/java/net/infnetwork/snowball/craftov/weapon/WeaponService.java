package net.infnetwork.snowball.craftov.weapon;

import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Player firearms. The magazine of the held gun lives in memory while it is in use so the item
 * stack (and the first-person view) is not rewritten on every shot; it is flushed back to the
 * stack whenever the item could move.
 */
public final class WeaponService implements Listener {

    /** Runtime state of the gun a player is holding. */
    public static final class GunState {
        final String uid;
        final int slot;
        final ItemDef def;
        int ammo;
        @Nullable String ammoType;
        int mode;
        boolean triggerHeld;
        boolean needsRelease;
        int burstLeft;
        double shotBudgetMs;
        long reloadStart = -1;
        long reloadEnd = -1;
        @Nullable String reloadType;
        double bloom;
        long lastShotTick;

        GunState(String uid, int slot, ItemDef def, ItemStack stack) {
            this.uid = uid;
            this.slot = slot;
            this.def = def;
            this.ammo = ItemFactory.ammo(stack);
            this.ammoType = ItemFactory.ammoType(stack);
            this.mode = Math.floorMod(ItemFactory.mode(stack), def.gun().modes().size());
        }

        public FireMode fireMode() {
            return def.gun().modes().get(mode);
        }

        public ItemDef def() {
            return def;
        }

        public int ammo() {
            return ammo;
        }

        public @Nullable String ammoType() {
            return ammoType;
        }

        public boolean reloading() {
            return reloadEnd > 0;
        }

        public double reloadProgress() {
            if (!reloading()) {
                return 0;
            }
            long now = Bukkit.getCurrentTick();
            return Math.min(1.0, (now - reloadStart) / (double) Math.max(1, reloadEnd - reloadStart));
        }
    }

    private final CraftovPlugin plugin;
    private final Ballistics ballistics;
    private final Map<UUID, GunState> states = new HashMap<>();
    private final Map<UUID, Boolean> ads = new HashMap<>();
    private final Map<UUID, Long> lastInventoryDrop = new HashMap<>();

    public WeaponService(CraftovPlugin plugin) {
        this.plugin = plugin;
        this.ballistics = new Ballistics(plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public Ballistics ballistics() {
        return ballistics;
    }

    public @Nullable GunState state(Player player) {
        return states.get(player.getUniqueId());
    }

    public boolean isAiming(Player player) {
        return ads.getOrDefault(player.getUniqueId(), false);
    }

    public double adsSpeedFactor(Player player) {
        if (!isAiming(player)) {
            return 1.0;
        }
        GunState state = state(player);
        int zoom = state == null ? 1 : state.def.gun().zoom();
        return switch (zoom) {
            case 0 -> 0.85;
            case 1 -> 0.7;
            case 2 -> 0.5;
            default -> 0.3;
        };
    }

    // ------------------------------------------------------------------ state binding

    private @Nullable GunState bind(Player player) {
        PlayerInventory inv = player.getInventory();
        int slot = inv.getHeldItemSlot();
        ItemStack held = inv.getItem(slot);
        ItemDef def = plugin.items().defOf(held);
        String uid = ItemFactory.uid(held);
        GunState current = states.get(player.getUniqueId());
        if (current != null && current.uid.equals(uid) && current.slot == slot) {
            return current;
        }
        if (current != null) {
            flush(player);
            states.remove(player.getUniqueId());
            setAds(player, false);
        }
        if (def == null || !def.isGun() || uid == null) {
            return null;
        }
        GunState state = new GunState(uid, slot, def, held);
        states.put(player.getUniqueId(), state);
        return state;
    }

    /** Writes the in-memory magazine back onto the stack (wherever it is now). */
    public void flush(Player player) {
        GunState state = states.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack stack = inv.getItem(state.slot);
        if (!state.uid.equals(ItemFactory.uid(stack))) {
            stack = null;
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack candidate = inv.getItem(i);
                if (state.uid.equals(ItemFactory.uid(candidate))) {
                    stack = candidate;
                    break;
                }
            }
        }
        if (stack == null) {
            return;
        }
        if (ItemFactory.ammo(stack) != state.ammo || !java.util.Objects.equals(ItemFactory.ammoType(stack), state.ammoType)
                || ItemFactory.mode(stack) != state.mode) {
            ItemFactory.setMode(stack, state.mode);
            plugin.items().setAmmo(stack, state.ammo, state.ammo > 0 ? state.ammoType : state.ammoType);
        }
    }

    /** Drops cached state entirely (raid transitions, death). */
    public void reset(Player player) {
        flush(player);
        states.remove(player.getUniqueId());
        setAds(player, false);
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        long now = Bukkit.getCurrentTick();
        for (Player player : Bukkit.getOnlinePlayers()) {
            GunState state = bind(player);
            if (state == null) {
                continue;
            }
            if (state.reloading()) {
                if (now >= state.reloadEnd) {
                    finishReload(player, state);
                }
                continue;
            }
            boolean using = player.hasActiveItem() && state.uid.equals(ItemFactory.uid(player.getActiveItem()));
            if (plugin.getConfig().getBoolean("debug", false) && state.triggerHeld) {
                plugin.getLogger().info("[debug] " + player.getName() + " held=" + state.triggerHeld + " active=" + player.hasActiveItem()
                        + " activeUid=" + ItemFactory.uid(player.getActiveItem()) + " uid=" + state.uid + " ammo=" + state.ammo
                        + " mode=" + state.fireMode() + " budget=" + state.shotBudgetMs + " canFire=" + plugin.raids().canFire(player)
                        + " interval=" + state.def.gun().millisBetweenShots());
            }
            if (!using && state.triggerHeld) {
                release(state);
            }
            state.bloom = Math.max(0, state.bloom - 0.08);
            if (!state.triggerHeld) {
                continue;
            }
            FireMode mode = state.fireMode();
            GunSpec gun = state.def.gun();
            state.shotBudgetMs += 50.0;
            double interval = gun.millisBetweenShots();
            int guard = 0;
            while (state.shotBudgetMs >= interval && guard++ < 4) {
                if (mode.singleAction() || mode == FireMode.BURST) {
                    if (mode == FireMode.BURST && state.burstLeft > 0) {
                        state.burstLeft--;
                    } else {
                        state.shotBudgetMs = Math.min(state.shotBudgetMs, interval);
                        break;
                    }
                }
                state.shotBudgetMs -= interval;
                if (!shoot(player, state)) {
                    state.shotBudgetMs = 0;
                    break;
                }
            }
        }
    }

    private void release(GunState state) {
        state.triggerHeld = false;
        state.needsRelease = false;
        state.burstLeft = 0;
    }

    private void press(Player player, GunState state) {
        if (state.reloading() || state.needsRelease) {
            return;
        }
        GunSpec gun = state.def.gun();
        long now = Bukkit.getCurrentTick();
        long cooldownTicks = Math.max(1, Math.round(gun.millisBetweenShots() / 50.0));
        state.triggerHeld = true;
        if (state.fireMode().singleAction() || state.fireMode() == FireMode.BURST) {
            state.needsRelease = true;
        }
        if (now - state.lastShotTick >= cooldownTicks || state.lastShotTick == 0) {
            state.shotBudgetMs = 0;
            state.burstLeft = state.fireMode() == FireMode.BURST ? 2 : 0;
            shoot(player, state);
        }
    }

    // ------------------------------------------------------------------ shooting

    private boolean shoot(Player player, GunState state) {
        if (!plugin.raids().canFire(player)) {
            return false;
        }
        GunSpec gun = state.def.gun();
        if (state.ammo <= 0) {
            player.playSound(player.getLocation(), "craftov:gun.dry", SoundCategory.PLAYERS, 0.8f, 1f);
            state.triggerHeld = false;
            state.needsRelease = true;
            startReload(player, state, false);
            return false;
        }
        state.ammo--;
        state.lastShotTick = Bukkit.getCurrentTick();
        ItemDef ammoDef = plugin.registry().get(state.ammoType);
        AmmoSpec ammo = ammoDef == null ? null : ammoDef.ammo();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        double spread = currentSpread(player, state);
        for (int i = 0; i < gun.pellets(); i++) {
            Vector pellet = Ballistics.spread(dir, gun.pellets() > 1 ? Math.max(spread, gun.hipSpread() * 0.75) : spread);
            ballistics.fire(player, eye, pellet, gun, ammo, state.def.name(), i == 0 || gun.pellets() <= 3);
        }
        Ballistics.muzzle(player, dir);
        float pitch = 0.94f + ThreadLocalRandom.current().nextFloat() * 0.12f;
        player.getWorld().playSound(player.getLocation(), gun.sound(), SoundCategory.PLAYERS, gun.soundVolume(), pitch);
        plugin.ai().alert(player.getLocation(), gun.soundVolume() * 12.0, player);
        recoil(player, state);
        state.bloom = Math.min(3.0, state.bloom + 0.35);
        if (state.fireMode() == FireMode.BOLT || state.fireMode() == FireMode.PUMP) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> player.playSound(player.getLocation(),
                    state.fireMode() == FireMode.BOLT ? "craftov:gun.bolt" : "craftov:gun.pump", SoundCategory.PLAYERS, 0.9f, 1f), 6L);
        }
        if (state.ammo == 0) {
            player.playSound(player.getLocation(), "craftov:gun.empty", SoundCategory.PLAYERS, 0.6f, 1.2f);
        }
        return true;
    }

    private double currentSpread(Player player, GunState state) {
        GunSpec gun = state.def.gun();
        double spread = isAiming(player) ? gun.adsSpread() : gun.hipSpread();
        Vector velocity = player.getVelocity();
        boolean moving = velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ() > 0.003;
        if (moving) {
            spread *= isAiming(player) ? 1.8 : 1.4;
        }
        if (player.isSneaking()) {
            spread *= 0.7;
        }
        if (!player.isOnGround()) {
            spread *= 2.5;
        }
        if (state.fireMode() != FireMode.BOLT) {
            spread += state.bloom * (isAiming(player) ? 0.25 : 0.6);
        }
        if (plugin.health().hasInjury(player, net.infnetwork.snowball.craftov.combat.Injury.FRACTURE)) {
            spread *= 1.2;
        }
        return spread;
    }

    private void recoil(Player player, GunState state) {
        GunSpec gun = state.def.gun();
        double scale = (isAiming(player) ? 0.7 : 1.0) * (player.isSneaking() ? 0.8 : 1.0);
        float pitchKick = (float) (gun.recoil() * scale * (0.85 + ThreadLocalRandom.current().nextDouble() * 0.3));
        float yawKick = (float) ((ThreadLocalRandom.current().nextDouble() * 2 - 1) * gun.recoilH() * scale);
        Recoil.kick(player, yawKick, -pitchKick);
    }

    // ------------------------------------------------------------------ reload

    /** Reserve ammo of the gun's caliber in the player's carry cells. */
    public int reserve(Player player, GunSpec gun, @Nullable String preferType) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            ItemDef def = plugin.items().defOf(stack);
            if (def != null && def.ammo() != null && def.ammo().caliber().equals(gun.caliber())
                    && (preferType == null || preferType.equals(def.id()))) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private List<String> ammoTypesCarried(Player player, GunSpec gun) {
        List<String> out = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            ItemDef def = plugin.items().defOf(stack);
            if (def != null && def.ammo() != null && def.ammo().caliber().equals(gun.caliber()) && !out.contains(def.id())) {
                out.add(def.id());
            }
        }
        return out;
    }

    public void startReload(Player player, GunState state, boolean cycleType) {
        if (state.reloading()) {
            return;
        }
        GunSpec gun = state.def.gun();
        List<String> types = ammoTypesCarried(player, gun);
        if (types.isEmpty()) {
            player.sendActionBar(Text.mm("<#FF8A80>没有 " + gun.caliber() + " 口径的弹药"));
            return;
        }
        String type;
        if (cycleType) {
            int index = state.ammoType == null ? -1 : types.indexOf(state.ammoType);
            type = types.get((index + 1) % types.size());
        } else {
            type = state.ammoType != null && types.contains(state.ammoType) ? state.ammoType : bestType(types);
        }
        if (type.equals(state.ammoType) && state.ammo >= gun.magazine()) {
            player.sendActionBar(Text.mm("<#9AA0A6>弹匣已满  <#8A8F98>(潜行+Q 切换弹种)"));
            return;
        }
        setAds(player, false);
        long now = Bukkit.getCurrentTick();
        double duration = gun.reloadTicks() * (state.ammo == 0 ? 1.2 : 1.0) * plugin.operators().reloadMultiplier(player);
        state.reloadStart = now;
        state.reloadEnd = now + Math.max(5, Math.round(duration));
        state.reloadType = type;
        state.triggerHeld = false;
        player.getWorld().playSound(player.getLocation(), "craftov:gun.reload_out", SoundCategory.PLAYERS, 0.9f, 1f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            GunState s = states.get(player.getUniqueId());
            if (s == state && s.reloading()) {
                player.getWorld().playSound(player.getLocation(), "craftov:gun.reload_in", SoundCategory.PLAYERS, 0.9f, 1f);
            }
        }, Math.max(2, (state.reloadEnd - now) * 2 / 3));
    }

    private String bestType(List<String> types) {
        String best = types.get(0);
        int bestPen = -1;
        for (String type : types) {
            ItemDef def = plugin.registry().get(type);
            int pen = def == null || def.ammo() == null ? 0 : def.ammo().penetration();
            if (pen > bestPen) {
                bestPen = pen;
                best = type;
            }
        }
        return best;
    }

    private void finishReload(Player player, GunState state) {
        GunSpec gun = state.def.gun();
        String type = state.reloadType;
        state.reloadStart = -1;
        state.reloadEnd = -1;
        state.reloadType = null;
        if (type == null) {
            return;
        }
        CarryService carry = plugin.carry();
        if (!type.equals(state.ammoType) && state.ammo > 0 && state.ammoType != null) {
            ItemStack back = plugin.items().create(state.ammoType, state.ammo);
            if (back != null) {
                carry.give(player, back, true);
            }
            state.ammo = 0;
        }
        int need = gun.magazine() - state.ammo;
        int taken = carry.takeItem(player, type, need);
        if (taken <= 0) {
            return;
        }
        state.ammo += taken;
        state.ammoType = type;
        flush(player);
        player.playSound(player.getLocation(), "craftov:gun.reload_done", SoundCategory.PLAYERS, 0.8f, 1f);
    }

    // ------------------------------------------------------------------ ADS

    public void setAds(Player player, boolean on) {
        boolean was = isAiming(player);
        if (was == on) {
            return;
        }
        ads.put(player.getUniqueId(), on);
        plugin.health().forceSpeedUpdate(player);
        GunState state = state(player);
        if (state != null && state.def.gun().zoom() >= 3) {
            plugin.hud().scopeOverlay(player, on);
        } else if (!on) {
            plugin.hud().scopeOverlay(player, false);
        }
        if (on) {
            player.playSound(player.getLocation(), "craftov:gun.ads", SoundCategory.PLAYERS, 0.5f, 1f);
        }
    }

    // ------------------------------------------------------------------ input events

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        ItemDef def = plugin.items().defOf(event.getItem());
        if (def == null || !def.isGun()) {
            return;
        }
        GunState state = bind(player);
        if (state == null) {
            return;
        }
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            if (!state.reloading() && plugin.raids().canFire(player)) {
                setAds(player, !isAiming(player));
            }
            return;
        }
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                    && event.getClickedBlock().getType().isInteractable() && !player.isSneaking()) {
                return; // let doors, levers and buttons work while holding a gun
            }
            if (!plugin.raids().canFire(player)) {
                event.setCancelled(true);
                return;
            }
            press(player, state);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeftClickEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && !plugin.damage().isApplying()) {
            GunState state = bind(player);
            if (state != null) {
                event.setCancelled(true);
                if (!state.reloading() && plugin.raids().canFire(player)) {
                    setAds(player, !isAiming(player));
                }
            }
        }
    }

    @EventHandler
    public void onStopUsing(PlayerStopUsingItemEvent event) {
        GunState state = states.get(event.getPlayer().getUniqueId());
        if (state != null && state.uid.equals(ItemFactory.uid(event.getItem()))) {
            release(state);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClickEarly(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            flush(player);
            if (event.getAction() == InventoryAction.DROP_ALL_SLOT || event.getAction() == InventoryAction.DROP_ONE_SLOT
                    || event.getAction() == InventoryAction.DROP_ALL_CURSOR || event.getAction() == InventoryAction.DROP_ONE_CURSOR) {
                lastInventoryDrop.put(player.getUniqueId(), (long) Bukkit.getCurrentTick());
            }
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            flush(player);
            setAds(player, false);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ItemStack dropped = event.getItemDrop().getItemStack();
        ItemDef def = plugin.items().defOf(dropped);
        if (def == null || !def.isGun()) {
            return;
        }
        Long invTick = lastInventoryDrop.get(player.getUniqueId());
        boolean fromInventoryScreen = invTick != null && invTick >= Bukkit.getCurrentTick() - 1;
        GunState state = states.get(player.getUniqueId());
        if (fromInventoryScreen) {
            if (state != null && state.uid.equals(ItemFactory.uid(dropped))) {
                plugin.items().setAmmo(dropped, state.ammo, state.ammoType);
                event.getItemDrop().setItemStack(dropped);
                states.remove(player.getUniqueId());
            }
            return;
        }
        // Q outside the inventory screen = reload (sneak+Q cycles ammo type).
        event.setCancelled(true);
        if (state != null && plugin.raids().canFire(player)) {
            startReload(player, state, player.isSneaking());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (player.isSneaking()) {
            return; // sneak+F is the operator skill
        }
        GunState state = bind(player);
        if (state == null || state.def.gun().modes().size() < 2) {
            return;
        }
        state.mode = (state.mode + 1) % state.def.gun().modes().size();
        player.playSound(player.getLocation(), "craftov:gun.mode", SoundCategory.PLAYERS, 0.7f, 1f);
        player.sendActionBar(Text.mm("<#F2C94C>射击模式: <white>" + state.fireMode().displayName()));
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        flush(event.getPlayer());
        GunState state = states.remove(event.getPlayer().getUniqueId());
        if (state != null && state.reloading()) {
            event.getPlayer().sendActionBar(Text.mm("<#9AA0A6>换弹已中断"));
        }
        setAds(event.getPlayer(), false);
    }

    @EventHandler
    public void onSprint(PlayerToggleSprintEvent event) {
        if (event.isSprinting()) {
            setAds(event.getPlayer(), false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        reset(event.getPlayer());
        ads.remove(event.getPlayer().getUniqueId());
        lastInventoryDrop.remove(event.getPlayer().getUniqueId());
    }
}
