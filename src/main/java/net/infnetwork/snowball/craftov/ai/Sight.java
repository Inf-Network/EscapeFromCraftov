package net.infnetwork.snowball.craftov.ai;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * What gets through a soldier's line of sight. A solid block stops it; a block you can see
 * through (glass, panes, iron bars, fences, leaves) lets half of it through, so two of them in a
 * row leave a quarter and three hide you. Blocks without collision (grass, flowers, open air)
 * do not count. A round fired along the same line is another matter: everything with collision
 * stops it, and of the see-through blocks only glass gives way (the round that hits it breaks it,
 * see Ballistics), so a soldier can see you through a fence, bars or a hedge but not shoot you.
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
        return look(world, from, to).seen();
    }

    /** What a sight line from {@code from} to {@code to} shows, and whether a round gets along it. */
    static Line look(World world, Vector from, Vector to) {
        Vector line = to.clone().subtract(from);
        double length = line.length();
        if (length < 1e-3) {
            return Line.CLEAR;
        }
        Vector dir = line.multiply(1 / length);
        double left = 1;
        boolean open = true;
        double travelled = 0;
        for (int i = 0; i < 8 && travelled < length; i++) {
            RayTraceResult hit = world.rayTraceBlocks(from.clone().add(dir.clone().multiply(travelled)).toLocation(world),
                    dir, length - travelled, FluidCollisionMode.NEVER, true);
            if (hit == null || hit.getHitBlock() == null) {
                return new Line(left, open);
            }
            Block block = hit.getHitBlock();
            if (!seeThrough(block.getType())) {
                return Line.BLOCKED;
            }
            open &= breaks(block.getType());
            left *= 0.5;
            if (left < OPAQUE) {
                return Line.BLOCKED;
            }
            // Carry on from where the line leaves this block.
            travelled = Math.max(travelled, exit(from, dir, block)) + 1e-3;
        }
        return new Line(left, open);
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

    /** Glass and panes, which the first round that hits them breaks (Ballistics.impact); tinted glass holds. */
    static boolean breaks(Material type) {
        return type != Material.TINTED_GLASS && type.name().contains("GLASS");
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
