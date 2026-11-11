package net.infnetwork.snowball.craftov.weapon;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.util.Nms;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
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
        /** The magazine was empty when this reload started (the bolt has to be worked at the end). */
        boolean reloadEmpty;
        /** A suppressor is fitted: quieter report, short range, no tail to speak of. */
        final boolean suppressed;
        /** Tactical light fitted: "light", "strobe" or null. */
        final @Nullable String light;
        /** The gun's stats with its attachments applied. */
        final GunSpec gun;
        @Nullable String reloadType;
        double bloom;
        long lastShotTick;
        /** Mounted optic, or the gun's built-in scope; null = iron sights. */
        final @Nullable OpticSpec optic;
        final @Nullable String opticName;
        final boolean range;

        GunState(String uid, int slot, ItemDef def, ItemStack stack, net.infnetwork.snowball.craftov.item.ItemRegistry registry) {
            this.uid = uid;
            this.slot = slot;
            this.def = def;
            this.ammo = ItemFactory.ammo(stack);
            this.ammoType = ItemFactory.ammoType(stack);
            this.mode = Math.floorMod(ItemFactory.mode(stack), def.gun().modes().size());
            ItemDef mounted = registry.get(ItemFactory.opticId(stack));
            if (mounted == null) {
                mounted = registry.get(def.gun().scope());
            }
            this.optic = mounted == null ? null : mounted.optic();
            this.opticName = mounted == null ? null : mounted.name();
            this.range = ItemFactory.isRange(stack);
            var parts = Attachments.resolve(def.gun(), ItemFactory.attachments(stack), registry);
            this.gun = Attachments.effective(def.gun(), parts);
            this.suppressed = Attachments.suppressed(parts);
            this.light = Attachments.light(parts);
        }

        public GunSpec gun() {
            return gun;
        }

        public @Nullable String light() {
            return light;
        }

        public @Nullable String opticName() {
            return opticName;
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

        public double reloadSecondsLeft() {
            return reloading() ? Math.max(0, (reloadEnd - Bukkit.getCurrentTick()) / 20.0) : 0;
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
    private final GunAudio audio;
    private final ImpactFx fx;
    private final Map<UUID, GunState> states = new HashMap<>();
    private final Map<UUID, Boolean> ads = new HashMap<>();
    /** Right mouse repeats a use packet every 4 ticks while held; ADS ends shortly after they stop. */
    private final Map<UUID, Long> adsUntil = new HashMap<>();
    /** FOV zoom last sent to each aiming player (see {@link Nms#zoom}). */
    private final Map<UUID, Double> sentZoom = new HashMap<>();
    private final Map<UUID, Long> lastHint = new HashMap<>();
    private final Map<UUID, Integer> controlTips = new HashMap<>();
    private final Map<UUID, Long> lastInventoryDrop = new HashMap<>();

    public WeaponService(CraftovPlugin plugin) {
        this.plugin = plugin;
        this.ballistics = new Ballistics(plugin);
        this.audio = new GunAudio(plugin);
        this.fx = new ImpactFx(plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public GunAudio audio() {
        return audio;
    }

    public ImpactFx fx() {
        return fx;
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
        int zoom = state == null ? 1 : state.gun.zoom();
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
            stopReloadAnimation(player, states.remove(player.getUniqueId()));
            setAds(player, false);
        }
        if (def == null || !def.isGun() || uid == null) {
            return null;
        }
        GunState state = new GunState(uid, slot, def, held, plugin.registry());
        states.put(player.getUniqueId(), state);
        int tips = controlTips.getOrDefault(player.getUniqueId(), 0);
        if (tips < 3) {
            controlTips.put(player.getUniqueId(), tips + 1);
            player.sendActionBar(Text.mm("<white>左键<#9AA0A6> 开火  <white>右键<#9AA0A6> 瞄准  <white>Q<#9AA0A6> 换弹  "
                    + "<white>潜行+Q<#9AA0A6> 换弹种  <white>F<#9AA0A6> 射击模式"));
        }
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
        stopReloadAnimation(player, states.remove(player.getUniqueId()));
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
            boolean held = plugin.triggers().held(player);
            if (plugin.getConfig().getBoolean("debug", false) && state.triggerHeld) {
                plugin.getLogger().info("[debug] " + player.getName() + " held=" + held + " uid=" + state.uid + " ammo=" + state.ammo
                        + " mode=" + state.fireMode() + " budget=" + state.shotBudgetMs + " canFire=" + plugin.raids().canFire(player)
                        + " interval=" + state.gun.millisBetweenShots());
            }
            if (isAiming(player)) {
                if (held) {
                    adsUntil.put(player.getUniqueId(), now + 6);
                } else if (now > adsUntil.getOrDefault(player.getUniqueId(), 0L)) {
                    setAds(player, false);
                }
                if (isAiming(player) && now % 4 == 0) {
                    applyZoom(player, state);
                }
            }
            if (!held && state.triggerHeld) {
                release(state);
            }
            state.bloom = Math.max(0, state.bloom - 0.08);
            if (!state.triggerHeld) {
                continue;
            }
            FireMode mode = state.fireMode();
            GunSpec gun = state.gun;
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

    /** Left mouse went down (see {@link TriggerService}). */
    public void triggerDown(Player player) {
        GunState state = bind(player);
        if (state == null) {
            return;
        }
        String blocker = fireBlocker(player);
        if (blocker != null) {
            hint(player, blocker);
            return;
        }
        if (state.reloading()) {
            hint(player, "<#FFB74D>换弹中…");
            return;
        }
        press(player, state);
    }

    public void triggerUp(Player player) {
        GunState state = states.get(player.getUniqueId());
        if (state != null) {
            release(state);
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
        GunSpec gun = state.gun;
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
        GunSpec gun = state.gun;
        if (state.ammo <= 0) {
            audio.foley(player, gun, "dry", 0.7f);
            state.triggerHeld = false;
            state.needsRelease = true;
            if (reserve(player, gun, null) > 0) {
                startReload(player, state, false);
            } else {
                hint(player, "<#FF8A80>没有 " + gun.caliber() + " 弹药 <#9AA0A6>· 去商人买或在物资箱里找");
            }
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
        Location muzzle = Ballistics.muzzlePoint(player, dir);
        audio.shot(player, muzzle, gun, state.suppressed);
        if (!state.suppressed) {
            fx.muzzleLight(player, muzzle);
        }
        plugin.ai().alert(player.getLocation(), gun.soundVolume() * 12.0 * (state.suppressed ? 0.35 : 1.0), player);
        recoil(player, state);
        state.bloom = Math.min(3.0, state.bloom + 0.35);
        if (state.fireMode() == FireMode.BOLT) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> audio.foley(player, gun, "bolt_open", 0.9f), 6L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> audio.foley(player, gun, "bolt_close", 0.9f), 13L);
            audio.casing(player, gun, 11);
        } else if (state.fireMode() == FireMode.PUMP) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> audio.foley(player, gun, "pump", 0.9f), 6L);
            audio.casing(player, gun, 10);
        } else {
            audio.casing(player, gun, 9 + ThreadLocalRandom.current().nextInt(5));
        }
        if (state.ammo == 0) {
            player.playSound(player.getLocation(), "craftov:gun.empty", SoundCategory.PLAYERS, 0.6f, 1.2f);
        }
        return true;
    }

    private double currentSpread(Player player, GunState state) {
        GunSpec gun = state.gun;
        double spread = isAiming(player) ? gun.adsSpread() * (state.optic != null && state.optic.magnified() ? 0.8 : 1.0) : gun.hipSpread();
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
        int arms = plugin.health().activeFractures(player, true);
        if (arms > 0) {
            spread *= 1.0 + 0.35 * arms;
        }
        return spread;
    }

    private void recoil(Player player, GunState state) {
        GunSpec gun = state.gun;
        double scale = (isAiming(player) ? 0.7 : 1.0) * (player.isSneaking() ? 0.8 : 1.0)
                * (1.0 + 0.25 * plugin.health().activeFractures(player, true));
        float pitchKick = (float) (gun.recoil() * scale * (0.85 + ThreadLocalRandom.current().nextDouble() * 0.3));
        float yawKick = (float) ((ThreadLocalRandom.current().nextDouble() * 2 - 1) * gun.recoilH() * scale);
        Recoil.kick(player, yawKick, -pitchKick);
    }

    // ------------------------------------------------------------------ reload

    /** Reserve ammo of the gun's caliber in the player's carry cells. */
    public int reserve(Player player, GunSpec gun, @Nullable String preferType) {
        if (plugin.lobby().inRange(player)) {
            return RANGE_RESERVE;
        }
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

    /** Reserve shown (and never used up) on the shooting range. */
    public static final int RANGE_RESERVE = 999;

    public void startReload(Player player, GunState state, boolean cycleType) {
        if (state.reloading()) {
            return;
        }
        GunSpec gun = state.gun;
        List<String> types = ammoTypesCarried(player, gun);
        if (plugin.lobby().inRange(player)) {
            types = new ArrayList<>(types);
            for (ItemDef ammo : plugin.registry().ammoFor(gun.caliber())) {
                if (!types.contains(ammo.id())) {
                    types.add(ammo.id());
                }
            }
        }
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
        double duration = gun.reloadTicks() * (state.ammo == 0 ? 1.2 : 1.0) * plugin.operators().reloadMultiplier(player)
                * (1.0 + 0.2 * plugin.health().activeFractures(player, true));
        state.reloadStart = now;
        state.reloadEnd = now + Math.max(5, Math.round(duration));
        // The client animates the reload from this cooldown on its own (see tools/art/reload.py).
        player.setCooldown(ItemFactory.RELOAD_GROUP, (int) (state.reloadEnd - now));
        state.reloadType = type;
        state.triggerHeld = false;
        state.reloadEmpty = state.ammo == 0;
        reloadFoley(player, state, state.reloadEnd - now);
    }

    /** Handling sounds spread over the reload: magazine out and in, or one shell at a time for the pump gun. */
    private void reloadFoley(Player player, GunState state, long duration) {
        GunSpec gun = state.gun;
        if ("pump".equals(gun.action())) {
            int shells = Math.max(1, Math.min(6, gun.magazine() - state.ammo));
            for (int i = 0; i < shells; i++) {
                long at = Math.max(1, duration * (i + 1) / (shells + 2));
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (states.get(player.getUniqueId()) == state && state.reloading()) {
                        audio.foley(player, gun, "shell", 0.8f);
                    }
                }, at);
            }
            return;
        }
        audio.foley(player, gun, "magout", 0.9f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (states.get(player.getUniqueId()) == state && state.reloading()) {
                audio.foley(player, gun, "magin", 0.9f);
            }
        }, Math.max(2, duration * 2 / 3));
    }

    /** Closing sound of a finished reload: the bolt or pump is worked only if the magazine had run dry. */
    private void reloadDone(Player player, GunState state) {
        GunSpec gun = state.gun;
        if (!state.reloadEmpty) {
            return;
        }
        switch (gun.action()) {
            case "pump" -> audio.foley(player, gun, "pump", 0.9f);
            case "bolt" -> {
                audio.foley(player, gun, "bolt_open", 0.9f);
                Bukkit.getScheduler().runTaskLater(plugin, () -> audio.foley(player, gun, "bolt_close", 0.9f), 6L);
            }
            default -> audio.foley(player, gun, "charge", 0.9f);
        }
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

    /** Ends the client-side reload animation if the dropped state was mid-reload. */
    private static void stopReloadAnimation(Player player, @Nullable GunState state) {
        if (state != null && state.reloading()) {
            player.setCooldown(ItemFactory.RELOAD_GROUP, 0);
        }
    }

    private void finishReload(Player player, GunState state) {
        GunSpec gun = state.gun;
        String type = state.reloadType;
        state.reloadStart = -1;
        state.reloadEnd = -1;
        state.reloadType = null;
        if (type == null) {
            return;
        }
        CarryService carry = plugin.carry();
        if (plugin.lobby().inRange(player)) {
            // Free, unlimited ammo on the range; nothing is taken from the inventory.
            state.ammo = gun.magazine();
            state.ammoType = type;
            flush(player);
            reloadDone(player, state);
            return;
        }
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
        reloadDone(player, state);
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
        boolean scoped = state != null && state.optic != null && state.optic.magnified();
        if (on && scoped) {
            plugin.hud().scopeOverlay(player, state.optic.overlay());
        } else if (!on) {
            plugin.hud().scopeOverlay(player, null);
        }
        if (on && state != null) {
            applyZoom(player, state);
            player.playSound(player.getLocation(), "craftov:gun.ads", SoundCategory.PLAYERS, 0.5f, 1f);
        } else if (!on && sentZoom.remove(player.getUniqueId()) != null) {
            Nms.unzoom(player);
        }
    }

    /** Zoom level at which ADS looks through a scope (overlay + strong magnification) instead of iron sights. */
    public static final int SCOPE_ZOOM = 2;

    /** Field-of-view multiplier while aiming: the optic's magnification, or a slight zoom on iron sights. */
    private static double fovFor(GunState state) {
        if (state.optic != null) {
            return state.optic.fov();
        }
        return state.gun.zoom() <= 0 ? 0.86 : 0.8;
    }

    private void applyZoom(Player player, GunState state) {
        org.bukkit.attribute.AttributeInstance speed = player.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
        double move = speed == null ? 0.1 : speed.getValue();
        Double previous = sentZoom.get(player.getUniqueId());
        if (previous != null && Math.abs(previous - move) < 0.0005) {
            return;
        }
        if (Nms.zoom(player, fovFor(state), move)) {
            sentZoom.put(player.getUniqueId(), move);
        }
    }

    /** Explains, at most once a second, why a trigger pull did nothing. */
    private void hint(Player player, String text) {
        long now = Bukkit.getCurrentTick();
        if (now - lastHint.getOrDefault(player.getUniqueId(), -100L) >= 20) {
            lastHint.put(player.getUniqueId(), now);
            player.sendActionBar(Text.mm(text));
        }
    }

    /** Why this player cannot fire right now, or null if they can. */
    private @Nullable String fireBlocker(Player player) {
        if (plugin.raids().canFire(player)) {
            return null;
        }
        if (plugin.lobby().isLobby(player.getWorld())) {
            return "<#9AA0A6>大厅里不能开火，请到靶场入口进入个人靶场";
        }
        if (plugin.raids().isInRaid(player)) {
            return "<#9AA0A6>现在不能开火";
        }
        return "<#9AA0A6>只能在行动或靶场中开火";
    }

    // ------------------------------------------------------------------ input events

    /** Right mouse aims down sights while held. Left mouse (the trigger) is handled by {@link TriggerService}. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || !event.getAction().isRightClick()) {
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
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType().isInteractable() && !player.isSneaking()
                && event.getClickedBlock().getLocation().add(0.5, 0.5, 0.5).distanceSquared(player.getEyeLocation()) < 16) {
            return; // let doors, levers and buttons work while holding a gun
        }
        // Never cancel or deny: a denied use makes Paper resync the whole inventory, which wipes the
        // scope overlay (a fake helmet) five times a second. Paper guns have no vanilla use anyway.
        if (state.reloading() || !plugin.raids().canFire(player) || player.isSprinting()) {
            return;
        }
        adsUntil.put(player.getUniqueId(), (long) Bukkit.getCurrentTick() + 6);
        setAds(player, true);
    }

    /** Clicking straight at a close entity sends an attack, not a dig; it still pulls the trigger. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onLeftClickEntity(io.papermc.paper.event.player.PrePlayerAttackEntityEvent event) {
        Player player = event.getPlayer();
        if (bind(player) != null) {
            event.setCancelled(true);
            plugin.triggers().tap(player);
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
                stopReloadAnimation(player, states.remove(player.getUniqueId()));
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
        if (state == null || state.gun.modes().size() < 2) {
            return;
        }
        state.mode = (state.mode + 1) % state.gun.modes().size();
        player.playSound(player.getLocation(), "craftov:gun.mode", SoundCategory.PLAYERS, 0.7f, 1f);
        player.sendActionBar(Text.mm("<#F2C94C>射击模式: <white>" + state.fireMode().displayName()));
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        flush(event.getPlayer());
        GunState state = states.remove(event.getPlayer().getUniqueId());
        stopReloadAnimation(event.getPlayer(), state);
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
        adsUntil.remove(event.getPlayer().getUniqueId());
        sentZoom.remove(event.getPlayer().getUniqueId());
        lastHint.remove(event.getPlayer().getUniqueId());
        lastInventoryDrop.remove(event.getPlayer().getUniqueId());
    }
}
