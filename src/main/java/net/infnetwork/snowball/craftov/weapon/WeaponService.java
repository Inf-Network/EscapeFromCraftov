package net.infnetwork.snowball.craftov.weapon;

import io.papermc.paper.event.packet.ClientTickEndEvent;
import io.papermc.paper.event.player.PlayerFailMoveEvent;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.security.VanillaGuardService;
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
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
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
        /** The trigger went down while the gun came up: the first shot waits until it is up. */
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
        /**
         * Last tick right mouse asked to aim while the sights could not come up (the gun coming up after a
         * switch, a sprint and the moments after one): still held once they can, it aims then.
         */
        long aimAsked = -100;
        /**
         * Tick the server took the aim, and the ticks the sights take to come up from there (see
         * {@link WeaponService#adsTicks}); the view, the spread and the recoil follow that progress.
         */
        long adsStart = -1;
        int adsTicks = 1;
        /**
         * Tick the aiming mark went on: the cooldown that runs the frames waits a little after it. The
         * same as adsStart unless the aim picked up a rise already under way (see startAds).
         */
        long adsMarkAt = -1;
        /**
         * Tick right mouse was last let go of (the use packets stopped), and how many ticks of the rise
         * the sights had then: aiming again within weapons.ads.resume-ticks picks up from there.
         */
        long adsLetGo = -100;
        int adsKept;
        /** The cooldown that runs the frames of the sights coming up has been sent (a tick after the mark). */
        boolean adsFrames;
        /** The sights are all the way up: the gun carries the aimed mark, a scope shows its picture. */
        boolean adsUp;
        /** Tick the sights last came all the way up, -1 while they rise from the hip: the gun settles from there (see sway). */
        long upAt = -1;
        /** Where in its figure of eight this gun's sway starts, in ticks: aims do not all sway alike. */
        final int swayPhase = ThreadLocalRandom.current().nextInt(1000);
        /** Tick a scope's picture goes up, a tick after the aimed mark (see {@link WeaponService#raise}); -1 for none due. */
        long scopeAt = -1;
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
            ItemDef fitted = registry.get(ItemFactory.opticId(stack));
            ItemDef mounted = fitted != null ? fitted : registry.get(def.gun().scope());
            this.optic = mounted == null ? null : mounted.optic();
            this.opticName = mounted == null ? null : mounted.name();
            this.range = ItemFactory.isRange(stack);
            this.test = ItemFactory.isTest(stack);
            var parts = Attachments.resolve(def.gun(), ItemFactory.attachments(stack), registry);
            // A built-in scope is part of the gun's own handling; a fitted sight changes it.
            this.gun = Attachments.withOptic(Attachments.effective(def.gun(), parts), fitted == null ? null : fitted.optic());
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
    /** Last tick each player was sprinting: the sights take a moment to come up afterwards (see {@link #sightsUp}). */
    private final Map<UUID, Long> lastSprint = new HashMap<>();
    /**
     * Last tick each player ran at a sprint by the server's measure with no sprint from the client
     * lately (a modified client): the sights wait out the same recovery after it (see {@link #sightsUp}).
     */
    private final Map<UUID, Long> lastRun = new HashMap<>();
    /** Players whose food level was last told that aiming stops their sprint (see {@link #stopsSprint}). */
    private final java.util.Set<UUID> sprintStopped = new java.util.HashSet<>();
    /** Last tick a use packet came from each player holding a gun (see {@link #rightHeld}). */
    private final Map<UUID, Long> lastUse = new HashMap<>();
    /**
     * Players the sprint key took out of the sights while right mouse was held (see {@link #onInput}),
     * and the tick it did: until that sprint is over or right mouse is let go, the sights stay down and
     * right mouse does not stop the sprint (see {@link #stopsSprint} and {@link #sightsUp}).
     */
    private final Map<UUID, Long> sprintWon = new HashMap<>();
    /**
     * Ticks the sprint the sprint key won has to start in (the client hears the food level, sprints and
     * says so): a sprint that has not started by then, or has ended since, gives right mouse back.
     */
    private static final int SPRINT_START_TICKS = 10;
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
        List<String> old = OBSOLETE.stream().filter(path -> plugin.getConfig().contains(path, true)).toList();
        if (!old.isEmpty()) {
            plugin.getLogger().warning("config.yml: " + String.join(", ", old) + " no longer do anything: each gun's handling"
                    + " (items.yml) sets how fast it aims, draws and comes out of a sprint, by weapons.handling. Delete them.");
        }
    }

    /** Keys that set aim, draw and sprint times before each gun had a handling (issue #117). */
    private static final List<String> OBSOLETE = List.of("weapons.sprint-recovery-ticks", "weapons.draw-ticks", "weapons.ads.ticks",
            "weapons.ads.optic-ticks");

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
     * Ticks the sights take to come up: by the gun's handling with its sight and parts (see
     * {@link Handling}), a submachine gun's 3 to a bare bolt-action rifle's 9.
     */
    int adsTicks(GunState state) {
        return Handling.adsTicks(plugin.getConfig(), state.gun.handling());
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
        if (state.drawing()) {
            // The draw frames run on the gun's cooldown, as long as the draw, under the draw mark. The
            // holster mark (the draw's first frame) reads no cooldown, so the cooldown goes out while
            // the stack still carries it and the draw mark after: whether the client takes the
            // packets in one frame or two, it shows the gun low and then coming up. The draw mark
            // first would show the draw's rest pose for any frame before the cooldown, the gun
            // popping up and dropping back. A gun that arrives with another mark (one that left the
            // inventory mid-reload) would read the cooldown as a shot's kick or a reload: it is put
            // to the holster mark first.
            if (ItemFactory.handMark(held) != ItemFactory.MARK_HOLSTER) {
                ItemFactory.setHolstered(held);
                Nms.syncInventory(player);
            }
            player.setCooldown(ItemFactory.RELOAD_GROUP, (int) (state.readyAt - now));
            ItemFactory.setHandMark(held, ItemFactory.MARK_DRAW);
            Nms.syncInventory(player);
            // Heard by the holder and everyone close by: the gun coming off the sling into the hands.
            audio.foley(player, state.gun, "draw", 0.55f);
        } else {
            ItemFactory.setHandMark(held, ItemFactory.MARK_NONE);
        }
        int tips = controlTips.getOrDefault(player.getUniqueId(), 0);
        if (tips < 3) {
            controlTips.put(player.getUniqueId(), tips + 1);
            // A hint, not a result: it must not push aside the answer to whatever put the gun in hand.
            // The player's own key bindings, not the defaults.
            Notify.hint(player, "<white><key:key.attack><muted> 开火  <white><key:key.use><muted> 瞄准  <white><key:key.drop><muted> 换弹  "
                    + "<white><key:key.sneak>+<key:key.drop><muted> 换弹种  <white><key:key.swapOffhand><muted> 射击模式");
        }
        return state;
    }

    /**
     * Ticks a gun takes to come up once it is in the hand, by its handling with its sight and parts
     * (see {@link Handling}): quick for a pistol, slow for a machine gun or a bolt-action rifle.
     * Switching guns used to be free, so going to the sidearm instead of reloading cost nothing.
     */
    private int drawTicks(GunSpec gun) {
        return Handling.drawTicks(plugin.getConfig(), gun.handling());
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
        if (state.reloading() || state.drawing() || now - state.lastShotTick < state.gun.kickTicks()
                || state.adsStart >= 0 && !state.adsUp) {
            player.setCooldown(ItemFactory.RELOAD_GROUP, 0);
        }
        // Put away: the next switch to it shows it low at once (ItemFactory.MARK_HOLSTER). A gun that
        // left the hand by leaving the inventory (shift-clicked into a box, picked up on the cursor) is
        // marked where it went, or it would keep the mark it had in the hand and come up from rest,
        // then snap down, for whoever draws it next. A gun on the ground is marked as it lands
        // (onItemSpawn).
        ItemStack stack = stackOf(player, state);
        if (stack == null) {
            stack = outsideStackOf(player, state.uid);
        }
        if (stack != null && ItemFactory.handMark(stack) != ItemFactory.MARK_HOLSTER) {
            ItemFactory.setHolstered(stack);
        }
        return state;
    }

    /** The live stack of a gun just out of the player's inventory: on their cursor or in the container they have open. */
    private static @Nullable ItemStack outsideStackOf(Player player, String uid) {
        ItemStack cursor = player.getItemOnCursor();
        if (uid.equals(ItemFactory.uid(cursor))) {
            return cursor;
        }
        for (ItemStack candidate : player.getOpenInventory().getTopInventory().getContents()) {
            if (uid.equals(ItemFactory.uid(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /** Every gun on the ground is put away, however it got there (see {@link #stow}). */
    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        ItemDef def = plugin.items().defOf(stack);
        if (def != null && def.isGun() && ItemFactory.handMark(stack) != ItemFactory.MARK_HOLSTER) {
            ItemFactory.setHolstered(stack);
            event.getEntity().setItemStack(stack);
        }
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

    /** The gun is up: its model may aim and kick again, and right mouse held through the draw aims. */
    private void drawn(Player player, GunState state, long now) {
        state.drawPending = false;
        if (!state.reloading()) {
            // The draw frames end at rest anyway; a last tick of their cooldown, late by a network hitch,
            // must not be read by the model at rest as a shot's kick and flash.
            player.setCooldown(ItemFactory.RELOAD_GROUP, 0);
        }
        setMark(player, state, wantedMark(player, state));
        aimIfAsked(player, state, now);
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info(String.format("[debug] %s up %s tick=%d aim=%b",
                    player.getName(), state.def.id(), now, isAiming(player)));
        }
    }

    /**
     * Right mouse held while the sights could not come up (the gun coming up after a switch, a sprint
     * and the moments after one) aims as soon as they can, rather than with the next use packet, which
     * on a scoped gun would leave up to 4 ticks of neither gun nor scope (the model hides the gun as
     * soon as the mark is gone).
     */
    private void aimIfAsked(Player player, GunState state, long now) {
        if (!isAiming(player) && aimAsked(state, now) && !state.drawing() && !state.reloading()
                && plugin.raids().canFire(player) && sightsUp(player)) {
            holdAim(player, now);
            setAds(player, true);
        }
    }

    /** Right mouse is held asking to aim: a use packet came within the last repeat (see {@link GunState#aimAsked}). */
    private static boolean aimAsked(GunState state, long now) {
        return now - state.aimAsked <= USE_REPEAT + 1;
    }

    /**
     * Aiming ends a sprint and none starts while it lasts: true while the player aims or holds right
     * mouse to (the sights still coming up). The server cannot stop a client that holds the sprint key,
     * so this holds the food level where the vanilla client may not sprint (see HealthService.updateFood);
     * once right mouse is let go the client sprints again if the key is still held. The sprint key
     * pressed while aiming wins it back (see {@link #onInput}): false until that sprint is over.
     */
    public boolean stopsSprint(Player player) {
        if (sprintWon.containsKey(player.getUniqueId())) {
            return false;
        }
        if (isAiming(player)) {
            return true;
        }
        GunState state = states.get(player.getUniqueId());
        return state != null && aimAsked(state, Bukkit.getCurrentTick()) && plugin.raids().canFire(player);
    }

    /** Right mouse is held: a use packet came within the last repeat. */
    private boolean rightHeld(Player player, long now) {
        return now - lastUse.getOrDefault(player.getUniqueId(), -100L) <= USE_REPEAT + 1;
    }

    /** The keys the vanilla client starts a sprint with: the sprint key and forward, not sneaking. */
    private static boolean sprintKeys(org.bukkit.Input input) {
        return input.isSprint() && input.isForward() && !input.isBackward() && !input.isSneak();
    }

    /** Tells the food level when {@link #stopsSprint} changes, so a sprint ends (or may start) right away. */
    private void syncSprintStop(Player player) {
        boolean stop = stopsSprint(player);
        if (stop ? sprintStopped.add(player.getUniqueId()) : sprintStopped.remove(player.getUniqueId())) {
            plugin.health().updateFood(player);
        }
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        long now = Bukkit.getCurrentTick();
        try {
            tickPlayers(now);
        } finally {
            ticked = now;
        }
    }

    /**
     * The last tick {@link #tick} has run in: equal to the current tick between two ticks (where use
     * packets come), behind it while the next is starting.
     */
    private long ticked = -1;

    private void tickPlayers(long now) {
        if (now % 20 == 0) {
            stowed.values().removeIf(kept -> kept.spent(now));
        }
        if (!scopeOut.isEmpty()) {
            // Collected first: lowerScope takes the player out of scopeOut itself, which would break an
            // iteration over it (and with it the rest of this tick for every gun).
            List<UUID> due = new ArrayList<>();
            scopeOut.forEach((id, until) -> {
                if (now >= until) {
                    due.add(id);
                }
            });
            for (UUID id : due) {
                scopeOut.remove(id);
                Player out = Bukkit.getPlayer(id);
                if (out != null && !isAiming(out)) {
                    lowerScope(out);
                }
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            double caught = motion.sample(player);
            if (caught > 0 && plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info(String.format("[debug] %s held back movement: one tick covered %.2f blocks more than it can (%s)",
                        player.getName(), caught, motion.lastCaught(player)));
            }
            if (player.isSprinting()) {
                // The client's sprint, not the measured run, which reads on for a few ticks of slowing down.
                lastSprint.put(player.getUniqueId(), now);
            } else if (motion.running(player) && now - lastSprint.getOrDefault(player.getUniqueId(), -100L) > RUN_AFTER_SPRINT) {
                // A run the client never called a sprint. The measure dips under the line now and then on
                // a steady run, and without this the sights came up for those ticks, mid-run.
                lastRun.put(player.getUniqueId(), now);
            }
            GunState state = bind(player);
            if (isAiming(player) && motion.running(player)) {
                // Aiming holds the vanilla client off a sprint (see stopsSprint), and the sights only come
                // up once a run is over (see sightsUp), so running with them up is a client that ignores
                // both and never says it sprints. They drop as for a sprint, so its shots go from the hip
                // with the sprint cone rather than aimed with only the walking penalty.
                setAds(player, false);
            }
            Long won = sprintWon.get(player.getUniqueId());
            if (won != null && (!rightHeld(player, now) || !player.isSprinting() && now - won > SPRINT_START_TICKS)) {
                // Right mouse let go (pressed again, it ends the sprint as ever), or the sprint the key won
                // is over (or never came): right mouse still held aims once the sights can come up.
                sprintWon.remove(player.getUniqueId());
            }
            syncSprintStop(player);
            if (state == null) {
                continue;
            }
            kickPending(player, state, now);
            settle(player, state, now);
            if (state.drawPending && !state.drawing()) {
                drawn(player, state, now);
            }
            aimIfAsked(player, state, now);
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
        if (state.drawing()) {
            // A pull while the gun comes up after a switch does nothing; kept held, it fires once the
            // gun is up (see tick). Sprinting holds nothing back: the gun fires from the hip on the run.
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
        if (!plugin.raids().canAttack(player)) {
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
                hint(player, "<danger>" + noAmmo(player, state));
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
            plugin.getLogger().info(String.format("[debug] %s shot spread=%.3f speed=%.3f move=%.2f aim=%b ground=%b pitch=%.2f climb=%.2f bloom=%.2f tick=%d level=%.2f sprint=%b run=%b stop=%b sway=%.4f carry=%.2f held=%b",
                    player.getName(), spread, motion.speed(player), motion.moveFactor(player), isAiming(player), MotionTracker.grounded(player),
                    eye.getPitch(), state.climbUp, state.bloom, Bukkit.getCurrentTick(), moveLevel(player), player.isSprinting(), motion.running(player), motion.stopping(player),
                    swaySize(state, Bukkit.getCurrentTick()), sprintCarry(player, state), motion.heldBack(player)));
        }
        for (int i = 0; i < gun.pellets(); i++) {
            Vector pellet = Ballistics.spread(bore, gun.pellets() > 1 ? Math.max(spread, gun.hipSpread() * 0.75) : spread);
            ballistics.fire(player, eye, pellet, gun, ammo, state.def.name(), i == 0 || gun.pellets() <= 3);
        }
        // The shooter's own smoke is left out on a sprint and aimed: it would cover the sight (see Ballistics.muzzle).
        Ballistics.muzzle(player, dir, !sprinting(player) && !isAiming(player));
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
     * pace past a small dead zone (see {@link #moveCost}): a pistol barely minds, a sniper rifle cannot
     * hit a thing on the move, and stopping before the shot puts it back. A slide widens it by
     * weapons.spread.slide (slide-ads aimed) and never gets the sneaking bonus. Hip fire on a sprint
     * (run-and-gun) is that cone, at full pace, times the gun's sprint multiplier (see {@link #sprintSpread}),
     * and straight out of one the multiplier wears off as the gun comes up from the sprint carry (see
     * {@link #sprintCarry}): quickly on a gun with much handling, slowly on one with little.
     */
    private double currentSpread(Player player, GunState state) {
        GunSpec gun = state.gun;
        double up = adsProgress(player);
        boolean aiming = up >= 1;
        var config = plugin.getConfig();
        double aimed = gun.adsSpread() * opticSpread(state);
        // The cone narrows as the sights come up, and is the aimed one only once they are.
        double spread = gun.hipSpread() + (aimed - gun.hipSpread()) * up;
        boolean sliding = plugin.slides().isSliding(player);
        if (sliding) {
            // A slide is no steady crouch: wider from the hip, a little wider aimed (SlideService).
            double hip = config.getDouble("weapons.spread.slide", 1.6);
            spread *= hip + (config.getDouble("weapons.spread.slide-ads", 1.3) - hip) * up;
        } else if (player.isSneaking()) {
            spread *= config.getDouble("weapons.spread.sneak", 0.7);
        }
        if (!MotionTracker.grounded(player)) {
            spread *= config.getDouble("weapons.spread.airborne", 2.5);
        }
        if (state.fireMode() != FireMode.BOLT) {
            spread += state.bloom * (aiming ? 0.25 : 0.6);
        }
        spread += gun.moveSpread() * moveCost(player) * (aiming ? config.getDouble("weapons.spread.move-ads-share", 0.5) : 1.0);
        if (!aiming) {
            double carry = sprintCarry(player, state);
            if (carry > 0) {
                spread *= 1 + (sprintSpread(gun) - 1) * carry;
            }
        }
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

    /**
     * How much of the gun's move spread the player's movement costs right now: {@link #moveLevel} times
     * weapons.spread.walk, and times weapons.spread.stop while they are coming to a stop (letting go
     * of the keys or pressing the opposite one, see {@link MotionTracker#stopping}): the measured speed
     * takes a tick or two to catch up with a stop, and stopping before the shot is the skill. Not for a
     * while after the player held back movement (see {@link MotionTracker}).
     */
    private double moveCost(Player player) {
        double level = moveLevel(player);
        if (level <= 0) {
            return 0;
        }
        var config = plugin.getConfig();
        double cost = level * Math.max(0, config.getDouble("weapons.spread.walk", 1.0));
        return motion.stopping(player) ? cost * Math.max(0, config.getDouble("weapons.spread.stop", 0.5)) : cost;
    }

    /**
     * How much wider the hip cone is on a sprint than at a full walk, by the gun's action in items.yml
     * (weapons.spread.sprint): the gun is fired from the sprint carry, so a pistol or a submachine gun
     * still hits something close, and a machine gun or a rifle with a long barrel does not.
     */
    private double sprintSpread(GunSpec gun) {
        double fallback = switch (gun.action()) {
            case "pistol", "smg", "pdw", "pump" -> 1.4;
            case "pistol_heavy" -> 1.5;
            case "dmr", "lmg" -> 1.8;
            case "bolt" -> 2.0;
            default -> 1.6; // ak, ar
        };
        return Math.max(0, plugin.getConfig().getDouble("weapons.spread.sprint." + gun.action(), fallback));
    }

    /**
     * How much of the sprint's wider hip cone is still on the gun, 0 to 1: all of it on a sprint, then
     * falling to none in a straight line over the gun's sprint-out time once it ends (its handling, see
     * {@link Handling#sprintOutTicks}), the same time the sights wait (see {@link #sightsUp}). So how
     * soon a gun fires true after a sprint is its handling too: an SV9 in 0.15 s, an AWR in 0.35 s
     * (by default).
     */
    private double sprintCarry(Player player, GunState state) {
        if (sprinting(player)) {
            return 1;
        }
        int out = Handling.sprintOutTicks(plugin.getConfig(), state.gun.handling());
        if (out <= 0) {
            return 0;
        }
        long last = Math.max(lastSprint.getOrDefault(player.getUniqueId(), -100L), lastRun.getOrDefault(player.getUniqueId(), -100L));
        long since = Bukkit.getCurrentTick() - last;
        return Math.max(0, Math.min(1, 1 - since / (double) out));
    }

    /** Cone half-angle (degrees) the player's held gun would fire with right now, or -1 without one. */
    public double spreadNow(Player player) {
        GunState state = states.get(player.getUniqueId());
        return state == null ? -1 : currentSpread(player, state);
    }

    /** Where the bullets go: the crosshair, raised by the climb only the bullets get, and off by the sway. */
    private Vector bore(Location eye, GunState state) {
        double[] sway = sway(state, Bukkit.getCurrentTick());
        double up = state.climbUp + sway[0];
        double right = state.climbRight + sway[1];
        if (up == 0 && right == 0) {
            return eye.getDirection();
        }
        Location raised = eye.clone();
        raised.setPitch((float) Math.max(-90, Math.min(90, eye.getPitch() - up)));
        raised.setYaw((float) (eye.getYaw() + right));
        return raised.getDirection();
    }

    private static final double[] NO_SWAY = {0, 0};

    /**
     * How far aimed shots go off the crosshair while the gun settles, in degrees up and right: a slow
     * figure of eight (weapons.handling.sway.period-ticks around), as wide as the gun's sway the moment
     * the sights are up (the less handling, the wider: see {@link Handling#sway}) and shrinking to
     * weapons.handling.sway.rest of that as they are held up (about two thirds of the way in settle-ticks).
     * Only the bullets sway, never the camera: a scope that drifted on its own would shake on screen
     * the way issue #118 found unusable. Nothing from the hip or while the sights come up, whose cone
     * is wider anyway.
     */
    private double[] sway(GunState state, long now) {
        double size = swaySize(state, now);
        if (size <= 0) {
            return NO_SWAY;
        }
        double period = Math.max(4, plugin.getConfig().getDouble("weapons.handling.sway.period-ticks", 50));
        double phase = 2 * Math.PI * (now + state.swayPhase) / period;
        // Side to side once a period, up and down twice, half as far: a figure of eight.
        return new double[]{size * 0.5 * Math.sin(2 * phase), size * Math.sin(phase)};
    }

    /** How wide the sway's figure of eight is right now, in degrees (see {@link #sway}): 0 unless the sights are up. */
    private double swaySize(GunState state, long now) {
        if (!state.adsUp || state.upAt < 0) {
            return 0;
        }
        var config = plugin.getConfig();
        double size = Handling.sway(config, state.gun.handling());
        double rest = Math.max(0, Math.min(1, config.getDouble("weapons.handling.sway.rest", 0.3)));
        double settle = Math.max(1, config.getDouble("weapons.handling.sway.settle-ticks", 12));
        return size * (rest + (1 - rest) * Math.exp(-(now - state.upAt) / settle));
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
        boolean aiming = isAiming(player);
        double scale = (player.isSneaking() && !plugin.slides().isSliding(player) ? config.getDouble("weapons.recoil.sneak", 0.8) : 1.0)
                * (!aiming && sprinting(player) ? config.getDouble("weapons.recoil.sprint", 1.15) : 1.0)
                * (1.0 + 0.25 * plugin.health().activeFractures(player, true));
        // The sights coming up take the kick from the hip's to the aimed one (see adsRecoil).
        double progress = adsProgress(player);
        double scaleUp = scale * (1.0 + (adsRecoil(state, false) - 1.0) * progress);
        double scaleSide = scale * (1.0 + (adsRecoil(state, true) - 1.0) * progress);
        double up = gun.recoil() * scaleUp * (0.85 + ThreadLocalRandom.current().nextDouble() * 0.3);
        double right = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * gun.recoilH() * scaleSide;
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

    /**
     * The share of the gun's kick (up, or {@code sideways}) a shot has once the sights are up: the gun's
     * own (items.yml ads-recoil and ads-recoil-h; weapons.recoil.ads and ads-h for a gun that gives
     * none), less than from the hip and least sideways: the side-to-side jitter is what cannot be
     * pulled against, and through a sight it shakes the picture (issue #118).
     */
    private double adsRecoil(GunState state, boolean sideways) {
        var config = plugin.getConfig();
        double own = sideways ? state.gun.adsRecoilH() : state.gun.adsRecoil();
        if (Double.isNaN(own)) {
            own = sideways ? config.getDouble("weapons.recoil.ads-h", 0.35) : config.getDouble("weapons.recoil.ads", 0.5);
        }
        return Math.max(0, own);
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
        double flyingUp = inFlight(state, state.turnUp, player, now);
        double flyingRight = inFlight(state, state.turnRight, player, now);
        double above = raw + flyingUp;
        double right = wrapDegrees(player.getYaw() - state.anchorYaw) + flyingRight;
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
        // At the anchor only once no drift is in flight: the server turns its own copy of the view as
        // it sends a turn, and a client that does not move its mouse meanwhile never reports the old
        // view again, so the turns counted in flight are often in the view already. Ending on them
        // stopped the drift short by a round trip of steps (an AWR shot came back 70 to 80%, not
        // 85%); now it waits for them to land and then gives back the rest, still never past the
        // anchor.
        boolean upDone = state.returnUp < 1e-3 || above < 1e-3 && Math.abs(flyingUp) < 1e-3;
        boolean sideDone = Math.abs(state.returnRight) < 1e-3 || room < 1e-3 && Math.abs(flyingRight) < 1e-3;
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

    /**
     * Why the gun has nothing to load. Rounds of its calibre that are the other kind (armory rounds
     * for an own gun, or the other way round) are named: the empty-trigger hint used to say there
     * were none at all, so a crate's gun with armory rounds in the pockets read as a reload bug (#180).
     */
    private String noAmmo(Player player, GunState state) {
        String none = "没有 " + state.gun.caliber() + " 口径的弹药";
        if (ammoTypesCarried(player, state.gun, !state.test).isEmpty()) {
            return none + " <muted>· 去商人买或在物资箱里找";
        }
        return none + (state.test ? " <muted>· 试验场武器只能用试验场弹药（军械库的子弹）"
                : " <muted>· 身上的是试验场弹药，只能装进军械库的枪");
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
            Notify.error(player, noAmmo(player, state));
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
            Notify.hint(player, "弹匣已满  <muted>(<key:key.sneak>+<key:key.drop> 切换弹种)");
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
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info(String.format("[debug] %s reload %s type=%s cycle=%b ammo=%d sliding=%b",
                    player.getName(), gun.caliber(), type, cycleType, state.ammo, plugin.slides().isSliding(player)));
        }
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
        plugin.training().onReload(player);
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
        syncSprintStop(player);
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
        boolean scopeShown = scopeOut.remove(player.getUniqueId()) != null;
        state.adsTicks = adsTicks(state);
        // Right mouse back within moments of letting go, or a use packet that came a little late (a
        // network hitch): the sights pick up where they were instead of coming up from the hip again,
        // so a late packet never drops the scope, the zoom and the aimed spread for a whole new rise.
        int kept = now - state.adsLetGo <= Math.max(0, plugin.getConfig().getInt("weapons.ads.resume-ticks", 3))
                ? Math.min(state.adsKept, state.adsTicks) : 0;
        state.adsLetGo = -100;
        state.adsStart = now - kept;
        state.adsMarkAt = now;
        state.adsUp = false;
        state.adsFrames = false;
        state.scopeAt = -1;
        if (kept < state.adsTicks) {
            state.upAt = -1; // a new rise: the gun has to settle again (see sway); sights picked up as they were go on settling
        }
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info(String.format("[debug] %s aims %s tick=%d ticks=%d kept=%d handling=%.0f",
                    player.getName(), state.def.id(), now, state.adsTicks, kept, state.gun.handling()));
        }
        plugin.health().forceSpeedUpdate(player);
        if (kept < state.adsTicks) {
            if (scopeShown) {
                // The last aim's scope picture is still up: the sights come up without it (raise puts
                // it back once they are up), or its reticle would hang over the unzoomed view.
                plugin.hud().scopeOverlay(player, null);
            }
            // The mark now; the cooldown that runs the frames a little later (see raise). The client
            // swaps the stack in on its own next tick, but reads a cooldown at once: sent together, the
            // frames drawn in between would show the old stack reading the cooldown as a shot's kick,
            // flash and all. Until the cooldown comes, the aiming mark shows the gun at the hip.
            if (player.getCooldown(ItemFactory.RELOAD_GROUP) > 0) {
                player.setCooldown(ItemFactory.RELOAD_GROUP, 0);
            }
            setMark(player, state, ItemFactory.MARK_AIM);
            Nms.syncInventory(player);
        }
        // Sights picked up part or all of the way go on at once (sights that were up straight back to
        // the aimed mark, the zoom and the scope's picture). A new rise taken between two ticks (a
        // use packet) sends the next tick's view now, as that tick's (see previewRise).
        if (kept > 0) {
            raise(player, state, now);
        } else if (ticked == now) {
            previewRise(player, state, now + 1);
        }
        if (kept == 0) {
            player.playSound(player.getLocation(), "craftov:gun.ads", SoundCategory.PLAYERS, 0.5f, 1f);
        }
        plugin.fov().aimed(player);
    }

    /**
     * Ticks between the aiming mark and the cooldown that runs the frames of the sights coming up. The
     * client swaps a changed stack in on its next tick but reads a cooldown at once, and the two
     * packets may reach it in the same tick: two ticks apart, the stack is always in first.
     */
    private static final int ADS_FRAMES_DELAY = 2;
    /**
     * Ticks that cooldown runs past the rest of the ads time, holding the last frame (its last share,
     * tools/art/models.py ADS_RISE_TAIL) until the aimed mark is in. The real client swaps that mark in
     * two ticks after the frames end rather than one (frame grabs, issue #117): a cooldown that ran out
     * sooner left the aiming mark with nothing to read, which is the gun at the hip, for a frame or two
     * between the sights coming up and the aimed pose. Any longer and the aimed model would read what is
     * left of it as a shot's kick.
     */
    private static final int ADS_FRAMES_HOLD = 2;

    /**
     * One tick of aiming. While the sights come up the view narrows with their progress: a tick ahead
     * of the server's own (the client shows what it is sent a tick later), eased out a little so the
     * zoom answers the press on the next tick yet lands as the sights come up (with a scope, as its picture
     * does), and spread evenly over the sight's power (the multiplier goes as target^p, so an 8x does
     * not save its zoom for the end). It is steered
     * past the client's easing (see FovService.steer), so the view is at the sight's zoom on the tick
     * the sights are up rather than a quarter of a second after. Then the gun takes the aimed mark (its
     * cooldown is a shot's kick again), a scope shows its picture, and the view is steered on until
     * the client has caught up.
     */
    private void raise(Player player, GunState state, long now) {
        if (state.adsStart < 0) {
            return;
        }
        double target = fovFor(player, state);
        if (state.adsUp) {
            if (state.scopeAt >= 0 && now >= state.scopeAt && state.optic != null) {
                state.scopeAt = -1;
                plugin.hud().scopeOverlay(player, state.optic.overlay());
            }
            if (!plugin.fov().settled(player, target)) {
                plugin.fov().steer(player, target, target);
            }
            return;
        }
        if (!state.adsFrames && now >= state.adsMarkAt + ADS_FRAMES_DELAY) {
            // The frames take the rest of the ads time; the cooldown runs on past it holding the final
            // frame until the aimed mark is in (see ADS_FRAMES_HOLD). None for sights up this very tick
            // (an ads time of 2 ticks or less): the aimed mark would read it as a shot's kick and flash.
            state.adsFrames = true;
            long frames = state.adsStart + state.adsTicks - now;
            if (frames > 0) {
                player.setCooldown(ItemFactory.RELOAD_GROUP, (int) frames + ADS_FRAMES_HOLD);
            }
        }
        long done = now - state.adsStart;
        if (done < state.adsTicks) {
            plugin.fov().steer(player, riseView(state, target, done), target);
            return;
        }
        state.adsUp = true;
        if (state.upAt < 0) {
            state.upAt = now;
        }
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info(String.format("[debug] %s aimed %s tick=%d", player.getName(), state.def.id(), now));
        }
        plugin.fov().steer(player, target, target);
        ItemStack held = stackOf(player, state);
        boolean masked = held != null && ItemFactory.handMark(held) == ItemFactory.MARK_AIMED;
        setMark(player, state, ItemFactory.MARK_AIMED);
        if (state.optic != null && state.optic.magnified()) {
            // The scope's mask is drawn with the gun under the aimed mark, which the client swaps in on
            // its next tick, but its reticle (the helmet's camera overlay) shows the moment it comes:
            // sent together, a frame could show the reticle over the zoomed world with no mask around
            // it. The reticle waits a tick, unless the gun still carries the mark (picked up as it was).
            if (masked) {
                plugin.hud().scopeOverlay(player, state.optic.overlay());
            } else {
                state.scopeAt = now + 1;
            }
        }
    }

    /**
     * Where the view should be once the client takes what is sent {@code done} ticks into the rise:
     * the next tick's progress (the client shows it a tick later), eased out and spread evenly over the
     * sight's power (see {@link #raise}).
     */
    private static double riseView(GunState state, double target, long done) {
        double next = Math.min(1, (done + 1) / (double) Math.max(1, state.adsTicks));
        return Math.pow(target, 1 - Math.pow(1 - next, 1.5));
    }

    /**
     * Sends, as soon as the server takes an aim between two ticks, the view the rise's first tick
     * ({@code tick}, the next) will send, counted as sent in that tick (see FovService.steer). The
     * client takes at most one of the two in its next tick. On a quick connection the one sent with
     * the tick nearly always reaches it first anyway, and counted as a step of its own, this one had
     * the zoom come in short of the sight and creep the rest of the way, the quickest guns the most
     * (their first step is the longest): on screen they were no quicker than an M4C (issue #117). But
     * a client whose next tick comes before the server's (its ticks follow its frames) takes this one
     * a tick sooner, then the same again.
     */
    private void previewRise(Player player, GunState state, long tick) {
        double target = fovFor(player, state);
        long done = tick - state.adsStart;
        plugin.fov().steer(player, done < state.adsTicks ? riseView(state, target, done) : target, target, tick);
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
            // Let go (or the use packets stopped coming): aiming again within a few ticks picks up from
            // here (see startAds). A switch, a reload, a sprint or a menu starts over.
            long now = Bukkit.getCurrentTick();
            state.adsLetGo = linger && state.adsStart >= 0 ? now : -100;
            state.adsKept = state.adsStart >= 0 ? (int) Math.min(state.adsTicks, now - state.adsStart) : 0;
            state.adsStart = -1;
            state.adsUp = false;
            state.scopeAt = -1;
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
     * The sights can come up: not sprinting (see {@link #sprinting}), nor in the few ticks after the
     * client's sprint ends while the gun comes up from the sprint carry (the held gun's sprint-out
     * time, by its handling: see {@link Handling}), or after a run at a sprint that the client never
     * said was one (see {@link #lastRun}). Hip fire is never held back by a sprint: the gun fires on
     * the run, with a wider cone (see {@link #currentSpread}). Right mouse ends the sprint (see
     * {@link #stopsSprint}) and aims once this allows (see {@link #aimIfAsked}), but not during a
     * sprint the sprint key won over the sights (see {@link #onInput}), nor while that sprint is still
     * to start.
     */
    private boolean sightsUp(Player player) {
        if (sprinting(player) || sprintWon.containsKey(player.getUniqueId())) {
            return false;
        }
        long last = Math.max(lastSprint.getOrDefault(player.getUniqueId(), -100L), lastRun.getOrDefault(player.getUniqueId(), -100L));
        GunState state = states.get(player.getUniqueId());
        int out = state == null ? 0 : Handling.sprintOutTicks(plugin.getConfig(), state.gun.handling());
        return Bukkit.getCurrentTick() - last >= Math.max(1, out);
    }

    /**
     * Ticks the server's measure of a run reads on after the client's sprint has ended, while the
     * player slows down: a run measured in them is that sprint, not a sprint the client never told.
     */
    private static final int RUN_AFTER_SPRINT = 5;

    /**
     * Sprinting by the client's word, or running at a sprint by the server's measure: a modified client
     * can move at sprint speed without ever saying it started sprinting.
     */
    private boolean sprinting(Player player) {
        return player.isSprinting() || motion.running(player);
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
        lastUse.put(player.getUniqueId(), (long) Bukkit.getCurrentTick());
        if (Bukkit.getCurrentTick() - handledUse.getOrDefault(player.getUniqueId(), -100L) <= 1 || useEntityBehindCell(player)) {
            return; // opened a box or a rack, or reviving: no aiming at it
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType().isInteractable() && !player.isSneaking()
                && event.getClickedBlock().getLocation().add(0.5, 0.5, 0.5).distanceSquared(player.getEyeLocation()) < 16) {
            // Let doors, levers and buttons work while holding a gun. Right mouse is still held, though:
            // an aim under way goes on while the crosshair passes over one.
            if (isAiming(player)) {
                holdAim(player, Bukkit.getCurrentTick());
            }
            return;
        }
        // Never cancel or deny: a denied use makes Paper resync the whole inventory, which wipes the
        // scope overlay (a fake helmet) five times a second. Paper guns have no vanilla use anyway.
        if (state.drawing()) {
            state.aimAsked = Bukkit.getCurrentTick(); // still held once the gun is up, it aims then (see aimIfAsked)
            return;
        }
        if (state.reloading() || !plugin.raids().canFire(player)) {
            return;
        }
        if (!sightsUp(player)) {
            // Aiming ends a sprint: the food level stops it now, and the sights come up a moment after.
            state.aimAsked = Bukkit.getCurrentTick();
            syncSprintStop(player);
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
        if (VanillaGuardService.taken(event)) {
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
                ItemFactory.setHolstered(dropped);
                event.getItemDrop().setItemStack(dropped);
                stow(player);
            }
            return;
        }
        // Q outside the inventory screen = reload (sneak+Q cycles ammo type; not mid-slide, whose
        // second tap of sneak is usually still held).
        event.setCancelled(true);
        if (state != null && plugin.raids().canFire(player)) {
            startReload(player, state, player.isSneaking() && !plugin.slides().isSliding(player));
        }
    }

    /** F with a gun in hand (SwapKeyService reads the key): the gun's next fire mode, if it has more than one. */
    public void nextFireMode(Player player) {
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

    /**
     * The sprint key pressed while aiming (or while right mouse asks to) takes the player out of the
     * sights and lets them sprint, as a sprint always took them out before aiming held the client off
     * one: the newer press wins. Pressed is when the keys the client sprints with come together (the
     * sprint key with forward held, or forward with the sprint key held), and only when nothing else
     * holds the sprint off, or the sights would drop for nothing. With right mouse still held they stay
     * down until that sprint is over (see {@link #sprintWon}).
     */
    @EventHandler
    public void onInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        // During the event the player still has the input from before it.
        if (!sprintKeys(event.getInput()) || sprintKeys(player.getCurrentInput()) || !stopsSprint(player)
                || plugin.health().sprintBlocked(player)) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        if (rightHeld(player, now)) {
            sprintWon.put(player.getUniqueId(), now);
        }
        setAds(player, false); // and the food level lets the client sprint (see syncSprintStop)
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

    /** The client's clock, which movement is measured by (see {@link MotionTracker}). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ClientTickEndEvent event) {
        motion.tickEnded(event.getPlayer());
    }

    /** Where the client's movement packet says it went, applied or not (see {@link MotionTracker#claimed}). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        motion.claimed(event.getPlayer(), event.getTo());
    }

    /** A move the server refused, putting the player back (see {@link MotionTracker#setBack}). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onFailMove(PlayerFailMoveEvent event) {
        if (!event.isAllowed()) {
            // At once: the tick end that comes with the refused move must not read as a step.
            motion.setBack(event.getPlayer());
        }
    }

    /** A push from the server (a blast, a punch): the client may move by it (see {@link MotionTracker#pushed}). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPushed(PlayerVelocityEvent event) {
        motion.pushed(event.getPlayer(), event.getVelocity());
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
        lastRun.remove(event.getPlayer().getUniqueId());
        sprintStopped.remove(event.getPlayer().getUniqueId());
        lastUse.remove(event.getPlayer().getUniqueId());
        sprintWon.remove(event.getPlayer().getUniqueId());
        lastEntityUse.remove(event.getPlayer().getUniqueId());
        handledUse.remove(event.getPlayer().getUniqueId());
    }
}
