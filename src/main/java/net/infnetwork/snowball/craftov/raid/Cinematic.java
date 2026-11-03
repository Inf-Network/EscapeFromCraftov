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
import java.util.List;
import java.util.function.Consumer;

/**
 * Entry camera: the squad's view sweeps from high above the map down to their insertion point.
 * Implemented with an invisible item display (smooth teleport interpolation) that the players
 * spectate.
 */
public final class Cinematic {

    private static final int DURATION = 130;
    private static final int STEP = 2;

    private Cinematic() {
    }

    public static void play(CraftovPlugin plugin, Raid raid, int squadNumber, List<Player> players, Location spawn,
                            Consumer<Player> onDone) {
        if (!plugin.getConfig().getBoolean("cinematic.enabled", true) || players.isEmpty()) {
            players.forEach(onDone);
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
            player.setGameMode(GameMode.SPECTATOR);
            player.teleport(camera.getLocation());
        }
        String tierLine = raid.tier.tag() + " <#8A8F98>· " + raid.mode.displayName() + (raid.ranked ? " · <#FFC23D>排位" : "");
        new BukkitRunnable() {
            int tick;

            @Override
            public void run() {
                if (raid.state == Raid.State.CLOSED || !camera.isValid()) {
                    cancel();
                    camera.remove();
                    players.forEach(p -> {
                        if (p.isOnline() && p.getGameMode() == GameMode.SPECTATOR) {
                            p.setSpectatorTarget(null);
                        }
                    });
                    return;
                }
                double t = Math.min(1.0, tick / (double) DURATION);
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
                for (Player player : players) {
                    if (!player.isOnline()) {
                        continue;
                    }
                    if (player.getSpectatorTarget() != camera) {
                        if (player.getLocation().distanceSquared(loc) > 64 * 64) {
                            player.teleport(loc);
                        }
                        player.setSpectatorTarget(camera);
                    }
                    if (tick == 4) {
                        player.showTitle(Title.title(Text.mm("<b><#F2E6C8>" + Text.escape(raid.map.name())),
                                Text.mm(tierLine), Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2600), Duration.ofMillis(600))));
                        player.playSound(player.getLocation(), "craftov:ui.cinematic", SoundCategory.MASTER, 1f, 1f);
                    } else if (tick == 70) {
                        player.showTitle(Title.title(Text.mm(""), Text.mm("<#9AA0A6>第 " + squadNumber + " 小队 · 正在接近渗透点"),
                                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(300))));
                    }
                }
                if (t >= 1.0) {
                    cancel();
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        for (Player player : players) {
                            if (player.isOnline()) {
                                if (player.getGameMode() == GameMode.SPECTATOR) {
                                    player.setSpectatorTarget(null);
                                }
                                onDone.accept(player);
                            }
                        }
                        camera.remove();
                    }, 4L);
                }
                tick += STEP;
            }
        }.runTaskTimer(plugin, 2L, STEP);
    }

    private static Vector bezier(Vector a, Vector b, Vector c, Vector d, double t) {
        double u = 1 - t;
        return a.clone().multiply(u * u * u)
                .add(b.clone().multiply(3 * u * u * t))
                .add(c.clone().multiply(3 * u * t * t))
                .add(d.clone().multiply(t * t * t));
    }
}
