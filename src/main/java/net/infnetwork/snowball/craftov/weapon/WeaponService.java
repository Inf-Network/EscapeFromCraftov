package net.infnetwork.snowball.craftov.weapon;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.util.Nms;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
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

    /** Ticks of camera turns kept per gun: enough for a round trip of up to about 850 ms. */
    private static final int TURN_TICKS = 20;
    /**
     * Degrees the view may sit away from where the last spray's kicks and recovery would put it before
     * a new spray counts as aimed somewhere else, and the old spray's recovery is dropped.
     */
    private static final double TURNED_AWAY = 2.0;
    /** What one shot costs from {@link GunState#shotBudget}: a minute in milliseconds. */
    private static final long SHOT_COST = 60_000L;
    /** Bloom a gun sheds each tick, in hand or put away. */
    private static final double BLOOM_DECAY = 0.08;

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
        /** The trigger went down while sprinting: the first shot waits until the gun is up again. */
        boolean pendingPress;
        /** The trigger was pulled again since the last shot: the next shot starts a new spray. */
        boolean freshPull;
        /** Rounds of the current three-round burst still to fire: they do, whether or not the trigger is held. */
        int burstLeft;
        /**
         * Rounds a multi-round report already playing still holds (see {@link GunAudio#roundsPerSample}):
         * they fire even if the trigger is let go, so what is heard is what is fired.
         */
        int sampleLeft;
        /**
         * Time banked toward the next shot, in milliseconds times rpm: a tick adds 50 x rpm and a shot
         * costs 60000. Whole numbers keep the rhythm exact, where 66.67 ms steps would drift a tick.
         */
        long shotBudget;
        long reloadStart = -1;
        long reloadEnd = -1;
        /**
         * The gun came into the hand at drawStart and is up at readyAt: until then it neither fires nor
         * aims (see {@link WeaponService#drawTicks}).
         */
        long drawStart;
        long readyAt;
        /** The gun is coming up and {@link WeaponService#drawn} has not run for it yet. */
        boolean drawPending;
        /** The reload cooldown, which plays the reload frames, has been sent for the reload under way. */
        boolean reloadShown;
        /** Last tick right mouse asked to aim while the gun came up: still held at readyAt, it aims then. */
        long aimAsked = -100;
        /**
         * Tick the server took the aim, and the ticks the sights take to come up from there (see
         * {@link WeaponService#adsTicks}); the view, the spread and the recoil follow that progress.
         */
        long adsStart = -1;
        int adsTicks = 1;
        /** The cooldown that runs the frames of the sights coming up has been sent (a tick after the mark). */
        boolean adsFrames;
        /** The sights are all the way up: the gun carries the aimed mark, a scope shows its picture. */
        boolean adsUp;
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
        /** First tick the gun may fire again on a trigger pull: it has cycled since the last shot. */
        long nextShotTick;
        /** Camera kick (degrees up, and right) of the current spray that the player has not pulled back down. */
        double kickUp;
        double kickRight;
        /**
         * The crosshair recovery heads for and never passes: where the spray's first shot was aimed,
         * lowered by whatever the previous spray's recovery still owed then. NaN while nothing is owed.
         */
        float anchorPitch = Float.NaN;
        float anchorYaw;
        /** What the previous spray's recovery still owed when this spray began (recoil-return applied). */
        double carryUp;
        double carryRight;
        /** Last tick's view above the anchor as the server read it, and how far the player turned it down then. */
        double lastAbove = Double.NaN;
        double lastOwn;
        /** The camera is drifting back: what is left to give back, and how much per tick. */
        boolean settling;
        double returnUp;
        double returnRight;
        double stepUp;
        double stepRight;
        /**
         * Camera turns sent each recent tick (degrees up, and right; kicks positive, recovery negative),
         * by tick: the client reports its view a round trip late, so these may not show in it yet.
         */
        final long[] turnTick = new long[TURN_TICKS];
        final double[] turnUp = new double[TURN_TICKS];
        final double[] turnRight = new double[TURN_TICKS];
        /**
         * Climb (degrees up, and right) that only the bullets get, on top of the crosshair, in automatic
         * fire: the camera never shows it, so a client that ignores the camera kick still cannot hold a
         * spray level. Once firing stops it comes back down at climbStep per tick.
         */
        double climbUp;
        double climbRight;
        boolean climbSettling;
        double climbStepUp;
        double climbStepRight;
        /** What is left of the last shot's camera kick (degrees up, and right), sent on the next tick. */
        double pendingUp;
        double pendingRight;
        /** Mounted optic, or the gun's built-in scope; null = iron sights. */
        final @Nullable OpticSpec optic;
        final @Nullable String opticName;
        final boolean range;
        /** A proving-ground armory gun: loads armory rounds only, and unloads them as armory rounds. */
        final boolean test;

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
            this.test = ItemFactory.isTest(stack);
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

        /** The gun is still coming up after being switched to. */
        public boolean drawing() {
            return Bukkit.getCurrentTick() < readyAt;
        }

        public double drawSecondsLeft() {
            return Math.max(0, (readyAt - Bukkit.getCurrentTick()) / 20.0);
        }

        public double drawProgress() {
            if (!drawing()) {
                return 1;
            }
            long now = Bukkit.getCurrentTick();
            return Math.max(0, Math.min(1.0, (now - drawStart) / (double) Math.max(1, readyAt - drawStart)));
        }
    }

    private final CraftovPlugin plugin;
    private final Ballistics ballistics;
    private final GunAudio audio;
    private final ImpactFx fx;
    private final MotionTracker motion = new MotionTracker();
    private final Map<UUID, GunState> states = new HashMap<>();
    /** What guns put away still owe, by gun uid (see {@link Stowed}). */
    private final Map<String, Stowed> stowed = new HashMap<>();
    private final Map<UUID, Boolean> ads = new HashMap<>();
    /** Right mouse repeats a use packet every 4 ticks while held; ADS ends shortly after they stop. */
    private final Map<UUID, Long> adsUntil = new HashMap<>();
    /**
     * Players let go of a scope: until this tick the scope's picture and the aimed mark stay while the
     * client's view widens back (it eases 0.5 of the way per tick), so the zoomed world never shows
     * without the scope around it.
     */
    private final Map<UUID, Long> scopeOut = new HashMap<>();
    private final Map<UUID, Long> lastHint = new HashMap<>();
    private final Map<UUID, Integer> controlTips = new HashMap<>();
    private final Map<UUID, Long> lastInventoryDrop = new HashMap<>();
    /** Last tick each player was sprinting: the gun takes a moment to come back up afterwards. */
    private final Map<UUID, Long> lastSprint = new HashMap<>();
    /** Last tick each player right-clicked an entity (really, or through a capture cell, see onInteract). */
    private final Map<UUID, Long> lastEntityUse = new HashMap<>();
    /**
     * Last tick a right-click on an entity did something (opened a box or a rack, started a revive). The
     * client follows such a click with a plain use of the gun in the same tick, which must not aim.
     */
    private final Map<UUID, Long> handledUse = new HashMap<>();
    /** How far a right-click reaches an entity: vanilla's entity interaction range. */
    private static final double USE_REACH = 3.0;
    /** Ticks between the use packets vanilla repeats while right mouse is held. */
    private static final int USE_REPEAT = 4;

    /**
     * What a gun keeps once its state is dropped (switched away from, dropped, or reset): the tick it may
     * fire again and its bloom as of {@code tick}. Without it, switching to another slot and back gave a
     * fresh state, so a bolt or pump gun fired again at once instead of after its cycle, and a long
     * spray's bloom was gone.
     */
    private record Stowed(UUID owner, long nextShotTick, double bloom, long tick) {
        double bloomAt(long now) {
            return Math.max(0, bloom - BLOOM_DECAY * Math.max(0, now - tick));
        }

        /** Nothing left to keep: the gun has cycled and its bloom has worn off. */
        boolean spent(long now) {
            return now >= nextShotTick && bloomAt(now) <= 0;
        }
    }

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

    /** How far the sights are up, 0 at the hip to 1 aimed: they take the gun's ads time to come up. */
    public double adsProgress(Player player) {
        GunState state = states.get(player.getUniqueId());
        if (!isAiming(player) || state == null || state.adsStart < 0) {
            return 0;
        }
        return Math.max(0, Math.min(1, (Bukkit.getCurrentTick() - state.adsStart) / (double) Math.max(1, state.adsTicks)));
    }

    /**
     * Ticks the sights take to come up (weapons.ads.ticks by ads-class: a pistol is quickest, a bolt
     * rifle slowest), plus a little for an optic, more the stronger it is (weapons.ads.optic-ticks).
     */
    int adsTicks(GunState state) {
        var config = plugin.getConfig();
        String kind = state.gun.adsClass();
        int fallback = switch (kind) {
            case "pistol" -> 4;
            case "smg" -> 5;
            case "dmr", "lmg" -> 8;
            case "bolt" -> 9;
            default -> 6; // rifle, shotgun
        };
        int ticks = config.getInt("weapons.ads.ticks." + kind, fallback);
        OpticSpec.Tier tier = state.optic == null ? OpticSpec.Tier.IRON : state.optic.tier();
        int extra = switch (tier) {
            case IRON -> 0;
            case REFLEX -> 1;
            case LOW -> 2;
            case HIGH -> 3;
        };
        return Math.max(1, ticks + config.getInt("weapons.ads.optic-ticks." + tier.key(), extra));
    }

    /** Vanilla walking pace in blocks per tick, the unit of {@link #horizontalSpeed}. */
    public static final double WALK_SPEED = MotionTracker.WALK_SPEED;

    /**
     * How fast the player is really moving, in blocks per tick, measured from their position each tick
     * (vanilla walking is about {@value #WALK_SPEED}). Bukkit's velocity reads about zero while a player
     * walks or runs, so this is what moving-target checks use.
     */
    public double horizontalSpeed(Player player) {
        return motion.speed(player);
    }

    /**
     * How much aiming slows the player (a multiplier of their speed): by the gun's ads-class
     * (weapons.ads.speed: a pistol barely, a bolt rifle by half), times the optic's
     * (weapons.ads.optic-speed: a long scope a little more). 1 while not aiming.
     */
    public double adsSpeedFactor(Player player) {
        GunState state = state(player);
        if (!isAiming(player) || state == null) {
            return 1.0;
        }
        var config = plugin.getConfig();
        String kind = state.gun.adsClass();
        double fallback = switch (kind) {
            case "pistol" -> 0.85;
            case "smg", "shotgun" -> 0.8;
            case "dmr" -> 0.6;
            case "lmg" -> 0.55;
            case "bolt" -> 0.5;
            default -> 0.7; // rifle
        };
        OpticSpec.Tier tier = state.optic == null ? OpticSpec.Tier.IRON : state.optic.tier();
        double optic = switch (tier) {
            case IRON, REFLEX -> 1.0;
            case LOW -> 0.9;
            case HIGH -> 0.8;
        };
        double factor = config.getDouble("weapons.ads.speed." + kind, fallback) * config.getDouble("weapons.ads.optic-speed." + tier.key(), optic);
        return Math.max(0.1, Math.min(1.0, factor));
    }

    /** The aimed cone is the gun's ads-spread times this for its optic (weapons.ads.optic-spread). */
    private double opticSpread(GunState state) {
        OpticSpec.Tier tier = state.optic == null ? OpticSpec.Tier.IRON : state.optic.tier();
        double fallback = switch (tier) {
            case IRON -> 1.0;
            case REFLEX -> 0.95;
            case LOW -> 0.85;
            case HIGH -> 0.8;
        };
        return plugin.getConfig().getDouble("weapons.ads.optic-spread." + tier.key(), fallback);
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
            stow(player);
            setAds(player, false);
        }
        if (def == null || !def.isGun() || uid == null) {
            return null;
        }
        GunState state = new GunState(uid, slot, def, held, plugin.registry());
        long now = Bukkit.getCurrentTick();
        state.drawStart = now;
        state.readyAt = now + drawTicks(state.gun);
        Stowed kept = stowed.remove(uid);
        if (kept != null) {
            state.nextShotTick = kept.nextShotTick();
            state.bloom = kept.bloomAt(now);
        }
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info(String.format("[debug] %s draws %s tick=%d ready=%d next=%d bloom=%.2f",
                    player.getName(), def.id(), now, state.readyAt, state.nextShotTick, state.bloom));
        }
        states.put(player.getUniqueId(), state);
        // A mark that left the inventory mid-draw or mid-reload (put in a box) would keep a gun with no
        // draw time (weapons.draw-ticks 0) from ever aiming, or show it reloading: whatever mark the
        // stack carries is replaced whenever the gun is bound.
        state.drawPending = state.drawing();
        ItemFactory.setHandMark(held, state.drawing() ? ItemFactory.MARK_DRAW : ItemFactory.MARK_NONE);
        int tips = controlTips.getOrDefault(player.getUniqueId(), 0);
        if (tips < 3) {
            controlTips.put(player.getUniqueId(), tips + 1);
            // A hint, not a result: it must not push aside the answer to whatever put the gun in hand.
            Notify.hint(player, "<white>左键<muted> 开火  <white>右键<muted> 瞄准  <white>Q<muted> 换弹  "
                    + "<white>潜行+Q<muted> 换弹种  <white>F<muted> 射击模式");
        }
        return state;
    }

    /**
     * Ticks a gun takes to come up once it is in the hand, by its action (weapons.draw-ticks): quick for
     * a pistol, slow for a machine gun or a bolt-action rifle. Switching guns used to be free, so going
     * to the sidearm instead of reloading cost nothing. Not in items.yml, whose copy on the server is
     * not updated from the jar.
     */
    private int drawTicks(GunSpec gun) {
        int fallback = switch (gun.action()) {
            case "pistol", "pistol_heavy" -> 6;
            case "smg", "pdw" -> 10;
            case "pump" -> 14;
            case "lmg", "bolt" -> 18;
            default -> 12; // ak, ar, dmr
        };
        return Math.max(0, plugin.getConfig().getInt("weapons.draw-ticks." + gun.action(), fallback));
    }

    /** Writes the in-memory magazine back onto the stack (wherever it is now). */
    public void flush(Player player) {
        GunState state = states.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        ItemStack stack = stackOf(player, state);
        if (stack == null) {
            return;
        }
        if (ItemFactory.ammo(stack) != state.ammo || !java.util.Objects.equals(ItemFactory.ammoType(stack), state.ammoType)
                || ItemFactory.mode(stack) != state.mode) {
            ItemFactory.setMode(stack, state.mode);
            plugin.items().setAmmo(stack, state.ammo, state.ammo > 0 ? state.ammoType : state.ammoType);
        }
    }

    /**
     * Puts the magazine in memory into a record of the player's bound gun (by its uid), so a snapshot
     * of the inventory counts the rounds really loaded without writing the stack, which would lower
     * and raise the gun in the hand.
     */
    public void liveMagazine(Player player, ItemRecord record) {
        GunState state = states.get(player.getUniqueId());
        if (state != null && state.uid.equals(record.uid)) {
            record.ammo = state.ammo;
            record.ammoType = state.ammoType;
        }
    }

    /** The live stack of the state's gun in the player's inventory (its slot first), or null once it left. */
    private @Nullable ItemStack stackOf(Player player, GunState state) {
        PlayerInventory inv = player.getInventory();
        ItemStack stack = inv.getItem(state.slot);
        if (state.uid.equals(ItemFactory.uid(stack))) {
            return stack;
        }
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack candidate = inv.getItem(i);
            if (state.uid.equals(ItemFactory.uid(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /** Drops cached state entirely (raid transitions, death). */
    public void reset(Player player) {
        flush(player);
        stow(player);
        setAds(player, false);
    }

    /**
     * Drops the player's gun state, keeping its cycle and bloom for when the gun is next bound (see
     * {@link Stowed}), and ends a reload animation it had running. Returns the dropped state.
     */
    private @Nullable GunState stow(Player player) {
        GunState state = states.remove(player.getUniqueId());
        if (state == null) {
            return null;
        }
        long now = Bukkit.getCurrentTick();
        // The rest of the last shot's kick still lands: switching away right after a shot dodges none of it.
        kickPending(player, state, now);
        if (now < state.nextShotTick || state.bloom > 0) {
            stowed.put(state.uid, new Stowed(player.getUniqueId(), state.nextShotTick, state.bloom, now));
        }
        // The cooldown belongs to every gun: the next one must not show this one's kick, reload or
        // sights coming up.
        if (state.reloading() || now - state.lastShotTick < state.gun.kickTicks() || state.adsStart >= 0 && !state.adsUp) {
            player.setCooldown(ItemFactory.RELOAD_GROUP, 0);
        }
        setMark(player, state, ItemFactory.MARK_NONE);
        return state;
    }

    /** The hand mark the gun's state calls for: reloading, coming up, aiming, aimed, or none. */
    private int wantedMark(Player player, GunState state) {
        if (state.reloading()) {
            return ItemFactory.MARK_RELOAD;
        }
        if (state.drawing()) {
            return ItemFactory.MARK_DRAW;
        }
        if (isAiming(player) && state.adsStart >= 0) {
            return state.adsUp ? ItemFactory.MARK_AIMED : ItemFactory.MARK_AIM;
        }
        return ItemFactory.MARK_NONE;
    }

    /**
     * Puts this hand mark on the gun's stack, if it is in the inventory and carries another. A gun dropped
     * with Q (a reload) is out of the inventory while the drop event runs and put back after: it is
     * marked from the next tick (see {@link #showReload}).
     */
    private void setMark(Player player, GunState state, int mark) {
        ItemStack stack = stackOf(player, state);
        if (stack != null && ItemFactory.handMark(stack) != mark) {
            ItemFactory.setHandMark(stack, mark);
        }
    }

    /**
     * The gun is up: its model may aim and kick again, and right mouse held through the draw aims at
     * once rather than with the next use packet, which on a scoped gun would leave up to 4 ticks of
     * neither gun nor scope (the model hides the gun as soon as the mark is gone).
     */
    private void drawn(Player player, GunState state, long now) {
        state.drawPending = false;
        setMark(player, state, wantedMark(player, state));
        if (now - state.aimAsked <= USE_REPEAT + 1 && !state.reloading() && plugin.raids().canFire(player)
                && !player.isSprinting()) {
            holdAim(player, now);
            setAds(player, true);
        }
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info(String.format("[debug] %s up %s tick=%d aim=%b",
                    player.getName(), state.def.id(), now, isAiming(player)));
        }
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        long now = Bukkit.getCurrentTick();
        if (now % 20 == 0) {
            stowed.values().removeIf(kept -> kept.spent(now));
        }
        if (!scopeOut.isEmpty()) {
            scopeOut.entrySet().removeIf(e -> {
                if (now < e.getValue()) {
                    return false;
                }
                Player out = Bukkit.getPlayer(e.getKey());
                if (out != null && !isAiming(out)) {
                    lowerScope(out);
                }
                return true;
            });
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            motion.sample(player);
            if (sprinting(player)) {
                lastSprint.put(player.getUniqueId(), now);
            }
            GunState state = bind(player);
            if (state == null) {
                continue;
            }
            kickPending(player, state, now);
            settle(player, state, now);
            if (state.drawPending && !state.drawing()) {
                drawn(player, state, now);
            }
            if (state.reloading()) {
                if (now >= state.reloadEnd) {
                    finishReload(player, state);
                } else if (!state.reloadShown) {
                    showReload(player, state, now);
                }
                continue;
            }
            boolean held = plugin.triggers().held(player);
            if (plugin.getConfig().getBoolean("debug", false) && state.triggerHeld) {
                plugin.getLogger().info("[debug] " + player.getName() + " held=" + held + " uid=" + state.uid + " ammo=" + state.ammo
                        + " mode=" + state.fireMode() + " budget=" + state.shotBudget / Math.max(1, state.gun.rpm()) + " canAttack=" + plugin.raids().canAttack(player)
                        + " interval=" + state.gun.millisBetweenShots() + " speed=" + String.format("%.3f", motion.speed(player))
                        + " bloom=" + String.format("%.2f", state.bloom));
            }
            if (isAiming(player)) {
                if (held) {
                    holdAim(player, now);
                } else if (now > adsUntil.getOrDefault(player.getUniqueId(), 0L)) {
                    endAds(player, true);
                }
            }
            if (isAiming(player)) {
                raise(player, state, now);
            }
            if (!held && state.triggerHeld) {
                release(state, !plugin.triggers().stale(player));
            }
            state.bloom = Math.max(0, state.bloom - BLOOM_DECAY);
            if (state.fireMode() != FireMode.BURST) {
                state.burstLeft = 0;
            }
            if (state.fireMode().singleAction()) {
                state.sampleLeft = 0;
            }
            if (!state.triggerHeld && state.burstLeft <= 0 && state.sampleLeft <= 0) {
                continue;
            }
            if (sprintLocked(player)) {
                // Still held when the sprint ends: the gun fires once it is back up. A burst does not resume.
                sprintHint(player);
                state.shotBudget = 0;
                state.burstLeft = 0;
                state.sampleLeft = 0;
                continue;
            }
            if (state.drawing()) {
                continue; // a pull while the gun comes up fires once it is up, if still held (see press)
            }
            if (state.pendingPress) {
                state.pendingPress = false;
                firstShot(player, state);
                continue;
            }
            FireMode mode = state.fireMode();
            GunSpec gun = state.gun;
            state.shotBudget += 50L * gun.rpm();
            int guard = 0;
            while (state.shotBudget >= SHOT_COST && guard++ < 4) {
                if (mode.singleAction() || mode == FireMode.BURST) {
                    if (mode == FireMode.BURST && state.burstLeft > 0) {
                        state.burstLeft--;
                    } else {
                        state.shotBudget = Math.min(state.shotBudget, SHOT_COST);
                        break;
                    }
                } else if (!state.triggerHeld && state.sampleLeft <= 0) {
                    break; // let go: automatic fire only finishes the rounds already heard
                }
                state.shotBudget -= SHOT_COST;
                if (!shoot(player, state)) {
                    state.shotBudget = 0;
                    state.burstLeft = 0;
                    state.sampleLeft = 0;
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
            if (plugin.downed().isReviving(player)) {
                plugin.downed().refusedFire(player);
            }
            hint(player, blocker);
            return;
        }
        if (state.reloading()) {
            hint(player, "<accent>换弹中…");
            return;
        }
        press(player, state);
    }

    /**
     * Left mouse went up. {@code letGo} is false when the trigger only timed out (no abort packet, see
     * {@link TriggerService}): the gun stops, but a semi-automatic does not take the swings that a lag
     * spike delivers late as a fresh pull.
     */
    public void triggerUp(Player player, boolean letGo) {
        GunState state = states.get(player.getUniqueId());
        if (state != null) {
            release(state, letGo);
        }
    }

    private void release(GunState state, boolean letGo) {
        state.triggerHeld = false;
        if (letGo) {
            state.needsRelease = false;
        }
        state.pendingPress = false;
        // A burst under way still fires its remaining rounds (see tick): a quick click gets all three.
    }

    private void press(Player player, GunState state) {
        // A pull during a burst does nothing: the next burst needs a click after this one has fired.
        if (state.reloading() || state.needsRelease || state.burstLeft > 0) {
            return;
        }
        if (state.sampleLeft > 0) {
            // Pulled again while the rounds of the last report are still firing: the spray just goes on.
            state.triggerHeld = true;
            return;
        }
        state.triggerHeld = true;
        state.freshPull = true;
        if (state.fireMode().singleAction() || state.fireMode() == FireMode.BURST) {
            state.needsRelease = true;
        }
        if (sprintLocked(player)) {
            // A pull while sprinting does nothing; kept held, it fires once the gun is back up (see tick).
            state.pendingPress = true;
            sprintHint(player);
            return;
        }
        if (state.drawing()) {
            // Likewise while the gun comes up after a switch: no shot until it is up.
            state.pendingPress = true;
            hint(player, "<muted>拔枪中…");
            return;
        }
        firstShot(player, state);
    }

    /**
     * The shot a trigger pull fires at once, unless the gun has not cycled since the last one: for semi,
     * bolt and pump guns this is the only limit on the rate of fire. The cycle outlives switching away
     * and back (see {@link Stowed}).
     */
    private void firstShot(Player player, GunState state) {
        if (Bukkit.getCurrentTick() >= state.nextShotTick) {
            state.shotBudget = 0;
            state.burstLeft = state.fireMode() == FireMode.BURST ? 2 : 0;
            shoot(player, state);
        }
    }

    // ------------------------------------------------------------------ shooting

    private boolean shoot(Player player, GunState state) {
        if (!plugin.raids().canAttack(player) || sprintLocked(player)) {
            return false;
        }
        GunSpec gun = state.gun;
        if (state.ammo <= 0) {
            audio.foley(player, gun, "dry", 0.7f);
            state.triggerHeld = false;
            state.needsRelease = true;
            if (reserve(player, state) > 0) {
                startReload(player, state, false);
            } else {
                hint(player, "<danger>没有 " + gun.caliber() + " 弹药 <muted>· 去商人买或在物资箱里找");
            }
            return false;
        }
        state.ammo--;
        if (!state.test) {
            plugin.raids().usedUp(player, state.ammoType, 1);
        }
        state.lastShotTick = Bukkit.getCurrentTick();
        state.nextShotTick = state.lastShotTick + gun.ticksBetweenShots();
        plugin.raids().onAttack(player);
        ItemDef ammoDef = plugin.registry().get(state.ammoType);
        AmmoSpec ammo = ammoDef == null ? null : ammoDef.ammo();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        Vector bore = bore(eye, state);
        double spread = currentSpread(player, state);
        net.infnetwork.snowball.craftov.raid.RaidPlayer session = plugin.raids().session(player);
        if (session != null && session.active()) {
            // The raid report counts the shots fired with movement costing no accuracy: stopped first.
            session.shots++;
            if (moveLevel(player) <= 0) {
                session.stillShots++;
            }
        }
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info(String.format("[debug] %s shot spread=%.3f speed=%.3f move=%.2f aim=%b ground=%b pitch=%.2f climb=%.2f bloom=%.2f tick=%d level=%.2f",
                    player.getName(), spread, motion.speed(player), motion.moveFactor(player), isAiming(player), player.isOnGround(),
                    eye.getPitch(), state.climbUp, state.bloom, Bukkit.getCurrentTick(), moveLevel(player)));
        }
        for (int i = 0; i < gun.pellets(); i++) {
            Vector pellet = Ballistics.spread(bore, gun.pellets() > 1 ? Math.max(spread, gun.hipSpread() * 0.75) : spread);
            ballistics.fire(player, eye, pellet, gun, ammo, state.def.name(), i == 0 || gun.pellets() <= 3);
        }
        Ballistics.muzzle(player, dir);
        // The first-person kick and muzzle flash: the item model plays them while this cooldown counts
        // down on the client, so they follow the shots the server fires, not the trigger finger. While
        // the sights come up the same cooldown runs their frames instead, so a shot then kicks the
        // camera only.
        if (!isAiming(player) || state.adsUp) {
            player.setCooldown(ItemFactory.RELOAD_GROUP, gun.kickTicks());
        }
        Location muzzle = Ballistics.muzzlePoint(player, dir);
        audio.shot(player, muzzle, gun, state.suppressed, reportRounds(state));
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
            // Not written back to the stack here: a changed stack in the hand makes the client lower and
            // raise the gun. It is written whenever the item could move (see flush).
            player.playSound(player.getLocation(), "craftov:gun.empty", SoundCategory.PLAYERS, 0.6f, 1.2f);
        }
        return true;
    }

    /**
     * Rounds the report of the shot just fired holds: 0 while a multi-round sample from an earlier shot
     * still covers it; in automatic fire at a rate the pack has a sample for, that many rounds when that
     * many will surely follow (the magazine holds them, a burst has them left; the trigger cannot cut
     * them short, see tick); otherwise 1.
     */
    private static int reportRounds(GunState state) {
        if (state.sampleLeft > 0) {
            state.sampleLeft--;
            return 0;
        }
        FireMode mode = state.fireMode();
        if (mode != FireMode.AUTO && mode != FireMode.BURST) {
            return 1;
        }
        int rounds = GunAudio.roundsPerSample(state.gun);
        int coming = mode == FireMode.BURST ? state.burstLeft : Integer.MAX_VALUE;
        if (rounds > 1 && state.ammo >= rounds - 1 && coming >= rounds - 1) {
            state.sampleLeft = rounds - 1;
            return rounds;
        }
        return 1;
    }

    /**
     * Cone half-angle of the next shot. Moving widens it by the gun's own amount (items.yml `move`, in
     * degrees, half of it while aiming) in proportion to how close the player is to their full walking
     * pace past a small dead zone (see {@link #moveLevel}): a pistol barely minds, a sniper rifle
     * cannot hit a thing on the move, and stopping before the shot puts it back.
     */
    private double currentSpread(Player player, GunState state) {
        GunSpec gun = state.gun;
        double up = adsProgress(player);
        boolean aiming = up >= 1;
        var config = plugin.getConfig();
        double aimed = gun.adsSpread() * opticSpread(state);
        // The cone narrows as the sights come up, and is the aimed one only once they are.
        double spread = gun.hipSpread() + (aimed - gun.hipSpread()) * up;
        if (player.isSneaking()) {
            spread *= config.getDouble("weapons.spread.sneak", 0.7);
        }
        if (!player.isOnGround()) {
            spread *= config.getDouble("weapons.spread.airborne", 2.5);
        }
        if (state.fireMode() != FireMode.BOLT) {
            spread += state.bloom * (aiming ? 0.25 : 0.6);
        }
        spread += gun.moveSpread() * moveLevel(player) * (aiming ? config.getDouble("weapons.spread.move-ads-share", 0.5) : 1.0);
        int arms = plugin.health().activeFractures(player, true);
        if (arms > 0) {
            spread *= 1.0 + 0.35 * arms;
        }
        return spread;
    }

    /**
     * How much moving counts against accuracy, 0 to 1: how close the player is to their full walking
     * pace (measured on the server, against what their movement-speed attribute allows right now, so a
     * player slowed by aiming or armour who walks as fast as they can counts as fully moving), less a
     * dead zone (weapons.spread.move-deadzone of that pace) so the last ticks of stopping and a nudge do
     * not count. Vanilla movement stops in about two ticks when the opposite key is pressed: stopping
     * before the shot is the skill. Sneaking walks at the sneaking-speed attribute's share of that pace
     * (0.3), over the dead zone, but costs nothing either: while sneaking the dead zone is that share
     * and a tenth more, so only a sneak faster than the client allows (a cheat) counts.
     */
    private double moveLevel(Player player) {
        double dead = Math.max(0, Math.min(0.9, plugin.getConfig().getDouble("weapons.spread.move-deadzone", 0.25)));
        if (player.isSneaking()) {
            AttributeInstance sneak = player.getAttribute(Attribute.SNEAKING_SPEED);
            dead = Math.max(dead, Math.min(0.9, (sneak == null ? 0.3 : sneak.getValue()) + 0.1));
        }
        return Math.max(0, Math.min(1, (motion.moveFactor(player) - dead) / (1 - dead)));
    }

    /** Cone half-angle (degrees) the player's held gun would fire with right now, or -1 without one. */
    public double spreadNow(Player player) {
        GunState state = states.get(player.getUniqueId());
        return state == null ? -1 : currentSpread(player, state);
    }

    /** Where the bullets go: the crosshair, raised by the climb only the bullets get. */
    private static Vector bore(Location eye, GunState state) {
        if (state.climbUp == 0 && state.climbRight == 0) {
            return eye.getDirection();
        }
        Location raised = eye.clone();
        raised.setPitch((float) Math.max(-90, eye.getPitch() - state.climbUp));
        raised.setYaw((float) (eye.getYaw() + state.climbRight));
        return raised.getDirection();
    }

    /**
     * Kicks the camera. In automatic fire (full auto and bursts) weapons.recoil.muzzle-share of the kick
     * goes to the bullets alone instead (see {@link #climb}): the camera kick is only a packet the client
     * may ignore, and this part still lifts the shots. Single shots kick the camera only, so a scope
     * held on target after a semi-auto shot is where the next one goes.
     */
    private void recoil(Player player, GunState state) {
        GunSpec gun = state.gun;
        var config = plugin.getConfig();
        long now = Bukkit.getCurrentTick();
        kickPending(player, state, now);
        double scale = (1.0 + (config.getDouble("weapons.recoil.ads", 0.6) - 1.0) * adsProgress(player))
                * (player.isSneaking() ? config.getDouble("weapons.recoil.sneak", 0.8) : 1.0)
                * (1.0 + 0.25 * plugin.health().activeFractures(player, true));
        double up = gun.recoil() * scale * (0.85 + ThreadLocalRandom.current().nextDouble() * 0.3);
        double right = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * gun.recoilH() * scale;
        boolean automatic = state.fireMode() == FireMode.AUTO || state.fireMode() == FireMode.BURST;
        double share = automatic ? Math.max(0, Math.min(1, config.getDouble("weapons.recoil.muzzle-share", 0.2))) : 0;
        if (share > 0) {
            climb(state, up * share, right * share);
        }
        double pitchKick = up * (1 - share);
        double yawKick = right * (1 - share);
        if (state.freshPull || state.settling || Float.isNaN(state.anchorPitch)) {
            rebase(player, state, now);
        }
        state.freshPull = false;
        // The camera eases into the kick: this share now, the rest on the next tick, so a shot reads as
        // a quick climb over two frames of the server's 20 a second rather than one jump.
        double first = Math.max(0, Math.min(1, config.getDouble("weapons.recoil.first-tick-share", 0.6)));
        kick(player, state, now, pitchKick * first, yawKick * first);
        state.pendingUp += pitchKick * (1 - first);
        state.pendingRight += yawKick * (1 - first);
    }

    /** Turns the camera by a kick (degrees up, and right) and counts it toward the spray's recovery. */
    private static void kick(Player player, GunState state, long now, double up, double right) {
        if (Math.abs(up) < 1e-4 && Math.abs(right) < 1e-4) {
            return;
        }
        state.kickUp += up;
        state.kickRight += right;
        turned(state, now, up, right);
        Recoil.kick(player, (float) right, (float) -up);
    }

    /** The part of the last shot's kick held over to this tick (see {@link #recoil}). */
    private static void kickPending(Player player, GunState state, long now) {
        double up = state.pendingUp;
        double right = state.pendingRight;
        state.pendingUp = state.pendingRight = 0;
        kick(player, state, now, up, right);
    }

    /**
     * Starts a new spray: a new trigger pull, or firing again once the last spray began drifting back.
     * Whatever the last spray's recovery still owes carries over, measured from where the player aims
     * now, so recovery heads for that crosshair lowered by the carry, which never lies past the one the
     * last spray started from. If the player turned the view away since (to another target, say), the
     * carry is dropped: drifting on would pull them off what they turned to.
     */
    private void rebase(Player player, GunState state, long now) {
        double inUp = inFlight(state, state.turnUp, player, now);
        double inRight = inFlight(state, state.turnRight, player, now);
        double carryUp = 0;
        double carryRight = 0;
        if (!Float.isNaN(state.anchorPitch)) {
            double above = state.anchorPitch - player.getPitch() + inUp;
            double right = wrapDegrees(player.getYaw() - state.anchorYaw) + inRight;
            fold(state, above, right);
            if (!state.settling) {
                owe(state, above, right);
            }
            // Folding took out what the player pulled down; anything left over is the view turned away.
            double away = Math.max(above - state.carryUp - state.kickUp, Math.abs(right - state.carryRight - state.kickRight));
            if (away <= TURNED_AWAY) {
                carryUp = Math.max(0, Math.min(state.returnUp, above));
                carryRight = state.returnRight >= 0 ? Math.max(0, Math.min(state.returnRight, right))
                        : Math.min(0, Math.max(state.returnRight, right));
            }
        }
        // Where the view will be once the turns still in flight land, then lowered by the carry.
        float anchor = (float) (player.getPitch() - inUp + carryUp);
        state.lastAbove = Float.isNaN(state.anchorPitch) ? Double.NaN : state.lastAbove + (anchor - state.anchorPitch);
        state.anchorPitch = anchor;
        state.anchorYaw = (float) (player.getYaw() + inRight - carryRight);
        state.carryUp = carryUp;
        state.carryRight = carryRight;
        state.kickUp = state.kickRight = 0;
        state.returnUp = state.returnRight = 0;
        state.settling = false;
    }

    /**
     * What the player has pulled back down of this spray's kick (or turned past the anchor) is theirs:
     * it is no longer owed. {@code above} and {@code right} are where the view sits from the anchor.
     */
    private static void fold(GunState state, double above, double right) {
        state.kickUp = Math.min(state.kickUp, Math.max(0, above - state.carryUp));
        double beyond = right - state.carryRight;
        state.kickRight = state.kickRight >= 0 ? Math.min(state.kickRight, Math.max(0, beyond))
                : Math.max(state.kickRight, Math.min(0, beyond));
    }

    /** What recovery gives back for the spray: the carry, plus the gun's recoil-return of the kick still owed. */
    private static void owe(GunState state, double above, double right) {
        double share = state.gun.recoilReturn();
        double ownUp = Math.max(0, Math.min(state.kickUp, above - state.carryUp));
        double beyond = right - state.carryRight;
        double ownRight = state.kickRight >= 0 ? Math.max(0, Math.min(state.kickRight, beyond))
                : Math.min(0, Math.max(state.kickRight, beyond));
        state.returnUp = state.carryUp + ownUp * share;
        state.returnRight = state.carryRight + ownRight * share;
    }

    /** Records a camera turn sent this tick (degrees up, and right). */
    private static void turned(GunState state, long now, double up, double right) {
        int slot = (int) Math.floorMod(now, (long) TURN_TICKS);
        if (state.turnTick[slot] != now) {
            state.turnTick[slot] = now;
            state.turnUp[slot] = 0;
            state.turnRight[slot] = 0;
        }
        state.turnUp[slot] += up;
        state.turnRight[slot] += right;
    }

    /** The camera turn up sent in the given tick, if it is still on record. */
    private static double turnedUp(GunState state, long tick) {
        int slot = (int) Math.floorMod(tick, (long) TURN_TICKS);
        return state.turnTick[slot] == tick ? state.turnUp[slot] : 0;
    }

    /** Whole ticks of the player's round trip. */
    private static int rttTicks(Player player) {
        return (int) Math.ceil(Math.max(0, player.getPing()) / 50.0);
    }

    /** Ticks of camera turns the view the client reports may not show yet: a round trip, plus the tick phases. */
    private static int inFlightTicks(Player player) {
        return Math.min(TURN_TICKS - 1, 2 + rttTicks(player));
    }

    /**
     * Camera turns of the last round trip and a bit, which the view the client reports may not show yet:
     * what the server reads now plus this is where the view ends up once they land.
     */
    private static double inFlight(GunState state, double[] turns, Player player, long now) {
        int ticks = inFlightTicks(player);
        double sum = 0;
        for (int i = 0; i < TURN_TICKS; i++) {
            if (now - state.turnTick[i] <= ticks) {
                sum += turns[i];
            }
        }
        return sum;
    }

    /**
     * Recoil recovery. A few ticks after the gun stops firing (weapons.recoil.return-delay-ticks, plus
     * the player's round trip so the server has seen what they did on letting go) the camera drifts back
     * toward where it pointed before the spray, return-per-tick of the kick each tick, until the gun's
     * recoil-return share of it is given back. What the player pulled down themselves, while firing or
     * after, is not given back again, and the drift never goes past the crosshair the spray started
     * from: it counts the turns the client has not reported back yet, and while the player is pulling
     * down it stops short by as far as they would get in a round trip at that pace.
     */
    private void settle(Player player, GunState state, long now) {
        var config = plugin.getConfig();
        boolean spraying = state.triggerHeld && state.fireMode() == FireMode.AUTO && !state.reloading();
        int rtt = rttTicks(player);
        boolean firing = spraying || now - state.lastShotTick < config.getInt("weapons.recoil.return-delay-ticks", 3) + rtt;
        settleClimb(state, firing, config.getInt("weapons.recoil.muzzle-return-ticks", 6));
        if (Float.isNaN(state.anchorPitch)) {
            state.lastAbove = Double.NaN;
            return;
        }
        // How fast the player is turning the view down themselves: the change the server sees, less
        // the turn it sent that should just have shown up in it. Kept up while firing too, so it is
        // known the moment the drift starts.
        double raw = state.anchorPitch - player.getPitch();
        double own = Double.isNaN(state.lastAbove) ? 0 : state.lastAbove - raw + turnedUp(state, now - 1 - rtt);
        double pulling = Math.max(0, (own + state.lastOwn) / 2);
        state.lastOwn = own;
        state.lastAbove = raw;
        // Degrees the view will sit above / right of the anchor once the turns in flight land.
        double above = raw + inFlight(state, state.turnUp, player, now);
        double right = wrapDegrees(player.getYaw() - state.anchorYaw) + inFlight(state, state.turnRight, player, now);
        fold(state, above, right);
        if (firing) {
            return;
        }
        if (!state.settling) {
            owe(state, above, right);
            double rate = Math.max(0, Math.min(1, config.getDouble("weapons.recoil.return-per-tick", 0.1)));
            double share = Math.max(0.1, state.gun.recoilReturn());
            // A long spray's recovery would otherwise jump several degrees a tick at 20 frames a second:
            // it takes longer instead.
            double most = Math.max(0.05, config.getDouble("weapons.recoil.return-max-step", 1.2));
            state.settling = true;
            state.stepUp = Math.min(most, state.returnUp * rate / share);
            state.stepRight = Math.min(most, Math.abs(state.returnRight) * rate / share);
        }
        double margin = pulling * inFlightTicks(player);
        double up = Math.max(0, Math.min(Math.min(state.stepUp, state.returnUp), above - margin));
        double back = Math.min(state.stepRight, Math.abs(state.returnRight));
        double room = state.returnRight >= 0 ? right : -right;
        double side = Math.copySign(Math.max(0, Math.min(back, room)), state.returnRight);
        if (up >= 1e-3 || Math.abs(side) >= 1e-3) {
            Recoil.kick(player, (float) -side, (float) up);
            turned(state, now, -up, -side);
            state.returnUp -= up;
            state.returnRight -= side;
            state.kickUp -= up;
            state.kickRight -= side;
        }
        boolean upDone = state.returnUp < 1e-3 || above < 1e-3;
        boolean sideDone = Math.abs(state.returnRight) < 1e-3 || room < 1e-3;
        if (upDone && sideDone) {
            // All given back, or the view is already at (or past) the anchor.
            state.anchorPitch = Float.NaN;
            state.lastAbove = Double.NaN;
            state.kickUp = state.kickRight = 0;
            state.carryUp = state.carryRight = 0;
            state.returnUp = state.returnRight = 0;
            state.settling = false;
        }
    }

    /**
     * Adds a round's lift to the bullets' own climb. Each round lifts them less the higher they already
     * are (weapons.recoil.muzzle-soft is the climb at which a round's lift has halved): a long spray
     * stays only a little above the crosshair, yet keeps creeping up to the end of the magazine instead
     * of settling at a fixed height that could simply be aimed under. muzzle-max is a hard ceiling no
     * magazine reaches.
     */
    private void climb(GunState state, double up, double right) {
        var config = plugin.getConfig();
        double soft = Math.max(0.05, config.getDouble("weapons.recoil.muzzle-soft", 0.5));
        double cap = Math.max(0, config.getDouble("weapons.recoil.muzzle-max", 6.0));
        state.climbUp = Math.min(cap, state.climbUp + up * soft / (soft + state.climbUp));
        double side = state.climbRight + right * soft / (soft + Math.abs(state.climbRight));
        state.climbRight = Math.max(-cap, Math.min(cap, side));
        state.climbSettling = false;
    }

    /**
     * The bullets' own climb comes back to the crosshair once firing stops, when the camera starts
     * drifting back, over weapons.recoil.muzzle-return-ticks. It does not depend on camera recovery,
     * so turning that off leaves no climb behind.
     */
    private static void settleClimb(GunState state, boolean firing, int ticks) {
        if (state.climbUp == 0 && state.climbRight == 0) {
            return;
        }
        if (firing) {
            state.climbSettling = false;
            return;
        }
        if (!state.climbSettling) {
            int over = Math.max(1, ticks);
            state.climbSettling = true;
            state.climbStepUp = state.climbUp / over;
            state.climbStepRight = Math.abs(state.climbRight) / over;
        }
        state.climbUp = Math.max(0, state.climbUp - state.climbStepUp);
        state.climbRight = Math.copySign(Math.max(0, Math.abs(state.climbRight) - state.climbStepRight), state.climbRight);
        if (state.climbUp < 1e-3 && Math.abs(state.climbRight) < 1e-3) {
            state.climbUp = state.climbRight = 0;
            state.climbSettling = false;
        }
    }

    private static double wrapDegrees(double degrees) {
        double d = degrees % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    // ------------------------------------------------------------------ reload

    /** Reserve ammo this gun can load: its caliber, and armory rounds for an armory gun, the player's own otherwise. */
    public int reserve(Player player, GunState state) {
        return reserve(player, state.gun, null, state.test);
    }

    /** The player's own reserve ammo (never armory rounds) of the gun's caliber in their carry cells. */
    public int reserve(Player player, GunSpec gun, @Nullable String preferType) {
        return reserve(player, gun, preferType, false);
    }

    private int reserve(Player player, GunSpec gun, @Nullable String preferType, boolean test) {
        if (plugin.lobby().inRange(player)) {
            return RANGE_RESERVE;
        }
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            ItemDef def = plugin.items().defOf(stack);
            if (def != null && def.ammo() != null && def.ammo().caliber().equals(gun.caliber()) && ItemFactory.isTest(stack) == test
                    && (preferType == null || preferType.equals(def.id()))) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * Ammo types of the gun's caliber the player carries, armory rounds for an armory gun and the player's
     * own otherwise: armory rounds in an own gun would be carried home in its magazine, and own rounds in
     * an armory gun would be lost with it.
     */
    private List<String> ammoTypesCarried(Player player, GunSpec gun, boolean test) {
        List<String> out = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            ItemDef def = plugin.items().defOf(stack);
            if (def != null && def.ammo() != null && def.ammo().caliber().equals(gun.caliber()) && ItemFactory.isTest(stack) == test
                    && !out.contains(def.id())) {
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
        List<String> types = ammoTypesCarried(player, gun, state.test);
        if (plugin.lobby().inRange(player)) {
            types = new ArrayList<>(types);
            for (ItemDef ammo : plugin.registry().ammoFor(gun.caliber())) {
                if (!types.contains(ammo.id())) {
                    types.add(ammo.id());
                }
            }
        }
        if (types.isEmpty()) {
            boolean otherKind = !ammoTypesCarried(player, gun, !state.test).isEmpty();
            Notify.error(player, "没有 " + gun.caliber() + " 口径的弹药" + (!otherKind ? ""
                    : state.test ? " <muted>· 试验场武器只能用试验场弹药" : " <muted>· 试验场弹药只能装进试验场武器"));
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
            Notify.hint(player, "弹匣已满  <muted>(潜行+Q 切换弹种)");
            return;
        }
        setAds(player, false);
        long now = Bukkit.getCurrentTick();
        double duration = gun.reloadTicks() * (state.ammo == 0 ? 1.2 : 1.0) * plugin.operators().reloadMultiplier(player)
                * (1.0 + 0.2 * plugin.health().activeFractures(player, true));
        state.reloadStart = now;
        state.reloadEnd = now + Math.max(5, Math.round(duration));
        // A shot's kick still running ends now; the reload frames start on the next tick (showReload),
        // once the stack carries the reload mark.
        player.setCooldown(ItemFactory.RELOAD_GROUP, 0);
        state.reloadShown = false;
        setMark(player, state, ItemFactory.MARK_RELOAD);
        state.reloadType = type;
        state.triggerHeld = false;
        state.burstLeft = 0;
        state.sampleLeft = 0;
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

    /**
     * Starts the client's reload frames: the reload mark first, sent at once rather than at the end of
     * the tick, then the cooldown that runs the frames. The other way round, the model would read the
     * cooldown as a shot and flash. A tick after the reload began, so a gun reloaded with Q (out of the
     * inventory while the drop event ran) is back in it and can carry the mark.
     */
    private void showReload(Player player, GunState state, long now) {
        state.reloadShown = true;
        setMark(player, state, ItemFactory.MARK_RELOAD);
        Nms.syncInventory(player);
        player.setCooldown(ItemFactory.RELOAD_GROUP, (int) Math.max(1, state.reloadEnd - now));
    }

    private void finishReload(Player player, GunState state) {
        GunSpec gun = state.gun;
        String type = state.reloadType;
        state.reloadStart = -1;
        state.reloadEnd = -1;
        state.reloadType = null;
        setMark(player, state, wantedMark(player, state));
        if (type == null) {
            return;
        }
        CarryService carry = plugin.carry();
        if (plugin.lobby().inRange(player)) {
            // Free, unlimited ammo on the range; nothing is taken from the inventory.
            state.ammo = gun.magazine();
            state.ammoType = type;
            reloadDone(player, state);
            return;
        }
        if (!type.equals(state.ammoType) && state.ammo > 0 && state.ammoType != null) {
            ItemStack back = plugin.items().create(state.ammoType, state.ammo);
            if (back != null) {
                carry.give(player, state.test ? plugin.items().markTest(back) : back, true);
            }
            state.ammo = 0;
        }
        int need = gun.magazine() - state.ammo;
        int taken = carry.takeItem(player, type, need, state.test);
        if (taken <= 0) {
            return;
        }
        state.ammo += taken;
        state.ammoType = type;
        // The full magazine stays in memory like every round fired from it: writing it to the stack now
        // would make the client lower and raise the gun right after the reload frames brought it back
        // up. It is written whenever the item could move (see flush).
        reloadDone(player, state);
    }

    // ------------------------------------------------------------------ ADS

    /** Right mouse is still held (a use packet, or the trigger held while aiming): aiming lasts a while longer. */
    private void holdAim(Player player, long now) {
        adsUntil.put(player.getUniqueId(), now + Math.max(1, plugin.getConfig().getInt("weapons.ads.timeout-ticks", 5)));
    }

    /**
     * Starts or ends aiming down the sights. The aim is the server's: the item model shows the sights
     * only when the gun carries the aiming marks, so what the player sees is what the shots get. On,
     * the gun takes the aiming mark (sent at once) and then a cooldown of its ads time that runs the
     * frames of the sights coming up on the client, while the view narrows tick by tick (see
     * {@link #raise}). Off, everything goes back at once.
     */
    public void setAds(Player player, boolean on) {
        if (on) {
            startAds(player);
        } else {
            endAds(player, false);
        }
    }

    private void startAds(Player player) {
        if (isAiming(player)) {
            return;
        }
        GunState state = state(player);
        if (state == null) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        ads.put(player.getUniqueId(), true);
        scopeOut.remove(player.getUniqueId());
        state.adsStart = now;
        state.adsTicks = adsTicks(state);
        state.adsUp = false;
        state.adsFrames = false;
        plugin.health().forceSpeedUpdate(player);
        // The mark now; the cooldown that runs the frames on the next tick (see raise). The client swaps
        // the stack in on its own next tick, but reads a cooldown at once: sent together, the frames
        // drawn in between would show the old stack reading the cooldown as a shot's kick, flash and all.
        // Until the cooldown comes, the aiming mark shows the gun at the hip.
        if (player.getCooldown(ItemFactory.RELOAD_GROUP) > 0) {
            player.setCooldown(ItemFactory.RELOAD_GROUP, 0);
        }
        setMark(player, state, ItemFactory.MARK_AIM);
        Nms.syncInventory(player);
        plugin.fov().show(player, 1.0);
        player.playSound(player.getLocation(), "craftov:gun.ads", SoundCategory.PLAYERS, 0.5f, 1f);
        plugin.fov().aimed(player);
    }

    /**
     * One tick of aiming: the view narrows with the sights' progress (eased in and out), and once
     * they are up the gun takes the aimed mark (its cooldown is a shot's kick again) and a scope
     * shows its picture.
     */
    /**
     * Ticks between the aiming mark and the cooldown that runs the frames of the sights coming up. The
     * client swaps a changed stack in on its next tick but reads a cooldown at once, and the two
     * packets may reach it in the same tick: two ticks apart, the stack is always in first.
     */
    private static final int ADS_FRAMES_DELAY = 2;
    /** Share of that cooldown, at its end, that holds the last frame (tools/art/models.py ADS_RISE_TAIL). */
    private static final double ADS_FRAMES_TAIL = 0.25;

    private void raise(Player player, GunState state, long now) {
        if (state.adsStart < 0) {
            return;
        }
        double target = fovFor(player, state);
        if (state.adsUp) {
            return;
        }
        if (!state.adsFrames && now >= state.adsStart + ADS_FRAMES_DELAY) {
            // The frames take the rest of the ads time; the cooldown runs a quarter longer, its last
            // quarter holding the final frame, so the aimed mark that ends them can come a little late.
            state.adsFrames = true;
            int frames = Math.max(1, state.adsTicks - ADS_FRAMES_DELAY);
            player.setCooldown(ItemFactory.RELOAD_GROUP, (int) Math.ceil(frames / (1 - ADS_FRAMES_TAIL)));
        }
        double t = Math.max(0, Math.min(1, (now - state.adsStart) / (double) Math.max(1, state.adsTicks)));
        if (t < 1) {
            double eased = t * t * (3 - 2 * t);
            plugin.fov().show(player, 1 + (target - 1) * eased);
            return;
        }
        state.adsUp = true;
        plugin.fov().show(player, target);
        setMark(player, state, ItemFactory.MARK_AIMED);
        if (state.optic != null && state.optic.magnified()) {
            plugin.hud().scopeOverlay(player, state.optic.overlay());
        }
    }

    /**
     * Ends aiming. {@code linger}: right mouse was let go (not a switch, reload, sprint or menu), so a
     * scope keeps its picture and the aimed mark for weapons.ads.scope-out-ticks while the client's
     * view widens back.
     */
    private void endAds(Player player, boolean linger) {
        if (!isAiming(player)) {
            return;
        }
        ads.put(player.getUniqueId(), false);
        plugin.health().forceSpeedUpdate(player);
        plugin.fov().show(player, 1.0);
        GunState state = state(player);
        boolean scope = state != null && state.adsUp && state.optic != null && state.optic.magnified();
        int out = Math.max(0, plugin.getConfig().getInt("weapons.ads.scope-out-ticks", 2));
        if (state != null) {
            if (!state.adsUp) {
                // The frames of the sights coming up are still running: stop them before the mark goes,
                // or the hip model would read what is left of them as a shot's kick.
                player.setCooldown(ItemFactory.RELOAD_GROUP, 0);
            }
            state.adsStart = -1;
            state.adsUp = false;
        }
        if (scope && linger && out > 0) {
            scopeOut.put(player.getUniqueId(), (long) Bukkit.getCurrentTick() + out);
            return;
        }
        lowerScope(player);
    }

    /** Takes a scope's picture down and the gun's aiming mark off. */
    private void lowerScope(Player player) {
        scopeOut.remove(player.getUniqueId());
        plugin.hud().scopeOverlay(player, null);
        GunState state = state(player);
        if (state != null) {
            setMark(player, state, wantedMark(player, state));
        }
    }

    /**
     * True power of the sights while aiming: the optic's, or the iron sights' by the gun's ads-class
     * (weapons.ads.iron: a slight zoom, a little less on handguns and the shotgun).
     */
    public double magnification(GunState state) {
        if (state.optic != null) {
            return state.optic.magnification();
        }
        var iron = plugin.getConfig();
        return switch (state.gun.adsClass()) {
            case "pistol" -> iron.getDouble("weapons.ads.iron.pistol", 1.1);
            case "shotgun" -> iron.getDouble("weapons.ads.iron.shotgun", 1.1);
            default -> iron.getDouble("weapons.ads.iron.default", 1.25);
        };
    }

    /** Field-of-view multiplier while aiming, at the field of view the player declared (70 by default). */
    private double fovFor(Player player, GunState state) {
        return plugin.fov().multiplier(player, magnification(state));
    }

    /** Shows the zoom again after the player declared another field of view or FOV Effects (/cv fov). */
    public void refreshZoom(Player player) {
        GunState state = state(player);
        if (!isAiming(player) || state == null || state.adsUp) {
            plugin.fov().show(player, isAiming(player) && state != null ? fovFor(player, state) : 1.0);
        }
    }

    /** Explains, at most once a second, why a trigger pull did nothing. */
    private void hint(Player player, String text) {
        long now = Bukkit.getCurrentTick();
        if (now - lastHint.getOrDefault(player.getUniqueId(), -100L) >= 20) {
            lastHint.put(player.getUniqueId(), now);
            Notify.info(player, text);
        }
    }

    /**
     * Sprinting carries the gun lowered: no shot while sprinting (see {@link #sprinting}), nor for a few
     * ticks after the sprint ends (weapons.sprint-recovery-ticks) while it comes back up. The vanilla
     * client keeps sprinting with the attack button held, and the server cannot stop a client that
     * holds the sprint key, so the trigger is what gives.
     */
    private boolean sprintLocked(Player player) {
        if (sprinting(player)) {
            return true;
        }
        Long last = lastSprint.get(player.getUniqueId());
        return last != null && Bukkit.getCurrentTick() - last <= plugin.getConfig().getInt("weapons.sprint-recovery-ticks", 5);
    }

    /**
     * Sprinting by the client's word, or running at a sprint by the server's measure: a modified client
     * can move at sprint speed without ever saying it started sprinting.
     */
    private boolean sprinting(Player player) {
        return player.isSprinting() || motion.running(player);
    }

    private void sprintHint(Player player) {
        if (sprinting(player)) {
            hint(player, "<muted>冲刺时不能开火");
        }
    }

    /** Why this player cannot fire right now, or null if they can. */
    private @Nullable String fireBlocker(Player player) {
        if (plugin.raids().canAttack(player)) {
            return null;
        }
        if (plugin.lobby().isLobby(player.getWorld())) {
            return "<muted>大厅里不能开火，请到靶场入口进入个人靶场";
        }
        if (plugin.downed().isReviving(player)) {
            return "<muted>救援时不能开火";
        }
        if (plugin.raids().isInRaid(player)) {
            return plugin.raids().isProtected(player) ? "<muted>保护时间内不能开火" : "<muted>现在不能开火";
        }
        return "<muted>只能在行动或靶场中开火";
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
        if (Bukkit.getCurrentTick() - handledUse.getOrDefault(player.getUniqueId(), -100L) <= 1 || useEntityBehindCell(player)) {
            return; // opened a box or a rack, or reviving: no aiming at it
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType().isInteractable() && !player.isSneaking()
                && event.getClickedBlock().getLocation().add(0.5, 0.5, 0.5).distanceSquared(player.getEyeLocation()) < 16) {
            return; // let doors, levers and buttons work while holding a gun
        }
        // Never cancel or deny: a denied use makes Paper resync the whole inventory, which wipes the
        // scope overlay (a fake helmet) five times a second. Paper guns have no vanilla use anyway.
        if (state.drawing()) {
            state.aimAsked = Bukkit.getCurrentTick(); // still held once the gun is up, it aims then (see drawn)
            return;
        }
        if (state.reloading() || !plugin.raids().canFire(player) || player.isSprinting()) {
            return;
        }
        holdAim(player, Bukkit.getCurrentTick());
        setAds(player, true);
    }

    /**
     * The trigger keeps client-only capture blocks in front of whatever the crosshair is on (see
     * {@link TriggerService}), so a right-click at a loot box, a body, a weapon rack or a downed teammate
     * lands on one and reaches the server as a click on air. This passes it on as the click it would have
     * been, but only to the first thing the client could have picked within reach, with nothing solid in
     * the way: an enemy or a range target standing in front of a box means the player is aiming at them.
     * True if something took it.
     */
    private boolean useEntityBehindCell(Player player) {
        long now = Bukkit.getCurrentTick();
        if (!plugin.triggers().capturing(player) || now - lastEntityUse.getOrDefault(player.getUniqueId(), -100L) <= 1) {
            // The client already clicked it (or the block click of this same press did): once is enough.
            return false;
        }
        Location eye = player.getEyeLocation();
        RayTraceResult hit = player.getWorld().rayTrace(eye, eye.getDirection(), USE_REACH, FluidCollisionMode.NEVER, false, 0.0,
                entity -> plugin.triggers().pickable(player, entity));
        Entity first = hit == null ? null : hit.getHitEntity();
        if (first == null || !(first instanceof Interaction || plugin.triggers().downedMate(player, first)
                || "container".equals(first.getPersistentDataContainer().get(Keys.ENTITY_ROLE, PersistentDataType.STRING)))) {
            return false;
        }
        PlayerInteractEntityEvent click = new PlayerInteractEntityEvent(player, first, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(click);
        return click.isCancelled();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityUse(PlayerInteractEntityEvent event) {
        long now = Bukkit.getCurrentTick();
        lastEntityUse.put(event.getPlayer().getUniqueId(), now);
        if (event.isCancelled()) {
            handledUse.put(event.getPlayer().getUniqueId(), now);
        }
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
                ItemFactory.setDrawing(dropped, false);
                event.getItemDrop().setItemStack(dropped);
                stow(player);
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
        state.burstLeft = 0;
        state.sampleLeft = 0;
        player.playSound(player.getLocation(), "craftov:gun.mode", SoundCategory.PLAYERS, 0.7f, 1f);
        Notify.info(player, "<accent>射击模式: <white>" + state.fireMode().displayName());
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        flush(event.getPlayer());
        GunState state = stow(event.getPlayer());
        if (state != null && state.reloading()) {
            Notify.hint(event.getPlayer(), "换弹已中断");
        }
        setAds(event.getPlayer(), false);
    }

    @EventHandler
    public void onSprint(PlayerToggleSprintEvent event) {
        if (event.isSprinting()) {
            setAds(event.getPlayer(), false);
        } else {
            lastSprint.put(event.getPlayer().getUniqueId(), (long) Bukkit.getCurrentTick());
        }
    }

    /** A teleport is not movement: measure speed again from where the player lands. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        motion.reset(event.getPlayer());
    }

    /** Nor is respawning, which fires no teleport event even within the same world. */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        motion.reset(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        motion.forget(event.getPlayer().getUniqueId());
        reset(event.getPlayer());
        UUID id = event.getPlayer().getUniqueId();
        stowed.values().removeIf(kept -> kept.owner().equals(id));
        ads.remove(event.getPlayer().getUniqueId());
        adsUntil.remove(event.getPlayer().getUniqueId());
        scopeOut.remove(event.getPlayer().getUniqueId());
        lastHint.remove(event.getPlayer().getUniqueId());
        lastInventoryDrop.remove(event.getPlayer().getUniqueId());
        lastSprint.remove(event.getPlayer().getUniqueId());
        lastEntityUse.remove(event.getPlayer().getUniqueId());
        handledUse.remove(event.getPlayer().getUniqueId());
    }
}
