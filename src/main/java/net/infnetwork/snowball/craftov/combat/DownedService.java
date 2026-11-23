package net.infnetwork.snowball.craftov.combat;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Knock-down and rescue. The first down gives a long bleed-out, the second a short one, and a third
 * lethal hit kills outright. A teammate revives by holding right click on the downed player, close
 * by, on the same level and with nothing in between.
 *
 * <p>Crouching used to revive too, from behind a wall or a floor up: crouch is also the silent walk,
 * sneak+Q and sneak+F, and the client already refuses to click a player it cannot see.
 *
 * <p>A player carrying a self-revive injector is knocked down even with no teammate standing (solo,
 * or the last of the squad up) and bleeds out as usual while they use it; without one, nobody left
 * to revive means death at once rather than a wait.
 */
public final class DownedService implements Listener {

    public static final class Downed {
        public final int number;
        public double pool;
        public final double maxPool;
        public @Nullable UUID reviver;
        public int reviveTicks;
        public final int reviveNeeded;
        public DamageService.@Nullable LastHit cause;
        Location fakeBlock;
        /** Where the reviver stood when the current attempt began. */
        @Nullable Location reviveFrom;
        /** Tick the last attempt was cut short by the reviver moving or getting hurt. */
        long interruptedAt = -100;

        Downed(int number, double pool, int reviveNeeded) {
            this.number = number;
            this.pool = pool;
            this.maxPool = pool;
            this.reviveNeeded = reviveNeeded;
        }

        public double fraction() {
            return Math.max(0, pool / maxPool);
        }
    }

    private static final BlockData BARRIER = Material.BARRIER.createBlockData();
    /**
     * An invisible ceiling over the upper half of a block: the pack draws a top purpur slab as nothing
     * (tools/art/build.py). Purpur is in no Craftov map, and CraftEngine keeps petrified oak slabs for
     * its own blocks.
     */
    private static final BlockData HALF_CEILING = Bukkit.createBlockData("minecraft:purpur_slab[type=top,waterlogged=false]");
    private final CraftovPlugin plugin;
    private final Map<UUID, Downed> downed = new HashMap<>();
    /** reviver -> (downed player, tick of last right-click). */
    private final Map<UUID, Map.Entry<UUID, Long>> clicks = new HashMap<>();
    /**
     * reviver -> what they last tried and could not do because their hands are busy (a trigger pull,
     * F on a knife), and the tick. The revive bar holds the action bar while it runs, and so does
     * the line saying why a revive cannot start, so the reason is said there rather than queued
     * behind it.
     */
    private final Map<UUID, Map.Entry<String, Long>> busyRefusals = new HashMap<>();
    /**
     * downed player -> what they just tried and could not do (a menu that stays shut, F on a knife),
     * and the tick. The bleed-out bar holds the action bar, and the revive bar while a squadmate
     * works on them, so the reason is said there for a moment.
     */
    private final Map<UUID, Map.Entry<String, Long>> refusals = new HashMap<>();
    /**
     * Ticks a right click on a downed mate counts as still held: a held button repeats every 4
     * ticks, and the revive check runs every 5.
     */
    private static final int HOLD_TICKS = 8;
    /** How long a refusal stays on the bleed-out bar. */
    private static final int REFUSAL_TICKS = 40;
    /** How long a refusal stays on the revive bar. */
    private static final int BUSY_REFUSAL_TICKS = 20;
    /**
     * Runs of {@link #tick} (every 5 ticks) so far. The heartbeat and the bandage sound come round
     * on every fourth, once a second, counted here rather than read off the server tick, whose
     * phase against this timer depends on when the plugin was enabled.
     */
    private long runs;

    public DownedService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
    }

    public boolean isDowned(Player player) {
        return downed.containsKey(player.getUniqueId());
    }

    public @Nullable Downed state(Player player) {
        return downed.get(player.getUniqueId());
    }

    /** The player pulled the trigger while reviving: their revive bar says why nothing fired for a second. */
    public void refusedFire(Player reviver) {
        refusedWhileReviving(reviver, "救援时不能开火");
    }

    /** The reviver tried something their busy hands cannot do: their revive bar says why for a second. */
    public void refusedWhileReviving(Player reviver, String reason) {
        busyRefusals.put(reviver.getUniqueId(), Map.entry(reason, (long) Bukkit.getCurrentTick()));
    }

    /** The downed player tried something they cannot do while down: their bleed-out bar says why for a moment. */
    public void refused(Player player, String reason) {
        refusals.put(player.getUniqueId(), Map.entry(reason, (long) Bukkit.getCurrentTick()));
    }

    /**
     * True while the player works on a downed squadmate or holds right click on one. Their hands
     * are busy: the gun goes down, and they cannot shoot, cut, throw, reload or treat themselves.
     * The click counts at once, before the revive check, so the use-item packet the same click
     * sends right after cannot throw the grenade or start the bandage in their hand.
     */
    public boolean isReviving(Player player) {
        UUID id = player.getUniqueId();
        for (Downed state : downed.values()) {
            if (id.equals(state.reviver)) {
                return true;
            }
        }
        Map.Entry<UUID, Long> click = clicks.get(id);
        return click != null && downed.containsKey(click.getKey()) && Bukkit.getCurrentTick() - click.getValue() <= HOLD_TICKS;
    }

    /**
     * True when a lethal hit should knock the player down: below downed.max-downs, and either a
     * squadmate is still up to revive them or they carry a self-revive injector.
     */
    public boolean canBeDowned(Raid raid, RaidPlayer rp) {
        int maxDowns = plugin.getConfig().getInt("downed.max-downs", 2);
        if (rp.downs >= maxDowns) {
            return false;
        }
        Player player = Bukkit.getPlayer(rp.uuid);
        if (player != null && carriesInjector(player)) {
            return true;
        }
        for (RaidPlayer mate : raid.squadOf(rp)) {
            if (mate != rp && mate.state == RaidPlayer.State.ALIVE) {
                return true;
            }
        }
        return false;
    }

    public void down(Raid raid, RaidPlayer rp, Player player, DamageService.@Nullable LastHit cause) {
        rp.downs++;
        rp.state = RaidPlayer.State.DOWNED;
        boolean first = rp.downs == 1;
        double pool = first ? plugin.getConfig().getDouble("downed.first-seconds", 60) : plugin.getConfig().getDouble("downed.second-seconds", 30);
        int revive = (int) Math.round(plugin.getConfig().getDouble("downed.revive-seconds", 5) * 20);
        Downed state = new Downed(rp.downs, pool, revive);
        state.cause = cause;
        downed.put(player.getUniqueId(), state);
        // Whatever was open goes shut: a secure container opened before the hit would otherwise
        // stay usable while the player crawls.
        player.closeInventory();
        plugin.weapons().reset(player);
        player.setHealth(1.0);
        player.setSprinting(false);
        plugin.health().forceSpeedUpdate(player);
        String wait = hasStandingMate(raid, rp) ? "等待队友救援" : "<accent>使用自救注射器<muted>";
        player.showTitle(Title.title(Text.mm("<danger><b>" + (first ? "第一次倒地" : "第二次倒地")),
                Text.mm("<muted>" + wait + " · " + (first ? "再次倒地将更加危险" : "<danger>下一次将直接阵亡")),
                Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(3), Duration.ofMillis(400))));
        player.playSound(player.getLocation(), "craftov:status.downed", SoundCategory.MASTER, 1f, 1f);
        for (RaidPlayer mate : raid.squadOf(rp)) {
            Player p = Bukkit.getPlayer(mate.uuid);
            if (p != null && p != player) {
                plugin.hud().alert(p, "<danger>队友 " + Text.escape(player.getName()) + " 倒地 · <text>靠近后对准他按住右键救援", 100, null);
                p.playSound(p.getLocation(), "craftov:status.mate_down", SoundCategory.MASTER, 1f, 1f);
            }
        }
        if (cause != null && cause.attacker() != null) {
            Player attacker = Bukkit.getPlayer(cause.attacker());
            if (attacker != null) {
                Notify.info(attacker, "<accent>击倒 <white>" + Text.escape(player.getName()));
            }
        }
        crawl(player, state);
    }

    /** Damage taken while downed eats into the bleed-out pool. */
    public void damageDowned(Raid raid, RaidPlayer rp, Player player, double amount) {
        Downed state = downed.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.pool -= amount * 0.6;
        state.reviveTicks = 0;
        state.reviver = null;
        if (state.pool <= 0) {
            finish(raid, rp, player, state);
        }
    }

    public void revive(Player player, @Nullable Player reviver) {
        Downed state = downed.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        RaidPlayer rp = plugin.raids().session(player);
        if (rp != null) {
            rp.state = RaidPlayer.State.ALIVE;
        }
        uncrawl(player, state);
        getUp(player);
        // Getting up, the gun has to come up again: without this the state bound while downed had
        // finished its draw long ago, so a revived player could fire on the tick they stood.
        plugin.weapons().reset(player);
        player.setHealth(Math.min(HealthService.MAX_HP, state.number == 1 ? 35 : 20));
        plugin.health().forceSpeedUpdate(player);
        player.showTitle(Title.title(Text.mm("<ok>已被救起"), Text.mm(reviver == null ? "<muted>自我救援成功" : "<muted>" + Text.escape(reviver.getName()) + " 救起了你"),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1500), Duration.ofMillis(300))));
        player.playSound(player.getLocation(), "craftov:status.revived", SoundCategory.MASTER, 1f, 1f);
        if (reviver != null) {
            Notify.ok(reviver, "救援成功");
            RaidPlayer medic = plugin.raids().session(reviver);
            if (medic != null) {
                medic.revives++;
            }
        }
    }

    public void clear(Player player) {
        Downed state = downed.remove(player.getUniqueId());
        if (state != null) {
            uncrawl(player, state);
            plugin.health().forceSpeedUpdate(player);
        }
    }

    private void finish(Raid raid, RaidPlayer rp, Player player, Downed state) {
        downed.remove(player.getUniqueId());
        uncrawl(player, state);
        rp.state = RaidPlayer.State.ALIVE;
        DamageService.LastHit hit = killingHit(player, state);
        plugin.raids().die(raid, player, rp, hit == null || hit.attacker() == null ? "倒地后失血过多"
                : net.infnetwork.snowball.craftov.raid.RaidManager.deathReason(hit), hit);
    }

    /**
     * Who gets the kill when a downed player dies (finished off, bled out, quit) or a player gives
     * up. That is whoever hurt them last, if within 30 seconds and not on their own squad;
     * otherwise, for a downed player, whoever knocked them down. Past 30 seconds the last hit has
     * expired, a shell landing after that names nobody, and a squadmate's round (friendly fire on)
     * earns no kill, so none of them takes the kill off the knocker: a squad could otherwise shoot
     * its own crawling member to deny the enemy the bounty. Ask before {@link #clear}, which
     * forgets the knock-down.
     */
    public DamageService.@Nullable LastHit killingHit(Player player) {
        return killingHit(player, downed.get(player.getUniqueId()));
    }

    private DamageService.@Nullable LastHit killingHit(Player player, @Nullable Downed state) {
        DamageService.LastHit hit = plugin.damage().lastHit(player.getUniqueId());
        DamageService.LastHit cause = state == null ? null : state.cause;
        if (hit != null && hostile(player, hit)) {
            return hit;
        }
        if (cause != null && cause.attacker() != null) {
            return cause;
        }
        return hit != null ? hit : cause;
    }

    /** True when someone other than the player and their squad dealt the hit: an enemy or a soldier. */
    private boolean hostile(Player player, DamageService.LastHit hit) {
        UUID attacker = hit.attacker();
        if (attacker == null || attacker.equals(player.getUniqueId())) {
            return false;
        }
        Raid raid = plugin.raids().raidOf(player);
        RaidPlayer victim = raid == null ? null : raid.player(player.getUniqueId());
        RaidPlayer other = raid == null ? null : raid.player(attacker);
        return victim == null || other == null || other.squad != victim.squad;
    }

    private void tick() {
        long now = Bukkit.getCurrentTick();
        boolean second = runs++ % 4 == 0;
        refusals.keySet().retainAll(downed.keySet());
        refusals.values().removeIf(refusal -> now - refusal.getValue() >= REFUSAL_TICKS);
        busyRefusals.values().removeIf(refusal -> now - refusal.getValue() >= BUSY_REFUSAL_TICKS);
        for (Map.Entry<UUID, Downed> entry : new HashMap<>(downed).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Downed state = entry.getValue();
            if (player == null) {
                downed.remove(entry.getKey());
                continue;
            }
            Raid raid = plugin.raids().raidOf(player);
            RaidPlayer rp = raid == null ? null : raid.player(player.getUniqueId());
            if (raid == null || rp == null) {
                clear(player);
                continue;
            }
            crawl(player, state);
            boolean mates = hasStandingMate(raid, rp);
            if (!mates && !carriesInjector(player)) {
                // Nobody left who could revive and no injector: bleeding out would only make everyone wait.
                finish(raid, rp, player, state);
                continue;
            }
            Player reviver = mates ? findReviver(raid, rp, player, state) : null;
            if (reviver != null) {
                Location at = reviver.getLocation();
                if (!reviver.getUniqueId().equals(state.reviver)) {
                    state.reviver = reviver.getUniqueId();
                    state.reviveTicks = 0;
                    state.reviveFrom = at;
                } else if (state.reviveFrom == null || horizontalDistanceSquared(at, state.reviveFrom) > square(maxMove())) {
                    interrupt(state, at);
                }
                state.reviveTicks += Math.round(5 * plugin.operators().reviveMultiplier(reviver));
                reviveEffects(reviver, player, second);
                double progress = Math.min(1, state.reviveTicks / (double) state.reviveNeeded);
                String note = now - state.interruptedAt < 20 ? "<danger>救援被打断，重新开始  " : "";
                // What either of them just tried and could not do goes on their own revive bar,
                // which holds the action bar over the hint.
                String own = note.isEmpty() ? reason(busyRefusals, reviver) : note;
                String theirs = note.isEmpty() ? reason(refusals, player) : note;
                Notify.critical(reviver, "revive", own + "<ok>正在救援 " + Text.escape(player.getName()) + "  " + Text.bar(progress, 20, "ok", "dim"));
                Notify.critical(player, "revive", theirs + "<ok>" + Text.escape(reviver.getName()) + " 正在救援你  " + Text.bar(progress, 20, "ok", "dim"));
                if (state.reviveTicks >= state.reviveNeeded) {
                    stopReviving(reviver);
                    revive(player, reviver);
                }
                continue;
            }
            if (state.reviver != null) {
                Player old = Bukkit.getPlayer(state.reviver);
                if (old != null) {
                    stopReviving(old);
                }
            }
            state.reviver = null;
            state.reviveTicks = 0;
            state.pool -= 0.25;
            if (second) {
                player.getWorld().spawnParticle(Particle.BLOCK, player.getLocation().add(0, 0.2, 0), 4, 0.3, 0.05, 0.3,
                        Material.REDSTONE_BLOCK.createBlockData());
                player.playSound(player.getLocation(), "craftov:status.heartbeat", SoundCategory.PLAYERS, 0.6f, 1f);
            }
            Map.Entry<String, Long> refusal = refusals.get(player.getUniqueId());
            Notify.critical(player, "downed", "<danger>倒地 " + Text.bar(state.fraction(), 24, "danger", "dim")
                    + " <muted>" + (int) Math.ceil(state.pool) + "s"
                    + (refusal != null ? "  <danger>" + refusal.getKey() : mates ? "" : "  <accent>没有队友能救你，使用自救注射器"));
            if (state.pool <= 0) {
                finish(raid, rp, player, state);
            }
        }
    }

    /**
     * Starts the attempt over from {@code at}: the reviver walked more than downed.revive-max-move
     * blocks from where they began, or got hurt. Reviving is meant to pin the medic down, not be
     * done on the move or through incoming fire.
     */
    private void interrupt(Downed state, Location at) {
        if (state.reviveTicks > 0) {
            state.interruptedAt = Bukkit.getCurrentTick();
        }
        state.reviveTicks = 0;
        state.reviveFrom = at;
    }

    private double maxMove() {
        return plugin.getConfig().getDouble("downed.revive-max-move", 0.5);
    }

    private static double horizontalDistanceSquared(Location a, Location b) {
        if (a.getWorld() != b.getWorld()) {
            return Double.MAX_VALUE;
        }
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static double square(double value) {
        return value * value;
    }

    /**
     * A reviver who takes damage loses the progress they made. Their own wounds bleeding does not
     * count (a medic who is bleeding could never finish otherwise): bleeding is no damage event, it
     * comes straight off their health (RaidManager.loseHealth).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onReviverHurt(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player hurt) || event.getFinalDamage() <= 0) {
            return;
        }
        UUID id = hurt.getUniqueId();
        for (Downed state : downed.values()) {
            if (id.equals(state.reviver)) {
                interrupt(state, hurt.getLocation());
            }
        }
    }

    /** True if the player has a self-revive injector anywhere on them, the cursor included. */
    public boolean carriesInjector(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isInjector(stack)) {
                return true;
            }
        }
        return isInjector(player.getItemOnCursor());
    }

    private boolean isInjector(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty() || ItemFactory.isUi(stack)) {
            return false;
        }
        ItemDef def = plugin.items().defOf(stack);
        return def != null && def.med() != null && def.med().effect() == MedSpec.Effect.SELF_REVIVE;
    }

    /**
     * True if a downed player has a squadmate standing who could revive them; false when only their
     * own injector can (solo, or the last of the squad up).
     */
    public boolean mateCanRevive(Player player) {
        Raid raid = plugin.raids().raidOf(player);
        RaidPlayer rp = raid == null ? null : raid.player(player.getUniqueId());
        return raid != null && rp != null && hasStandingMate(raid, rp);
    }

    private boolean hasStandingMate(Raid raid, RaidPlayer rp) {
        for (RaidPlayer mate : raid.squadOf(rp)) {
            if (mate != rp && mate.state == RaidPlayer.State.ALIVE && Bukkit.getPlayer(mate.uuid) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The reviver kneels over the downed player and dresses the wound: others see them crouched with
     * a bandage worked back and forth in hand (StanceService's revive stance) and hear the bandage
     * being wrapped once a second (on {@code second}).
     */
    private void reviveEffects(Player reviver, Player patient, boolean second) {
        if (reviver.getPose() != Pose.SNEAKING) {
            reviver.setPose(Pose.SNEAKING, true);
        }
        if (second) {
            Location at = patient.getLocation().add(0, 0.4, 0);
            patient.getWorld().playSound(at, "craftov:med.wrap", SoundCategory.PLAYERS, 0.7f,
                    0.95f + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 0.1f);
        }
    }

    /**
     * Ends the reviver's kneel. Only the kneel: a reviver knocked down mid-revive already crawls,
     * and clearing that pose stood them up (a full-height box with the upright hit zones, and a
     * player seen standing) until their own crawl came round again in the next pass.
     */
    private void stopReviving(Player reviver) {
        busyRefusals.remove(reviver.getUniqueId());
        if (reviver.hasFixedPose() && reviver.getPose() == Pose.SNEAKING && !isDowned(reviver)) {
            reviver.setPose(Pose.STANDING, false);
        }
    }

    /**
     * The squadmate holding right click on the downed player who is close enough to work on them,
     * the current reviver first. A mate who holds on but cannot reach is told why.
     */
    private @Nullable Player findReviver(Raid raid, RaidPlayer rp, Player downedPlayer, Downed state) {
        Player found = null;
        for (RaidPlayer mate : raid.squadOf(rp)) {
            if (mate == rp || mate.state != RaidPlayer.State.ALIVE) {
                continue;
            }
            Player p = Bukkit.getPlayer(mate.uuid);
            if (p == null || !holding(p, downedPlayer)) {
                continue;
            }
            String blocked = reviveBlocker(p, downedPlayer);
            if (blocked != null) {
                // Their hands count as busy from the click on: what that refused is said here too.
                Notify.critical(p, "revive", reason(busyRefusals, p) + "<danger>" + blocked);
                continue;
            }
            if (p.getUniqueId().equals(state.reviver)) {
                return p;
            }
            if (found == null) {
                found = p;
            }
        }
        return found;
    }

    /** The player's refusal in {@code reasons} as the start of a bar line, or "" when there is none. */
    private static String reason(Map<UUID, Map.Entry<String, Long>> reasons, Player player) {
        Map.Entry<String, Long> refusal = reasons.get(player.getUniqueId());
        return refusal == null ? "" : "<muted>" + refusal.getKey() + "  ";
    }

    /** True while {@code reviver} holds right click on {@code downedPlayer}. */
    private boolean holding(Player reviver, Player downedPlayer) {
        Map.Entry<UUID, Long> click = clicks.get(reviver.getUniqueId());
        return click != null && click.getKey().equals(downedPlayer.getUniqueId())
                && Bukkit.getCurrentTick() - click.getValue() <= HOLD_TICKS;
    }

    /**
     * Why {@code reviver} cannot work on {@code downedPlayer} from where they are, or null if they
     * can: within downed.revive-range, feet less than downed.revive-max-height apart (a stair or a
     * slab, not the floor above or a ledge), and a clear line from the reviver's eyes to the
     * downed player. The client cannot click a player behind a wall, but a modified one can, and
     * the range alone reached through a wall or up a flight of stairs.
     */
    private @Nullable String reviveBlocker(Player reviver, Player downedPlayer) {
        Location from = reviver.getLocation();
        Location to = downedPlayer.getLocation();
        double range = plugin.getConfig().getDouble("downed.revive-range", 2.2);
        if (from.getWorld() != to.getWorld() || from.distanceSquared(to) > range * range) {
            return "离得太远，靠近一些再救援";
        }
        if (Math.abs(from.getY() - to.getY()) >= plugin.getConfig().getDouble("downed.revive-max-height", 1.0)) {
            return "不在同一高度，无法救援";
        }
        if (!inSight(reviver, downedPlayer)) {
            return "中间有遮挡，无法救援";
        }
        return null;
    }

    /** A block-free line from the reviver's eyes to the middle or the top of the downed player's box. */
    private static boolean inSight(Player reviver, Player downedPlayer) {
        Location eye = reviver.getEyeLocation();
        BoundingBox box = downedPlayer.getBoundingBox();
        Vector[] targets = {box.getCenter(), new Vector(box.getCenterX(), box.getMaxY() - 0.05, box.getCenterZ())};
        for (Vector target : targets) {
            Vector line = target.clone().subtract(eye.toVector());
            double length = line.length();
            if (length < 1e-3 || eye.getWorld().rayTraceBlocks(eye, line.multiply(1 / length), length,
                    FluidCollisionMode.NEVER, true) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * A revived player comes up through a crouch: everyone else sees them kneel for
     * downed.get-up-ticks before they stand, instead of springing upright on the spot. (Their own
     * client stands at once; it decides its own pose.)
     */
    private void getUp(Player player) {
        int ticks = plugin.getConfig().getInt("downed.get-up-ticks", 12);
        if (ticks <= 0 || !player.isOnline()) {
            return;
        }
        player.setPose(Pose.SNEAKING, true);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !isDowned(player) && !isReviving(player) && player.hasFixedPose()
                    && player.getPose() == Pose.SNEAKING) {
                player.setPose(Pose.STANDING, false);
            }
        }, ticks);
    }

    /**
     * Forces the crawling pose on the player's own client with a client-side block over their head: one
     * that leaves room for the crawl (0.6 high) but not for a crouch (1.5). A full barrier block does
     * that unless the feet are between 0.4 and 0.5 into a block (on a slab, say), where the block above
     * the head is out of the crouch's reach and the one below it in the crawl's: there an invisible top
     * slab ({@link #HALF_CEILING}) fills the upper half of the block above the feet's block instead.
     * Without it the player crouched rather than crawled there.
     */
    private void crawl(Player player, Downed state) {
        // Server-side pose so other players see the crawl; the fake block keeps the own client down.
        if (player.getPose() != Pose.SWIMMING || !player.hasFixedPose()) {
            player.setPose(Pose.SWIMMING, true);
        }
        Location feet = player.getLocation();
        double into = feet.getY() - Math.floor(feet.getY());
        boolean slab = into > 0.4 + 1e-6 && into <= 0.5 + 1e-6;
        Block block = feet.clone().add(0, slab ? 1.0 : 1.5, 0).getBlock();
        BlockData shown = slab ? HALF_CEILING : BARRIER;
        if (state.fakeBlock != null && !state.fakeBlock.getBlock().equals(block)) {
            player.sendBlockChange(state.fakeBlock, state.fakeBlock.getBlock().getBlockData());
            state.fakeBlock = null;
        }
        if (block.getType().isAir()) {
            player.sendBlockChange(block.getLocation(), shown);
            state.fakeBlock = block.getLocation();
        }
    }

    private void uncrawl(Player player, Downed state) {
        if (player.hasFixedPose()) {
            player.setPose(Pose.STANDING, false);
        }
        if (state.reviver != null) {
            Player reviver = Bukkit.getPlayer(state.reviver);
            if (reviver != null) {
                stopReviving(reviver);
            }
        }
        if (state.fakeBlock != null && player.isOnline()) {
            player.sendBlockChange(state.fakeBlock, state.fakeBlock.getBlock().getBlockData());
            state.fakeBlock = null;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!isDowned(event.getPlayer())) {
            return;
        }
        ItemDef def = plugin.items().defOf(event.getItem());
        if (def != null && def.med() != null && def.med().effect() == MedSpec.Effect.SELF_REVIVE) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isDowned(event.getPlayer())) {
            event.setCancelled(true);
            return;
        }
        if (event.getRightClicked() instanceof Player target && isDowned(target)
                && plugin.raids().sameSquad(event.getPlayer(), target)) {
            event.setCancelled(true);
            clicks.put(event.getPlayer().getUniqueId(), Map.entry(target.getUniqueId(), (long) Bukkit.getCurrentTick()));
        }
    }
}
