package net.infnetwork.snowball.craftov.ai;

import io.papermc.paper.raytracing.BlockCollisionMode;
import io.papermc.paper.raytracing.RayTraceTarget;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.function.Predicate;

/**
 * What gets through a soldier's line of sight. A solid block stops it; a block you can see
 * through (glass, panes, iron bars, fences, leaves) lets half of it through, so two of them in a
 * row leave a quarter and three hide you. Blocks a player walks through (grass, flowers, signs,
 * open air) do not count. A round fired along the same line is another matter: every other block
 * stops it, and of the see-through ones it gets through those the caller says it does (glass it
 * shatters, and bars and leaves a round goes through, see Ballistics), so a soldier sees you
 * through a fence but cannot shoot you there. Blocks are met where they are drawn (their outline),
 * as rounds meet them: a fence or a wall counts one block high, not the 1.5 it is to walk into, so
 * a head showing over a railing or a parapet can be seen and shot, as a player behind it can
 * shoot.
 */
final class Sight {

    /** Below this much left, a line of sight counts as blocked. */
    private static final double OPAQUE = 0.12;

    /**
     * How much of a sight line gets through, 0 (none) to 1 (clear), and whether a round fired along
     * it reaches the end (never when nothing gets through).
     */
    record Line(double seen, boolean open) {
        static final Line BLOCKED = new Line(0, false);
        static final Line CLEAR = new Line(1, true);
    }

    private Sight() {
    }

    /** How much of a sight line from {@code from} to {@code to} gets through: 0 (none) to 1 (clear). */
    static double through(World world, Vector from, Vector to) {
        return look(world, from, to, block -> false).seen();
    }

    /**
     * What a sight line from {@code from} to {@code to} shows, and whether a round gets along it:
     * through each see-through block on the way that {@code passes}.
     */
    static Line look(World world, Vector from, Vector to, Predicate<Block> passes) {
        Vector line = to.clone().subtract(from);
        double length = line.length();
        if (length < 1e-3) {
            return Line.CLEAR;
        }
        Vector dir = line.multiply(1 / length);
        double shown = 1;
        boolean open = true;
        double travelled = 0;
        for (int i = 0; i < 8 && travelled < length; i++) {
            Location start = from.clone().add(dir.clone().multiply(travelled)).toLocation(world);
            double left = length - travelled;
            RayTraceResult hit = world.rayTrace(b -> b.start(start).direction(dir).maxDistance(left)
                    .fluidCollisionMode(FluidCollisionMode.NEVER).blockCollisionMode(BlockCollisionMode.OUTLINE)
                    .blockFilter(block -> !block.isPassable())
                    .targets(RayTraceTarget.BLOCK));
            if (hit == null || hit.getHitBlock() == null) {
                return new Line(shown, open);
            }
            Block block = hit.getHitBlock();
            if (!seeThrough(block.getType())) {
                return Line.BLOCKED;
            }
            open &= passes.test(block);
            shown *= 0.5;
            if (shown < OPAQUE) {
                return Line.BLOCKED;
            }
            // Carry on from where the line leaves this block.
            travelled = Math.max(travelled, exit(from, dir, block)) + 1e-3;
        }
        return new Line(shown, open);
    }

    /**
     * Length of the stretch of the segment {@code from}-{@code to} inside an upright ellipsoid
     * centred on {@code centre}, {@code radius} across and {@code height} up and down from it.
     */
    static double chord(Vector from, Vector to, Vector centre, double radius, double height) {
        // Squash the ellipsoid into a sphere; the fraction of the segment inside stays the same.
        double k = radius / height;
        Vector a = new Vector(from.getX() - centre.getX(), (from.getY() - centre.getY()) * k, from.getZ() - centre.getZ());
        Vector d = new Vector(to.getX() - from.getX(), (to.getY() - from.getY()) * k, to.getZ() - from.getZ());
        double qa = d.lengthSquared();
        if (qa < 1e-9) {
            return 0;
        }
        double qb = 2 * a.dot(d);
        double qc = a.lengthSquared() - radius * radius;
        double disc = qb * qb - 4 * qa * qc;
        if (disc <= 0) {
            return 0;
        }
        double root = Math.sqrt(disc);
        double t1 = Math.max(0, (-qb - root) / (2 * qa));
        double t2 = Math.min(1, (-qb + root) / (2 * qa));
        return t2 <= t1 ? 0 : (t2 - t1) * from.distance(to);
    }

    /** Blocks that stop a bullet or a body but not the eye. */
    static boolean seeThrough(Material type) {
        if (type == Material.TINTED_GLASS) {
            return false;
        }
        String name = type.name();
        return name.contains("GLASS") || name.endsWith("_BARS") || name.equals("CHAIN") || name.endsWith("_CHAIN")
                || Tag.LEAVES.isTagged(type) || Tag.FENCES.isTagged(type) || Tag.FENCE_GATES.isTagged(type);
    }

    /** Distance along {@code dir} from {@code origin} at which the line leaves {@code block}'s cell. */
    private static double exit(Vector origin, Vector dir, Block block) {
        return Math.min(edge(origin.getX(), dir.getX(), block.getX()),
                Math.min(edge(origin.getY(), dir.getY(), block.getY()), edge(origin.getZ(), dir.getZ(), block.getZ())));
    }

    private static double edge(double origin, double dir, int cell) {
        if (Math.abs(dir) < 1e-9) {
            return Double.MAX_VALUE;
        }
        return ((dir > 0 ? cell + 1 : cell) - origin) / dir;
    }
}
