package net.infnetwork.snowball.craftov.lobby;

import net.infnetwork.snowball.craftov.map.gen.Builder;
import org.bukkit.Material;
import org.bukkit.World;

/** Builds the operations hangar that serves as the lobby. */
final class LobbyBuilder {

    static final int Y = 64;
    static final int R = 22;

    private LobbyBuilder() {
    }

    static void build(World world) {
        Builder b = new Builder(world, 7L);
        // Floor with a tactical grid.
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                boolean line = Math.floorMod(x, 6) == 0 || Math.floorMod(z, 6) == 0;
                b.set(x, Y, z, line ? Material.GRAY_CONCRETE : Material.POLISHED_DEEPSLATE);
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
        // Shooting range along the east side (x 8..20, z -20..-8) separated by glass.
        for (int x = 8; x <= R - 1; x++) {
            for (int z = -R + 1; z <= -9; z++) {
                b.set(x, Y, z, Math.floorMod(x, 4) == 0 ? Material.YELLOW_CONCRETE : Material.SMOOTH_STONE);
            }
        }
        for (int z = -R + 1; z <= -9; z++) {
            b.set(7, Y + 1, z, Material.POLISHED_BLACKSTONE_SLAB);
        }
        for (int x = 8; x <= R - 1; x++) {
            b.set(x, Y + 1, -9, Material.POLISHED_BLACKSTONE_SLAB);
        }
        for (int x = 8; x <= R - 1; x++) {
            for (int y = Y + 1; y <= Y + 4; y++) {
                b.set(x, y, -R + 1, Material.HAY_BLOCK);
            }
        }
    }
}
