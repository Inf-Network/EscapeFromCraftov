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
 * do not count.
 */
final class Sight {

    /** Below this much left, a line of sight counts as blocked. */
    private static final double OPAQUE = 0.12;

    private Sight() {
    }

    /** How much of a sight line from {@code from} to {@code to} gets through: 0 (none) to 1 (clear). */
    static double through(World world, Vector from, Vector to) {
        Vector line = to.clone().subtract(from);
        double length = line.length();
        if (length < 1e-3) {
            return 1;
        }
        Vector dir = line.multiply(1 / length);
        double left = 1;
        double travelled = 0;
        for (int i = 0; i < 8 && travelled < length; i++) {
            RayTraceResult hit = world.rayTraceBlocks(from.clone().add(dir.clone().multiply(travelled)).toLocation(world),
                    dir, length - travelled, FluidCollisionMode.NEVER, true);
            if (hit == null || hit.getHitBlock() == null) {
                return left;
            }
            Block block = hit.getHitBlock();
            if (!seeThrough(block.getType())) {
                return 0;
            }
            left *= 0.5;
            if (left < OPAQUE) {
                return 0;
            }
            // Carry on from where the line leaves this block.
            travelled = Math.max(travelled, exit(from, dir, block)) + 1e-3;
        }
        return left;
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
