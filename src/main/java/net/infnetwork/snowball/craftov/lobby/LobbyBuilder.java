package net.infnetwork.snowball.craftov.lobby;

import net.infnetwork.snowball.craftov.map.gen.Builder;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Builds the operations hangar that serves as the lobby. The firing lane that used to stand in its
 * north-east corner, with three dummies, is gone from the lobby: shooting is the private range's
 * job, and each range plot (a copy of that corner) gets the lane painted on instead ({@link #lane}).
 */
final class LobbyBuilder {

    static final int Y = 64;
    static final int R = 22;
    /** The firing lane: floor x 8..R-1, z -R+1..-9, a rail along its open sides, hay at the far end. */
    private static final int LANE_X0 = 8;
    private static final int LANE_Z1 = -9;

    private LobbyBuilder() {
    }

    /** The hangar floor's tactical grid. */
    private static Material floor(int x, int z) {
        return Math.floorMod(x, 6) == 0 || Math.floorMod(z, 6) == 0 ? Material.GRAY_CONCRETE : Material.POLISHED_DEEPSLATE;
    }

    static void build(World world) {
        Builder b = new Builder(world, 7L);
        // Floor with a tactical grid.
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                b.set(x, Y, z, floor(x, z));
                b.set(x, Y - 1, z, Material.DEEPSLATE);
            }
        }
        // Walls and roof.
        for (int y = Y + 1; y <= Y + 9; y++) {
            for (int i = -R; i <= R; i++) {
                Material wall = (y == Y + 4 || y == Y + 5) && Math.floorMod(i, 4) != 0 ? Material.GRAY_STAINED_GLASS : Material.DEEPSLATE_TILES;
                if (y == Y + 9) {
                    wall = Material.POLISHED_BLACKSTONE;
                }
                b.set(i, y, -R, wall);
                b.set(i, y, R, wall);
                b.set(-R, y, i, wall);
                b.set(R, y, i, wall);
            }
        }
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                boolean light = Math.floorMod(x, 6) == 3 && Math.floorMod(z, 6) == 3;
                b.set(x, Y + 10, z, light ? Material.SEA_LANTERN : Material.POLISHED_BLACKSTONE);
            }
        }
        for (int x = -R + 1; x < R; x += 6) {
            for (int y = Y + 1; y <= Y + 9; y++) {
                b.set(x, y, -R + 1, Material.POLISHED_BLACKSTONE_WALL);
                b.set(x, y, R - 1, Material.POLISHED_BLACKSTONE_WALL);
            }
        }
        // Central briefing table with a map hologram pad.
        b.fill(-3, Y + 1, -3, 3, Y + 1, 3, Material.POLISHED_BLACKSTONE);
        b.fill(-2, Y + 1, -2, 2, Y + 1, 2, Material.CYAN_STAINED_GLASS);
        b.set(0, Y + 1, 0, Material.SEA_LANTERN);
        // Station pedestals around the hall.
        for (int[] p : LobbyService.STATIONS_XZ) {
            b.set(p[0], Y + 1, p[1], Material.LODESTONE);
            b.set(p[0], Y, p[1], Material.SEA_LANTERN);
        }
    }

    /**
     * Paints the firing lane onto a range plot, {@code dx} blocks east of the hangar it was copied
     * from: a striped floor, a slab rail along its open sides and hay bales at the far end.
     */
    static void lane(World world, int dx) {
        Builder b = new Builder(world, 7L);
        for (int x = LANE_X0; x <= R - 1; x++) {
            for (int z = -R + 1; z <= LANE_Z1; z++) {
                b.set(x + dx, Y, z, Math.floorMod(x, 4) == 0 ? Material.YELLOW_CONCRETE : Material.SMOOTH_STONE);
            }
        }
        for (int z = -R + 1; z <= LANE_Z1; z++) {
            b.set(LANE_X0 - 1 + dx, Y + 1, z, Material.POLISHED_BLACKSTONE_SLAB);
        }
        for (int x = LANE_X0; x <= R - 1; x++) {
            b.set(x + dx, Y + 1, LANE_Z1, Material.POLISHED_BLACKSTONE_SLAB);
        }
        for (int x = LANE_X0; x <= R - 1; x++) {
            for (int y = Y + 1; y <= Y + 4; y++) {
                b.set(x + dx, y, -R + 1, Material.HAY_BLOCK);
            }
        }
    }

    /**
     * Turns the lane a hangar built before #164 still has back into plain hangar: the floor grid,
     * no rail, and the wall columns the hay bales had replaced.
     */
    static void clearLane(World world) {
        Builder b = new Builder(world, 7L);
        for (int x = LANE_X0; x <= R - 1; x++) {
            for (int z = -R + 1; z <= LANE_Z1; z++) {
                b.set(x, Y, z, floor(x, z));
            }
        }
        for (int z = -R + 1; z <= LANE_Z1; z++) {
            b.set(LANE_X0 - 1, Y + 1, z, Material.AIR);
        }
        for (int x = LANE_X0; x <= R - 1; x++) {
            b.set(x, Y + 1, LANE_Z1, Material.AIR);
            // build() stands a column every 6 blocks in front of the walls (x = -R+1, -R+7, ...).
            boolean column = Math.floorMod(x - (-R + 1), 6) == 0;
            for (int y = Y + 1; y <= Y + 4; y++) {
                b.set(x, y, -R + 1, column ? Material.POLISHED_BLACKSTONE_WALL : Material.AIR);
            }
        }
    }
}
