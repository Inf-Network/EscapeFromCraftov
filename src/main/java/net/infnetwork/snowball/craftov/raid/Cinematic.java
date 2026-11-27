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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Entry camera: the squad's view sweeps from high above the map down to their insertion point and
 * ends in their own eyes. The players spectate a mannequin that only they are sent (nobody else sees
 * it, and as the entity they look through it is not drawn for them), moved by the server every
 * update and glided between those positions by the client, so the view moves on every frame. Its
 * moves are sent with exact positions and pitch (Nms#precisePosition). A mannequin rather than an
 * invisible display: the client eases its camera's eye height towards that of the entity it looks
 * through, and a display's is 0, so on landing the view dropped to the player's feet (through the
 * floor for a frame) and rose to their eyes; a mannequin's eyes are at a standing player's height.
 * A client only takes the camera once it has been sent the mannequin, which comes with the chunk it
 * is in, a few ticks after the jump into the raid world: told earlier, it ignores the camera and
 * stays in its own body, which the server then drags along in jumps (#166, what the entry camera
 * looked like before). So a player is put on the camera once it is tracked for them, kept up with it
 * until then, and put on it again if their client ever loses it.
 * Players are looked up by UUID on every step, never kept as Player objects: someone who drops out
 * and reconnects during the flight is a new Player object, and RaidManager.tryRejoin puts them back
 * in spectator in the raid world so the camera picks them up again. Whoever ends up outside the raid
 * world while flying in (a command, a plugin) is brought straight back to the camera, and the flight
 * always ends and lands everyone, whatever happens to one of them.
 */
public final class Cinematic {

    /** The flight, in ticks. */
    private static final int DURATION = 130;
    /**
     * Ticks from the last frame to the landing: the client glides an entity over three ticks to
     * each position it is sent, so the view is then at rest in the squad's eyes.
     */
    private static final int SETTLE = 4;
    /** The {@link Keys#ENTITY_ROLE} of the camera: bullets, grenades and the HUD pass over it. */
    private static final String ROLE = "entry_camera";
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
     * Flies the camera for these players, then calls onDone with every one of their UUIDs, online
     * or not, once it lands (at once when the camera is off).
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
        Vector p2 = spawn.toVector().add(new Vector(0, 22, 0)).add(spawn.getDirection().multiply(-14));
        Vector p3 = spawn.toVector().add(new Vector(0, EYE, 0));
        Location start = frame(raid.world, p0, p1, p2, p3, spawn, 0);
        Mannequin camera = raid.world.spawn(feet(start), Mannequin.class, m -> {
            m.getPersistentDataContainer().set(Keys.ENTITY_ROLE, PersistentDataType.STRING, ROLE);
            // Sent to the squad alone (attach): to anyone else, a spectator flying in on another
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
        });
        Nms.precisePosition(camera);
        for (Player player : players) {
            // A black curtain over the jump into the raid world, so its loading never shows.
            net.infnetwork.snowball.craftov.gui.UiFont.blackout(player, CURTAIN_IN, CURTAIN_STAY, CURTAIN_OUT);
            player.setGameMode(GameMode.SPECTATOR);
            // Already looking where the camera does: until the client has the camera, this is the view.
            player.teleport(feet(start));
        }
        String tierLine = raid.tier.tag() + " <muted>· " + (players.size() <= 1 ? "单人行动" : players.size() + " 人小队");
        new BukkitRunnable() {
            int tick;
            /** Squad members whose client was put on the camera while it had the mannequin. */
            final Set<UUID> shown = new HashSet<>();

            @Override
            public void run() {
                if (raid.state == Raid.State.CLOSED || !camera.isValid()) {
                    cancel();
                    camera.remove();
                    release();
                    if (raid.state == Raid.State.RUNNING) {
                        land(); // the camera vanished mid-flight: land everyone rather than leave them hanging
                    }
                    return;
                }
                int now = tick;
                // Counted first: whatever goes wrong further down, the flight still reaches its end
                // and the squad lands. Counted at the bottom, an exception on every step kept the
                // camera on the same frame for ever and nobody was ever put down.
                tick++;
                Location loc = feet(frame(raid.world, p0, p1, p2, p3, spawn, now));
                camera.teleport(loc);
                // The head too: it is what the camera looks along.
                camera.setRotation(loc.getYaw(), loc.getPitch());
                for (Player player : aboard()) {
                    try {
                        follow(player, loc, now);
                    } catch (RuntimeException ex) {
                        // One player's trouble must not hold up the rest of the squad.
                        plugin.getLogger().log(Level.WARNING, "Entry camera could not take " + player.getName() + " along", ex);
                    }
                }
                if (now >= DURATION) {
                    cancel();
                    // Once the client has glided the camera onto its last position.
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        release();
                        land();
                        camera.remove();
                    }, SETTLE);
                }
            }

            private void follow(Player player, Location loc, int now) {
                if (player.getGameMode() != GameMode.SPECTATOR) {
                    player.setGameMode(GameMode.SPECTATOR);
                }
                attach(player, loc);
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
             * Puts the player on the camera, or keeps them with it until their client can be. The
             * client gets the camera with the chunk it is in; a camera packet before that is
             * dropped, and the player would stay in their own body for the whole flight.
             */
            private void attach(Player player, Location loc) {
                UUID id = player.getUniqueId();
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
                    shown.remove(id);
                    return;
                }
                boolean onCamera = player.getSpectatorTarget() == camera;
                if (!camera.isTrackedBy(player)) {
                    // Not sent the camera yet (or lost it): along with it until it is, looking the
                    // same way, so the view the client does have is the camera's.
                    shown.remove(id);
                    if (!onCamera) {
                        player.teleport(loc);
                    }
                } else if (!onCamera) {
                    player.setSpectatorTarget(camera);
                    shown.add(id);
                } else if (!shown.contains(id) && Nms.sendCamera(player, camera)) {
                    // On it on the server, but the client did not have the camera when told so.
                    shown.add(id);
                }
            }

            /**
             * Squad members still flying in: online and neither landed nor settled, wherever they
             * are. follow() brings back whoever is not on the camera.
             */
            private List<Player> aboard() {
                List<Player> out = new ArrayList<>();
                for (UUID id : ids) {
                    RaidPlayer rp = raid.player(id);
                    Player player = Bukkit.getPlayer(id);
                    if (player != null && rp != null && rp.state == RaidPlayer.State.DEPLOYING) {
                        out.add(player);
                    }
                }
                return out;
            }

            /** Takes the squad off the camera: the flight is over for all of them, landed or not. */
            private void release() {
                for (UUID id : ids) {
                    RaidPlayer rp = raid.player(id);
                    if (rp != null) {
                        rp.inFlight = false;
                    }
                    Player player = Bukkit.getPlayer(id);
                    if (player == null) {
                        continue;
                    }
                    try {
                        if (player.getSpectatorTarget() == camera) {
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
     * Where the camera's eyes are and where they look at this tick of the flight: along a curve
     * through p0..p3 (high above the map, over its middle, above and behind the insertion point, the
     * squad's eyes), eased in and out, looking at the insertion point and then ahead along the
     * bearing the squad will face, ending exactly in their eyes on that bearing.
     */
    private static Location frame(World world, Vector p0, Vector p1, Vector p2, Vector p3, Location spawn, int tick) {
        double t = Math.min(1.0, tick / (double) DURATION);
        double e = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
        Vector pos = bezier(p0, p1, p2, p3, e);
        Vector look = e > 0.85 ? spawn.getDirection().multiply(10).add(p3) : spawn.toVector().add(new Vector(0, 1, 0));
        if (e > 0.6 && e <= 0.85) {
            double k = (e - 0.6) / 0.25;
            look = spawn.toVector().add(new Vector(0, 1, 0)).multiply(1 - k)
                    .add(spawn.getDirection().multiply(10).add(p3).multiply(k));
        }
        Location loc = pos.toLocation(world);
        loc.setDirection(look.clone().subtract(pos));
        if (t >= 1.0) {
            loc.setYaw(spawn.getYaw());
            loc.setPitch(0);
        }
        return loc;
    }

    /** The feet of a standing player whose eyes are at {@code eyes}. */
    private static Location feet(Location eyes) {
        return eyes.clone().subtract(0, EYE, 0);
    }

    /** Whether this is an entry camera, which the squad flying in on it may not leave. */
    public static boolean isCamera(@Nullable Entity entity) {
        return entity instanceof Mannequin
                && ROLE.equals(entity.getPersistentDataContainer().get(Keys.ENTITY_ROLE, PersistentDataType.STRING));
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
