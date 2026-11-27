package net.infnetwork.snowball.craftov.raid;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.util.Nms;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Entry camera: the squad's view sweeps from high above the map down to their insertion point and
 * ends in each player's own eyes, where they land. Every squad member spectates a mannequin of their
 * own that only they are sent (as the entity they look through, it is not drawn for them either).
 * The flights share their start and part ways only towards the end, each into its player's own
 * insertion point: with one camera for the squad, everyone but the first landed a few blocks to the
 * side of where the flight had ended. The server moves the cameras every tick and sends where they
 * are every other tick (a mannequin's update interval), with exact positions and pitch
 * (Nms#precisePosition); the client glides a camera over three ticks to each position it is sent,
 * so the view moves on every frame. A mannequin rather than an invisible display: the client eases
 * its camera's eye height towards that of the entity it looks through, and a display's is 0, so on
 * landing the view dropped to the player's feet (through the floor for a frame) and rose to their
 * eyes; a mannequin's eyes are at a standing player's height.
 * A client only takes the camera once it has been sent the mannequin, which comes with the chunk it
 * is in, a few ticks after the jump into the raid world: told earlier, it ignores the camera and
 * stays in its own body, which the server then drags along in jumps (#166, what the entry camera
 * looked like before). So a player is put on their camera once it is tracked for them, kept up with
 * it until then, and put on it again if their client ever loses it.
 * Players are looked up by UUID on every step, never kept as Player objects: someone who drops out
 * and reconnects during the flight is a new Player object, and RaidManager.tryRejoin puts them back
 * in spectator in the raid world so their camera picks them up again. Whoever ends up outside the
 * raid world while flying in (a command, a plugin) is brought straight back to their camera, and the
 * flight always ends and lands everyone, whatever happens to one of them.
 */
public final class Cinematic {

    /** The flight, in ticks. */
    private static final int DURATION = 130;
    /**
     * Ticks from the last frame to the landing: the client glides an entity over three ticks to
     * each position it is sent, so the view is then at rest in the squad's eyes.
     */
    private static final int SETTLE = 4;
    /**
     * The {@link Keys#ENTITY_ROLE} of a camera, whose {@link Keys#ENTITY_REF} is its player's UUID:
     * bullets, grenades and the HUD pass over it.
     */
    private static final String ROLE = "entry_camera";
    /**
     * The share of the flight by which the camera faces the squad's bearing, level: the rest glides
     * straight in. A mannequin's head yaw goes out in steps of 1.4 degrees, which a slow turn would
     * show; the turn ends while the camera still moves fast.
     */
    private static final double TURNED = 0.9;
    /** A standing player's eye height, and the mannequin's: the camera is that far above its feet. */
    private static final double EYE = 1.62;
    /** The black curtain over the jump into the raid world: fade in, hold and fade out, in ticks. */
    private static final int CURTAIN_IN = 4;
    private static final int CURTAIN_STAY = 14;
    private static final int CURTAIN_OUT = 10;
    /**
     * The flight tick at which the map's name comes up: once the curtain is gone, since a new title
     * replaces the one on screen and the curtain is a title.
     */
    private static final int NAME_AT = CURTAIN_IN + CURTAIN_STAY + CURTAIN_OUT;
    /** The squad line, as the map's name fades out. */
    private static final int SQUAD_AT = 86;

    private Cinematic() {
    }

    /**
     * Flies each of these players in on their own camera, into their insertion point (their
     * RaidPlayer.lastLocation, or {@code spawn} without one), then calls onDone with every one of
     * their UUIDs, online or not, once they land (at once when the camera is off).
     */
    public static void play(CraftovPlugin plugin, Raid raid, int squadNumber, List<Player> players, Location spawn,
                            Consumer<UUID> onDone) {
        List<UUID> ids = players.stream().map(Player::getUniqueId).toList();
        if (!plugin.getConfig().getBoolean("cinematic.enabled", true) || players.isEmpty()) {
            ids.forEach(onDone);
            return;
        }
        if (raid.training()) {
            // No flight over a course of four rooms: the curtain alone, and down once the screen is black.
            for (Player player : players) {
                net.infnetwork.snowball.craftov.gui.UiFont.blackout(player, CURTAIN_IN, CURTAIN_STAY, CURTAIN_OUT);
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> ids.forEach(onDone), CURTAIN_IN + 2L);
            return;
        }
        Location center = raid.map.camera().at(raid.world);
        Vector p0 = center.toVector().add(new Vector(-30, 30, -30));
        Vector p1 = center.toVector().add(new Vector(0, 10, 0));
        List<Flight> flights = new ArrayList<>();
        for (Player player : players) {
            RaidPlayer rp = raid.player(player.getUniqueId());
            // Where RaidManager.deploySquad has them land (and reconnect, should they drop out).
            Location landing = rp != null && rp.lastLocation != null && rp.lastLocation.getWorld() == raid.world
                    ? rp.lastLocation.clone() : spawn.clone();
            Flight flight = new Flight(player.getUniqueId(), landing, p0, p1, cameraFor(raid, player, landing, p0, p1));
            flights.add(flight);
            // A black curtain over the jump into the raid world, so its loading never shows.
            net.infnetwork.snowball.craftov.gui.UiFont.blackout(player, CURTAIN_IN, CURTAIN_STAY, CURTAIN_OUT);
            player.setGameMode(GameMode.SPECTATOR);
            // Already looking where the camera does: until the client has the camera, this is the view.
            player.teleport(flight.at(raid.world, 0));
        }
        String tierLine = raid.tier.tag() + " <muted>· " + (players.size() <= 1 ? "单人行动" : players.size() + " 人小队");
        new BukkitRunnable() {
            int tick;

            @Override
            public void run() {
                if (raid.state == Raid.State.CLOSED || flights.stream().anyMatch(f -> !f.camera.isValid())) {
                    cancel();
                    flights.forEach(f -> f.camera.remove());
                    release();
                    if (raid.state == Raid.State.RUNNING) {
                        land(); // a camera vanished mid-flight: land everyone rather than leave them hanging
                    }
                    return;
                }
                int now = tick;
                // Counted first: whatever goes wrong further down, the flight still reaches its end
                // and the squad lands. Counted at the bottom, an exception on every step kept the
                // camera on the same frame for ever and nobody was ever put down.
                tick++;
                for (Flight flight : flights) {
                    RaidPlayer rp = raid.player(flight.id);
                    Player player = Bukkit.getPlayer(flight.id);
                    try {
                        Location loc = flight.at(raid.world, now);
                        flight.camera.teleport(loc);
                        // Still flying in: online and neither landed nor settled, wherever they are.
                        if (player != null && rp != null && rp.state == RaidPlayer.State.DEPLOYING) {
                            follow(player, flight, loc, now);
                        }
                    } catch (RuntimeException ex) {
                        // One player's trouble must not hold up the rest of the squad.
                        plugin.getLogger().log(Level.WARNING, "Entry camera could not take "
                                + (player != null ? player.getName() : flight.id) + " along", ex);
                    }
                }
                if (now >= DURATION) {
                    cancel();
                    // Once the client has glided the camera onto its last position.
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        release();
                        land();
                        flights.forEach(f -> f.camera.remove());
                    }, SETTLE);
                }
            }

            private void follow(Player player, Flight flight, Location loc, int now) {
                if (player.getGameMode() != GameMode.SPECTATOR) {
                    player.setGameMode(GameMode.SPECTATOR);
                }
                attach(player, flight, loc);
                if (now == NAME_AT) {
                    player.showTitle(Title.title(Text.mm("<b><text>" + Text.escape(raid.map.name())),
                            Text.mm(tierLine + (isRanked(raid, player) ? " · <accent>排位" : "")), Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2000), Duration.ofMillis(600))));
                    player.playSound(player.getLocation(), "craftov:ui.cinematic", SoundCategory.MASTER, 1f, 1f);
                } else if (now == SQUAD_AT) {
                    player.showTitle(Title.title(Text.mm(""), Text.mm("<muted>第 " + squadNumber + " 小队 · 正在接近渗透点"),
                            Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(300))));
                }
            }

            /**
             * Puts the player on their camera, or keeps them with it until their client can be. The
             * client gets the camera with the chunk it is in; a camera packet before that is
             * dropped, and the player would stay in their own body for the whole flight.
             */
            private void attach(Player player, Flight flight, Location loc) {
                Mannequin camera = flight.camera;
                if (!player.canSee(camera)) {
                    // Shown per Player object: again for whoever reconnected.
                    player.showEntity(plugin, camera);
                }
                // The world first: the distance to a player in another world cannot be measured.
                if (player.getWorld() != raid.world || player.getLocation().distanceSquared(loc) > 64 * 64) {
                    // Somewhere else (just reconnected, or sent to another world by a command):
                    // next to the camera now, and on it once their client has been sent it.
                    if (player.getSpectatorTarget() != null) {
                        player.setSpectatorTarget(null);
                    }
                    player.teleport(loc);
                    flight.shown = false;
                    return;
                }
                boolean onCamera = player.getSpectatorTarget() == camera;
                if (!camera.isTrackedBy(player)) {
                    // Not sent the camera yet (or lost it): along with it until it is, looking the
                    // same way, so the view the client does have is the camera's.
                    flight.shown = false;
                    if (!onCamera) {
                        player.teleport(loc);
                    }
                } else if (!onCamera) {
                    player.setSpectatorTarget(camera);
                    flight.shown = true;
                } else if (!flight.shown && Nms.sendCamera(player, camera)) {
                    // On it on the server, but the client did not have the camera when told so.
                    flight.shown = true;
                }
            }

            /** Takes the squad off their cameras: the flight is over for all of them, landed or not. */
            private void release() {
                for (Flight flight : flights) {
                    RaidPlayer rp = raid.player(flight.id);
                    if (rp != null) {
                        rp.inFlight = false;
                    }
                    Player player = Bukkit.getPlayer(flight.id);
                    if (player == null) {
                        continue;
                    }
                    try {
                        if (player.getSpectatorTarget() == flight.camera) {
                            player.setSpectatorTarget(null);
                        }
                    } catch (RuntimeException ex) {
                        plugin.getLogger().log(Level.WARNING, "Entry camera could not let go of " + player.getName(), ex);
                    }
                }
            }

            /** Hands every squad member, online or not, to onDone: one failure keeps nobody else in the air. */
            private void land() {
                for (UUID id : ids) {
                    try {
                        onDone.accept(id);
                    } catch (RuntimeException ex) {
                        plugin.getLogger().log(Level.SEVERE, "Could not land " + id + " after the entry camera", ex);
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * One squad member's flight: the shared start (p0, p1), then above and behind their own
     * insertion point and into their own eyes there. Insertion points of a squad stand a couple of
     * blocks apart, so the flights only part in the last stretch.
     */
    private static final class Flight {
        final UUID id;
        final Location landing;
        final Vector p0;
        final Vector p1;
        final Vector p2;
        final Vector p3;
        final Mannequin camera;
        /** Whether their client was put on the camera while it had the mannequin. */
        boolean shown;

        Flight(UUID id, Location landing, Vector p0, Vector p1, Mannequin camera) {
            this.id = id;
            this.landing = landing;
            this.p0 = p0;
            this.p1 = p1;
            this.p2 = above(landing);
            this.p3 = eyes(landing);
            this.camera = camera;
        }

        /** Where the camera stands (its feet) and looks at this tick. */
        Location at(World world, int tick) {
            return feet(frame(world, p0, p1, p2, p3, landing, tick));
        }
    }

    /** Above and behind an insertion point, where the last stretch of the flight comes down from. */
    private static Vector above(Location landing) {
        return landing.toVector().add(new Vector(0, 22, 0)).add(landing.getDirection().multiply(-14));
    }

    private static Vector eyes(Location landing) {
        return landing.toVector().add(new Vector(0, EYE, 0));
    }

    /** The camera for one player, where their flight starts, sent to them alone (Flight). */
    private static Mannequin cameraFor(Raid raid, Player player, Location landing, Vector p0, Vector p1) {
        Location start = feet(frame(raid.world, p0, p1, above(landing), eyes(landing), landing, 0));
        return raid.world.spawn(start, Mannequin.class, m -> {
            m.getPersistentDataContainer().set(Keys.ENTITY_ROLE, PersistentDataType.STRING, ROLE);
            m.getPersistentDataContainer().set(Keys.ENTITY_REF, PersistentDataType.STRING, player.getUniqueId().toString());
            // Sent to its player alone (attach): to anyone else, a spectator flying in on another
            // camera for one, it would show as a ghost.
            m.setVisibleByDefault(false);
            m.setInvisible(true);
            m.setPersistent(false);
            m.setInvulnerable(true);
            m.setSilent(true);
            m.setGravity(false);
            m.setCollidable(false);
            m.setImmovable(true);
            m.setDescription(null);
            Nms.precisePosition(m);
        });
    }

    /**
     * Where the camera's eyes are and where they look at this tick of the flight: along a curve
     * through p0..p3 (high above the map, over its middle, above and behind the insertion point, the
     * player's eyes there), eased in and out. It starts looking down at the insertion point, turns (eased,
     * at most about two degrees a tick) to the bearing the squad will face once landed and levels
     * out on the way down, and glides the last tenth of the flight into the player's eyes without
     * turning. It used to look at the insertion point all the way down: passing right over it, the
     * view spun half a turn in three ticks.
     */
    private static Location frame(World world, Vector p0, Vector p1, Vector p2, Vector p3, Location spawn, int tick) {
        double t = Math.min(1.0, tick / (double) DURATION);
        double e = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
        Vector pos = bezier(p0, p1, p2, p3, e);
        Location first = p0.toLocation(world);
        first.setDirection(spawn.toVector().add(new Vector(0, 1, 0)).subtract(p0));
        double turn = ((spawn.getYaw() - first.getYaw()) % 360 + 540) % 360 - 180;
        Location loc = pos.toLocation(world);
        loc.setYaw((float) (first.getYaw() + turn * smooth(t / TURNED)));
        loc.setPitch((float) (first.getPitch() * (1 - smooth((t - 0.45) / (TURNED - 0.45)))));
        if (t >= 1.0) {
            loc.setYaw(spawn.getYaw());
            loc.setPitch(0);
        }
        return loc;
    }

    /** Smoothstep: 0 up to 0, 1 from 1 on, eased in and out between. */
    private static double smooth(double x) {
        double k = Math.max(0, Math.min(1, x));
        return k * k * (3 - 2 * k);
    }

    /** The feet of a standing player whose eyes are at {@code eyes}. */
    private static Location feet(Location eyes) {
        return eyes.clone().subtract(0, EYE, 0);
    }

    /** Whether this is an entry camera, which the player flying in on it may not leave. */
    public static boolean isCamera(@Nullable Entity entity) {
        return entity instanceof Mannequin
                && ROLE.equals(entity.getPersistentDataContainer().get(Keys.ENTITY_ROLE, PersistentDataType.STRING));
    }

    /** Whether this is the entry camera of the player with this UUID. */
    public static boolean isCameraOf(@Nullable Entity entity, UUID player) {
        return isCamera(entity)
                && player.toString().equals(entity.getPersistentDataContainer().get(Keys.ENTITY_REF, PersistentDataType.STRING));
    }

    private static boolean isRanked(Raid raid, Player player) {
        RaidPlayer rp = raid.player(player.getUniqueId());
        return rp != null && rp.ranked;
    }

    private static Vector bezier(Vector a, Vector b, Vector c, Vector d, double t) {
        double u = 1 - t;
        return a.clone().multiply(u * u * u)
                .add(b.clone().multiply(3 * u * u * t))
                .add(c.clone().multiply(3 * u * t * t))
                .add(d.clone().multiply(t * t * t));
    }
}
