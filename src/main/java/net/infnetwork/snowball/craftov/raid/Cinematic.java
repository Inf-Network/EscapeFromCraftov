package net.infnetwork.snowball.craftov.raid;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Entry camera: the squad's view sweeps from high above the map down to their insertion point.
 * Implemented with an invisible item display (smooth teleport interpolation) that the players
 * spectate. Players are looked up by UUID on every step, never kept as Player objects: someone who
 * drops out and reconnects during the flight is a new Player object, and RaidManager.tryRejoin puts
 * them back in spectator in the raid world so the camera picks them up again. Whoever ends up
 * outside the raid world while flying in (a command, a plugin) is brought straight back to the
 * camera, and the flight always ends and lands everyone, whatever happens to one of them.
 */
public final class Cinematic {

    private static final int DURATION = 130;
    private static final int STEP = 2;
    /** The black curtain over the jump into the raid world: fade in, hold and fade out, in ticks. */
    private static final int CURTAIN_IN = 4;
    private static final int CURTAIN_STAY = 14;
    private static final int CURTAIN_OUT = 10;
    /**
     * The flight step at which the map's name comes up: once the curtain is gone, since a new title
     * replaces the one on screen and the curtain is a title. A multiple of STEP.
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
        Vector p3 = spawn.toVector().add(new Vector(0, 1.62, 0));
        ItemDisplay camera = raid.world.spawn(new Location(raid.world, p0.getX(), p0.getY(), p0.getZ()), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setTeleportDuration(STEP + 1);
            d.setViewRange(0f);
        });
        for (Player player : players) {
            // A black curtain over the jump into the raid world, so its loading never shows.
            net.infnetwork.snowball.craftov.gui.UiFont.blackout(player, CURTAIN_IN, CURTAIN_STAY, CURTAIN_OUT);
            player.setGameMode(GameMode.SPECTATOR);
            player.teleport(camera.getLocation());
        }
        String tierLine = raid.tier.tag() + " <muted>· " + (players.size() <= 1 ? "单人行动" : players.size() + " 人小队");
        new BukkitRunnable() {
            int tick;

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
                tick += STEP;
                double t = Math.min(1.0, now / (double) DURATION);
                double e = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
                Vector pos = bezier(p0, p1, p2, p3, e);
                Vector look = e > 0.85 ? spawn.getDirection().multiply(10).add(p3) : spawn.toVector().add(new Vector(0, 1, 0));
                if (e > 0.6 && e <= 0.85) {
                    double k = (e - 0.6) / 0.25;
                    look = spawn.toVector().add(new Vector(0, 1, 0)).multiply(1 - k)
                            .add(spawn.getDirection().multiply(10).add(p3).multiply(k));
                }
                Location loc = pos.toLocation(raid.world);
                loc.setDirection(look.clone().subtract(pos));
                if (t >= 1.0) {
                    loc.setYaw(spawn.getYaw());
                    loc.setPitch(0);
                }
                camera.teleport(loc);
                for (Player player : aboard()) {
                    try {
                        follow(player, loc, now);
                    } catch (RuntimeException ex) {
                        // One player's trouble must not hold up the rest of the squad.
                        plugin.getLogger().log(Level.WARNING, "Entry camera could not take " + player.getName() + " along", ex);
                    }
                }
                if (t >= 1.0) {
                    cancel();
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        release();
                        land();
                        camera.remove();
                    }, 4L);
                }
            }

            private void follow(Player player, Location loc, int now) {
                if (player.getGameMode() != GameMode.SPECTATOR) {
                    player.setGameMode(GameMode.SPECTATOR);
                }
                // The world first: the distance to a player in another world cannot be measured.
                if (player.getWorld() != raid.world || player.getLocation().distanceSquared(loc) > 64 * 64) {
                    // Somewhere else (just reconnected, or sent to another world by a command):
                    // next to the camera now, and attached on the next step, once the client has
                    // been sent the camera entity.
                    if (player.getSpectatorTarget() != null) {
                        player.setSpectatorTarget(null);
                    }
                    player.teleport(loc);
                    return;
                }
                if (player.getSpectatorTarget() != camera) {
                    player.setSpectatorTarget(camera);
                }
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
        }.runTaskTimer(plugin, 2L, STEP);
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
