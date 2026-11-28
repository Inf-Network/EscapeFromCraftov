package net.infnetwork.snowball.craftov.lobby;

import net.infnetwork.snowball.craftov.map.gen.Builder;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Builds the part of a range plot west of the hangar corner it is copied from (RangeService): two
 * more lanes beside the corner's, shot from the same firing line.
 *
 * <pre>
 *   x  -34        -10          6  7          22
 *  z-43 +----------+-----------+
 *       | hay                  |
 *       | moving   |  bullseye |
 *       | rails    |  25 m     |
 *       | 30 m     |           |
 *  z-22 | 20 m     |           +-------------+   (the corner's north wall, window to the 50 m target)
 *       | 10 m     |  10 m     |  silhouettes |
 *  z -9 +====== firing rail ===============+  |
 *       | firing line                          |
 *  z -1 +--------------------------------------+
 * </pre>
 *
 * Every lane's distances are counted from the firing rail (z = -9), as the corner's always were.
 * The walls match the corner's: deepslate tiles with a band of glass, eight blocks high, open to
 * the sky. All coordinates are the hangar's; a plot is {@code dx} blocks east of them.
 */
final class RangeBuilder {

    static final int Y = LobbyBuilder.Y;
    /** The rail along the firing line; lane distances are counted from it. */
    static final int RAIL_Z = -9;
    static final int WEST = -34;
    static final int FAR = -43;
    static final int BACK = -1;
    /** The annex's east wall north of the corner, and the corner's east wall. */
    static final int EAST = 6;
    static final int CORNER_EAST = LobbyBuilder.R;
    /** The slab between the bullseye lane (east of it) and the moving lane. */
    static final int DIVIDER_X = -10;
    /** Moving targets' rails: from RAIL_X0 to RAIL_X1, at 10, 20 and 30 m. */
    static final int RAIL_X0 = -31;
    static final int RAIL_X1 = -13;
    static final int[] RAILS_Z = {RAIL_Z - 10, RAIL_Z - 20, RAIL_Z - 30};
    /** Bullseye stands (x, z): 10 m and 25 m, apart so the near one never hides the far one. */
    static final int[][] BULLSEYES = {{-5, RAIL_Z - 10}, {1, RAIL_Z - 25}};
    /** Silhouettes in the corner's lane (x, z), where its three dummies stood: 4, 7 and 11 m. */
    static final int[][] SILHOUETTES = {{11, RAIL_Z - 4}, {15, RAIL_Z - 7}, {19, RAIL_Z - 11}};
    /** The middle of each lane along the firing line, for its sign: silhouettes, bullseyes, moving. */
    static final double[] LANE_SIGN_X = {14.5, -1.5, -21.5};
    private static final int HEIGHT = 8;

    private RangeBuilder() {
    }

    /** The annex of the plot {@code dx} blocks east of the hangar, built over whatever was left there. */
    static void build(World world, int dx) {
        Builder b = new Builder(world, 7L);
        // Clear what a last session may have left (an engineer's shield) before building.
        for (int x = WEST + 1; x < EAST; x++) {
            for (int z = FAR + 1; z < BACK; z++) {
                for (int y = Y + 1; y <= Y + HEIGHT; y++) {
                    Block block = world.getBlockAt(x + dx, y, z);
                    if (!block.getType().isAir()) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
        for (int x = WEST; x <= EAST; x++) {
            for (int z = FAR; z <= BACK; z++) {
                b.set(x + dx, Y - 1, z, Material.DEEPSLATE);
                b.set(x + dx, Y, z, floor(x, z));
            }
        }
        for (int x = EAST + 1; x <= CORNER_EAST; x++) {
            b.set(x + dx, Y - 1, BACK, Material.DEEPSLATE);
            b.set(x + dx, Y, BACK, Material.POLISHED_DEEPSLATE);
        }
        for (int y = Y + 1; y <= Y + HEIGHT; y++) {
            for (int x = WEST; x <= EAST; x++) {
                b.set(x + dx, y, FAR, wall(x, y));
            }
            for (int z = FAR; z <= BACK; z++) {
                b.set(WEST + dx, y, z, wall(z, y));
            }
            for (int z = FAR; z < -LobbyBuilder.R; z++) {
                b.set(EAST + dx, y, z, wall(z, y));
            }
            for (int x = WEST; x <= CORNER_EAST; x++) {
                b.set(x + dx, y, BACK, wall(x, y));
            }
        }
        // The firing rail joins the corner's lane rail at x = 7; the divider runs down the range.
        for (int x = WEST + 1; x <= EAST; x++) {
            b.set(x + dx, Y + 1, RAIL_Z, Material.POLISHED_BLACKSTONE_SLAB);
        }
        for (int z = FAR + 1; z < RAIL_Z; z++) {
            b.set(DIVIDER_X + dx, Y + 1, z, Material.POLISHED_BLACKSTONE_SLAB);
        }
        // Hay at the far end, as in the corner's lane.
        for (int x = WEST + 1; x < EAST; x++) {
            for (int y = Y + 1; y <= Y + 4; y++) {
                b.set(x + dx, y, FAR + 1, Material.HAY_BLOCK);
            }
        }
        for (int z : RAILS_Z) {
            for (int x = RAIL_X0; x <= RAIL_X1; x++) {
                b.set(x + dx, Y + 1, z, "rail[shape=east_west]");
            }
            b.set(RAIL_X0 - 1 + dx, Y + 1, z, Material.POLISHED_BLACKSTONE_WALL);
            b.set(RAIL_X1 + 1 + dx, Y + 1, z, Material.POLISHED_BLACKSTONE_WALL);
        }
        for (int[] stand : BULLSEYES) {
            b.set(stand[0] + dx, Y + 1, stand[1], Material.DARK_OAK_FENCE);
        }
    }

    /** The firing line has the hangar's grid; the lanes the corner lane's yellow stripes. */
    private static Material floor(int x, int z) {
        if (z >= RAIL_Z || x == WEST || x == EAST || z == FAR || z == BACK) {
            return Math.floorMod(x, 6) == 0 || Math.floorMod(z, 6) == 0 ? Material.GRAY_CONCRETE : Material.POLISHED_DEEPSLATE;
        }
        return x != DIVIDER_X && Math.floorMod(x, 4) == 0 ? Material.YELLOW_CONCRETE : Material.SMOOTH_STONE;
    }

    /** The hangar's wall: deepslate tiles with a band of glass two blocks high, a pillar every fourth block. */
    private static Material wall(int along, int y) {
        return (y == Y + 4 || y == Y + 5) && Math.floorMod(along, 4) != 0 ? Material.GRAY_STAINED_GLASS : Material.DEEPSLATE_TILES;
    }
}
