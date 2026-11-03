package net.infnetwork.snowball.craftov.map.gen;

import net.infnetwork.snowball.craftov.map.ExtractType;
import net.infnetwork.snowball.craftov.map.MapDef;
import net.infnetwork.snowball.craftov.map.Pos;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the original "锈港 / Rust Harbor" raid map: a fenced port compound with a three-storey
 * command centre, warehouses, a container yard, docks, dormitories, a clinic, a garage and a
 * radio tower. Gameplay markers are collected into a {@link Blueprint} as the map is built.
 */
public final class RustPortGenerator {

    public static final String ID = "rustport";
    static final int S = 208;
    static final int G = 64;
    static final int SEA_X = 188;

    private final Builder b;
    private final Blueprint bp = new Blueprint();

    private record Palette(Material floor, Material wall, Material pillar, Material inner, Material roof, String glass,
                           String wood, Material trim) {
    }

    private static final Palette HQ = new Palette(Material.POLISHED_ANDESITE, Material.LIGHT_GRAY_CONCRETE, Material.GRAY_CONCRETE,
            Material.WHITE_CONCRETE, Material.SMOOTH_STONE, "light_blue_stained_glass_pane", "dark_oak", Material.CYAN_TERRACOTTA);
    private static final Palette DORM = new Palette(Material.SPRUCE_PLANKS, Material.BRICKS, Material.STONE_BRICKS,
            Material.BIRCH_PLANKS, Material.STONE_BRICKS, "glass_pane", "spruce", Material.MUD_BRICKS);
    private static final Palette CLINIC = new Palette(Material.WHITE_CONCRETE, Material.WHITE_TERRACOTTA, Material.LIGHT_GRAY_CONCRETE,
            Material.WHITE_CONCRETE, Material.SMOOTH_STONE, "glass_pane", "birch", Material.RED_CONCRETE);
    private static final Palette GARAGE = new Palette(Material.GRAY_CONCRETE, Material.CUT_COPPER, Material.WEATHERED_CUT_COPPER,
            Material.STONE_BRICKS, Material.WEATHERED_CUT_COPPER, "gray_stained_glass_pane", "spruce", Material.YELLOW_CONCRETE);
    private static final Palette OFFICE = new Palette(Material.POLISHED_ANDESITE, Material.STONE_BRICKS, Material.POLISHED_DEEPSLATE,
            Material.LIGHT_GRAY_CONCRETE, Material.SMOOTH_STONE, "glass_pane", "dark_oak", Material.GREEN_TERRACOTTA);

    public RustPortGenerator(World world) {
        this.b = new Builder(world, 0xC0FFEEL);
    }

    public MapDef generate() {
        ground();
        roads();
        perimeter();
        docks();
        hq();
        building("dorm", 14, 14, 30, 16, 2, DORM, new String[]{"locker", "crate", "parcel", "med_box", "crate"}, 0, true, "dorm_201");
        building("clinic", 14, 84, 20, 14, 1, CLINIC, new String[]{"med_box", "med_box", "crate", "filing_cabinet"}, 0, false, null);
        building("checkpoint", 76, 18, 20, 12, 1, OFFICE, new String[]{"filing_cabinet", "ammo_box", "locker", "computer"}, 0, false, null);
        building("garage_office", 14, 150, 16, 12, 1, GARAGE, new String[]{"toolbox", "toolbox", "crate", "computer"}, 0, false, null);
        warehouse(118, 14, 38, 20, "wh_office", 1);
        warehouse(118, 44, 38, 20, null, 0);
        garageHall(34, 150, 22, 18);
        containerYard(118, 128, 62, 64);
        radioTower(56, 184);
        props();
        markers();
        return new MapDef(ID, "锈港", "被遗弃的军用港口。指挥中心机房藏着最值钱的东西，码头和铁道是你的退路。",
                "craftov_tpl_" + ID, 25 * 60, new Pos(104, 128, 104, 0),
                List.copyOf(bp.spawns), List.copyOf(bp.extracts), List.copyOf(bp.containers), List.copyOf(bp.ai),
                List.copyOf(bp.bosses), List.copyOf(bp.doors), List.copyOf(bp.switches), 0, 0, S - 1, S - 1, "dusk");
    }

    public long blocksPlaced() {
        return b.placed();
    }

    // ------------------------------------------------------------------ terrain

    private void ground() {
        Material[] yard = {Material.GRAVEL, Material.COARSE_DIRT, Material.ANDESITE, Material.STONE, Material.GRAVEL,
                Material.PACKED_MUD, Material.DIRT_PATH};
        for (int x = -20; x < S + 20; x++) {
            for (int z = -20; z < S + 20; z++) {
                boolean sea = x >= SEA_X;
                b.set(x, G - 2, z, Material.STONE);
                if (sea) {
                    b.set(x, G - 6, z, Material.SAND);
                    b.fill(x, G - 5, z, x, G - 1, z, Material.WATER);
                    continue;
                }
                b.set(x, G - 1, z, Material.DIRT);
                boolean outside = x < 0 || z < 0 || z >= S;
                Material top;
                if (outside) {
                    top = b.chance(0.85) ? Material.GRASS_BLOCK : Material.COARSE_DIRT;
                } else {
                    double n = Math.sin(x * 0.11) + Math.cos(z * 0.09) + Math.sin((x + z) * 0.05);
                    top = n > 1.3 ? Material.GRASS_BLOCK : yard[b.random().nextInt(yard.length)];
                    if (x < 60 && z > 60 && z < 140 && b.chance(0.55)) {
                        top = Material.GRASS_BLOCK;
                    }
                }
                b.set(x, G, z, top);
                if (top == Material.GRASS_BLOCK && b.chance(0.12)) {
                    b.set(x, G + 1, z, b.chance(0.8) ? Material.SHORT_GRASS : Material.FERN);
                }
            }
        }
        // Trees outside the fence and in the west grove.
        for (int i = 0; i < 70; i++) {
            int x = b.between(-18, S + 4);
            int z = b.between(-18, S + 18);
            boolean outside = x < -2 || z < -2 || z > S + 1;
            boolean grove = x > 4 && x < 54 && z > 106 && z < 140;
            if ((outside || grove) && x < SEA_X - 3) {
                tree(x, z);
            }
        }
    }

    private void tree(int x, int z) {
        int h = b.between(4, 6);
        for (int y = 1; y <= h; y++) {
            b.set(x, G + y, z, Material.SPRUCE_LOG);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = h - 2; dy <= h + 1; dy++) {
                    int r = dy > h ? 1 : 2;
                    if (Math.abs(dx) <= r && Math.abs(dz) <= r && !(dx == 0 && dz == 0 && dy <= h) && b.chance(0.85)) {
                        b.set(x + dx, G + dy, z + dz, "spruce_leaves[persistent=true]");
                    }
                }
            }
        }
    }

    private void roads() {
        // East-west main road and north-south service road.
        for (int x = 0; x < SEA_X; x++) {
            for (int z = 70; z <= 76; z++) {
                b.set(x, G, z, z == 73 && x % 4 < 2 ? Material.YELLOW_CONCRETE : Material.GRAY_CONCRETE);
            }
        }
        for (int z = 0; z < S; z++) {
            for (int x = 62; x <= 68; x++) {
                b.set(x, G, z, x == 65 && z % 4 < 2 ? Material.YELLOW_CONCRETE : Material.GRAY_CONCRETE);
            }
        }
        for (int x = 68; x < SEA_X; x++) {
            for (int z = 116; z <= 121; z++) {
                b.set(x, G, z, Material.GRAY_CONCRETE);
            }
        }
        for (int z = 0; z <= 18; z++) {
            for (int x = 84; x <= 94; x++) {
                b.set(x, G, z, Material.GRAY_CONCRETE);
            }
        }
        // Street lamps.
        for (int x = 8; x < SEA_X; x += 18) {
            lamp(x, 69);
            lamp(x + 9, 77);
        }
        for (int z = 10; z < S; z += 20) {
            lamp(61, z);
        }
    }

    private void lamp(int x, int z) {
        if (b.type(x, G + 1, z) != Material.AIR) {
            return;
        }
        for (int y = 1; y <= 4; y++) {
            b.set(x, G + y, z, Material.IRON_BARS);
        }
        b.set(x, G + 5, z, Material.SMOOTH_STONE_SLAB);
        b.set(x, G + 4, z, "lantern[hanging=true]");
        b.set(x, G + 3, z, Material.IRON_BARS);
    }

    private void perimeter() {
        for (int i = 0; i < S; i++) {
            fence(i, 0);
            fence(i, S - 1);
            fence(0, i);
        }
        // North gate opening (the extraction lane) and west rail gap.
        b.clear(85, G + 1, 0, 93, G + 6, 0);
        b.clear(0, G + 1, 117, 0, G + 6, 123);
        b.clear(176, G + 1, S - 1, 184, G + 6, S - 1);
        // Invisible walls so nobody leaves the compound.
        for (int i = -1; i <= S; i++) {
            for (int y = G + 1; y <= G + 40; y++) {
                b.set(i, y, -1, Material.BARRIER);
                b.set(i, y, S, Material.BARRIER);
                b.set(-1, y, i, Material.BARRIER);
                b.set(S, y, i, Material.BARRIER);
            }
        }
        // Rail line past the west gap.
        for (int z = 100; z < 140; z++) {
            b.set(-4, G, z, Material.GRAVEL);
            b.set(-5, G + 1, z, "rail[shape=north_south]");
            b.set(-5, G, z, Material.GRAVEL);
        }
    }

    private void fence(int x, int z) {
        if (x >= SEA_X) {
            return;
        }
        boolean post = (x + z) % 6 == 0;
        b.set(x, G + 1, z, post ? Material.STONE_BRICKS : Material.CRACKED_STONE_BRICKS);
        b.set(x, G + 2, z, post ? Material.STONE_BRICKS : Material.STONE_BRICK_WALL);
        b.set(x, G + 3, z, Material.IRON_BARS);
        b.set(x, G + 4, z, post ? Material.IRON_BARS : Material.AIR);
    }

    // ------------------------------------------------------------------ buildings

    /**
     * Generic multi-storey building with a corridor or a row of rooms, exterior doors, windows,
     * stairs between floors and containers placed along room walls.
     */
    private void building(String name, int x0, int z0, int w, int d, int floors, Palette p, String[] loot, int tier,
                          boolean corridor, String lockedRoom) {
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        int top = G + floors * 5;
        b.clear(x0, G + 1, z0, x1, top + 2, z1);
        for (int f = 0; f <= floors; f++) {
            int y = G + f * 5;
            b.fill(x0, y, z0, x1, y, z1, f == floors ? p.roof() : p.floor());
        }
        for (int f = 0; f < floors; f++) {
            int y = G + f * 5;
            b.walls(x0, z0, x1, z1, y + 1, y + 4, p.wall());
            for (int yy = y + 1; yy <= y + 4; yy++) {
                b.set(x0, yy, z0, p.pillar());
                b.set(x1, yy, z0, p.pillar());
                b.set(x0, yy, z1, p.pillar());
                b.set(x1, yy, z1, p.pillar());
            }
            b.fill(x0, y + 4, z0, x1, y + 4, z0, p.trim());
            b.fill(x0, y + 4, z1, x1, y + 4, z1, p.trim());
            for (int x = x0 + 2; x < x1 - 1; x += 3) {
                b.pane(x, y + 2, z0, p.glass() + "[east=true,west=true]");
                b.pane(x, y + 3, z0, p.glass() + "[east=true,west=true]");
                b.pane(x, y + 2, z1, p.glass() + "[east=true,west=true]");
                b.pane(x, y + 3, z1, p.glass() + "[east=true,west=true]");
            }
            for (int z = z0 + 2; z < z1 - 1; z += 3) {
                b.pane(x0, y + 2, z, p.glass() + "[north=true,south=true]");
                b.pane(x0, y + 3, z, p.glass() + "[north=true,south=true]");
                b.pane(x1, y + 2, z, p.glass() + "[north=true,south=true]");
                b.pane(x1, y + 3, z, p.glass() + "[north=true,south=true]");
            }
            interior(name, x0, z0, x1, z1, y, f, floors, p, loot, tier, corridor, f == floors - 1 ? lockedRoom : null);
        }
        // Parapet.
        b.walls(x0, z0, x1, z1, top + 1, top + 1, p.pillar());
        // Exterior doors on the ground floor.
        int mx = x0 + w / 2;
        int mz = z0 + d / 2;
        b.clear(mx, G + 1, z1, mx, G + 2, z1);
        b.door(mx, G + 1, z1, BlockFace.NORTH, p.wood());
        b.clear(mx, G + 1, z0, mx, G + 2, z0);
        b.door(mx, G + 1, z0, BlockFace.SOUTH, p.wood());
        b.clear(x1, G + 1, mz, x1, G + 2, mz);
        b.door(x1, G + 1, mz, BlockFace.WEST, p.wood());
        b.set(mx, G + 3, z1 + 1, "lantern[hanging=false]");
        b.set(mx, G + 3, z1, p.trim());
        bp.ai(mx, G + 1, z1 + 3, 1, 8, "guard");
    }

    private void interior(String name, int x0, int z0, int x1, int z1, int y, int floor, int floors, Palette p,
                          String[] loot, int tier, boolean corridor, String lockedRoom) {
        int d = z1 - z0 + 1;
        boolean hasCorridor = corridor && d >= 12;
        int zc = z0 + d / 2;
        // Stairs hall at the west end.
        int stairZ = hasCorridor ? zc : z0 + 1;
        if (floor < floors - 1 || name.equals("hq")) {
            for (int i = 0; i < 5; i++) {
                int sx = x0 + 1 + i;
                for (int dz = 0; dz < 2; dz++) {
                    b.set(sx, y + 1 + i, stairZ + dz, "stone_brick_stairs[facing=east]");
                    for (int fill = y + 1; fill < y + 1 + i; fill++) {
                        b.set(sx, fill, stairZ + dz, Material.STONE_BRICKS);
                    }
                }
            }
            b.clear(x0 + 1, y + 5, stairZ, x0 + 4, y + 5, stairZ + 1);
        }
        int roomStart = x0 + 7;
        b.fill(roomStart, y + 1, z0 + 1, roomStart, y + 4, z1 - 1, p.inner());
        b.clear(roomStart, y + 1, hasCorridor ? zc : z0 + 1, roomStart, y + 2, hasCorridor ? zc + 1 : z0 + 2);
        List<int[]> rooms = new ArrayList<>();
        if (hasCorridor) {
            b.fill(roomStart, y + 1, zc - 2, x1 - 1, y + 4, zc - 2, p.inner());
            b.fill(roomStart, y + 1, zc + 2, x1 - 1, y + 4, zc + 2, p.inner());
            int x = roomStart;
            while (x < x1 - 4) {
                int rw = Math.min(x1 - 1 - x, b.between(6, 8));
                int xe = x + rw;
                if (xe < x1 - 1) {
                    b.fill(xe, y + 1, z0 + 1, xe, y + 4, zc - 2, p.inner());
                    b.fill(xe, y + 1, zc + 2, xe, y + 4, z1 - 1, p.inner());
                }
                rooms.add(new int[]{x + 1, z0 + 1, xe - 1, zc - 3, x + rw / 2, zc - 2});
                rooms.add(new int[]{x + 1, zc + 3, xe - 1, z1 - 1, x + rw / 2, zc + 2});
                x = xe;
            }
            for (int lx = roomStart + 2; lx < x1; lx += 5) {
                b.set(lx, y + 4, zc, "lantern[hanging=true]");
            }
            bp.ai((x0 + x1) / 2.0, y + 1, zc, 1, 6, "patrol");
        } else {
            int x = roomStart;
            while (x < x1 - 4) {
                int rw = Math.min(x1 - 1 - x, b.between(6, 9));
                int xe = x + rw;
                if (xe < x1 - 1) {
                    b.fill(xe, y + 1, z0 + 1, xe, y + 4, z1 - 1, p.inner());
                }
                rooms.add(new int[]{x + 1, z0 + 1, xe - 1, z1 - 1, xe, b.between(z0 + 2, z1 - 2)});
                x = xe;
            }
            bp.ai((x0 + x1) / 2.0, y + 1, (z0 + z1) / 2.0, 1, 5, "patrol");
        }
        int lockIndex = lockedRoom == null ? -1 : rooms.size() - 1;
        for (int r = 0; r < rooms.size(); r++) {
            int[] room = rooms.get(r);
            int doorX = room[4];
            int doorZ = room[5];
            boolean locked = r == lockIndex;
            if (hasCorridor) {
                b.clear(doorX, y + 1, doorZ, doorX, y + 2, doorZ);
                if (locked) {
                    b.set(doorX, y + 1, doorZ, "iron_door[facing=" + (doorZ < zc ? "south" : "north") + ",half=lower]");
                    b.set(doorX, y + 2, doorZ, "iron_door[facing=" + (doorZ < zc ? "south" : "north") + ",half=upper]");
                    bp.doors.add(new MapDef.DoorDef(lockedRoom, new Pos(doorX + 0.5, y + 1, doorZ + 0.5, 0)));
                } else if (b.chance(0.6)) {
                    b.door(doorX, y + 1, doorZ, doorZ < zc ? BlockFace.SOUTH : BlockFace.NORTH, p.wood());
                }
            } else if (doorX < x1 - 1) {
                b.clear(doorX, y + 1, doorZ, doorX, y + 2, doorZ);
                if (locked) {
                    b.set(doorX, y + 1, doorZ, "iron_door[facing=east,half=lower]");
                    b.set(doorX, y + 2, doorZ, "iron_door[facing=east,half=upper]");
                    bp.doors.add(new MapDef.DoorDef(lockedRoom, new Pos(doorX + 0.5, y + 1, doorZ + 0.5, 0)));
                }
            }
            furnish(name, room, y, p);
            int count = locked ? 3 : b.between(1, 2);
            int roomTier = locked ? Math.min(2, tier + 2) : tier;
            placeContainers(room, y, loot, count, roomTier, locked);
            b.set((room[0] + room[2]) / 2, y + 4, (room[1] + room[3]) / 2, "lantern[hanging=true]");
        }
    }

    private void furnish(String name, int[] room, int y, Palette p) {
        int xa = room[0];
        int za = room[1];
        int xb = room[2];
        int zb = room[3];
        if (xb - xa < 2 || zb - za < 2) {
            return;
        }
        switch (name) {
            case "dorm" -> {
                b.set(xb, y + 1, za, "red_bed[facing=north,part=head]");
                b.set(xb, y + 1, za + 1, "red_bed[facing=north,part=foot]");
                if (xb - 2 > xa) {
                    b.set(xb - 2, y + 1, za, "gray_bed[facing=north,part=head]");
                    b.set(xb - 2, y + 1, za + 1, "gray_bed[facing=north,part=foot]");
                }
            }
            case "hq", "checkpoint" -> {
                int tx = (xa + xb) / 2;
                int tz = (za + zb) / 2;
                b.set(tx, y + 1, tz, Material.DARK_OAK_FENCE);
                b.set(tx, y + 2, tz, Material.DARK_OAK_PRESSURE_PLATE);
                b.set(tx + 1, y + 1, tz, "dark_oak_stairs[facing=west]");
            }
            case "clinic" -> {
                b.set(xb, y + 1, zb, "white_bed[facing=south,part=head]");
                b.set(xb, y + 1, zb - 1, "white_bed[facing=south,part=foot]");
                b.set(xa, y + 1, zb, Material.BREWING_STAND);
            }
            default -> b.set(xb, y + 1, zb, Material.BARREL);
        }
        if (b.chance(0.5)) {
            b.set(xa, y + 1, zb, b.chance(0.5) ? Material.BOOKSHELF : Material.LOOM);
        }
    }

    private void placeContainers(int[] room, int y, String[] loot, int count, int tier, boolean vault) {
        int xa = room[0];
        int za = room[1];
        int xb = room[2];
        int zb = room[3];
        int[][] spots = {{xa, za}, {xb, zb - 1}, {xa, (za + zb) / 2}, {(xa + xb) / 2, za}};
        for (int i = 0; i < count && i < spots.length; i++) {
            int sx = spots[i][0];
            int sz = spots[i][1];
            if (b.type(sx, y + 1, sz) != Material.AIR) {
                continue;
            }
            String type = vault ? (i == 0 ? "vault" : i == 1 ? "computer" : "safe") : b.pick(loot);
            bp.container(type, sx, y + 1, sz, b.between(0, 3) * 90f, tier);
        }
    }

    private void hq() {
        int x0 = 78;
        int z0 = 86;
        int w = 32;
        int d = 24;
        building("hq", x0, z0, w, d, 3, HQ, new String[]{"filing_cabinet", "computer", "locker", "weapon_case", "safe", "crate"},
                1, true, "hq_server");
        int roof = G + 15;
        // Helipad markings on the roof.
        int cx = x0 + w / 2;
        int cz = z0 + d / 2;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                boolean ring = Math.abs(dx) == 4 || Math.abs(dz) == 4;
                boolean letterH = (Math.abs(dx) == 2 && Math.abs(dz) <= 2) || (dz == 0 && Math.abs(dx) <= 2);
                b.set(cx + dx, roof, cz + dz, ring || letterH ? Material.YELLOW_CONCRETE : Material.GRAY_CONCRETE);
            }
        }
        b.set(x0 + 1, roof + 1, z0 + 1, Material.REDSTONE_LAMP);
        b.set(x0 + w - 2, roof + 1, z0 + d - 2, Material.SEA_LANTERN);
        bp.bosses.add(new MapDef.BossDef("commander", "铁幕指挥官", new Pos(cx + 0.5, G + 11, cz + 0.5, 0), 3, 1.0, 90));
        bp.ai(cx, G + 6, cz, 2, 8, "guard");
        bp.ai(cx, roof + 1, cz, 1, 5, "sniper");
        // Sandbag nest by the front entrance.
        for (int x = cx - 5; x <= cx + 5; x++) {
            if (Math.abs(x - cx) > 1) {
                b.set(x, G + 1, z0 + d + 3, Material.PACKED_MUD);
                b.set(x, G + 2, z0 + d + 3, Material.MUD_BRICK_SLAB);
            }
        }
    }

    private void warehouse(int x0, int z0, int w, int d, String lock, int tier) {
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        int h = 9;
        b.clear(x0, G + 1, z0, x1, G + h + 2, z1);
        b.fill(x0, G, z0, x1, G, z1, Material.SMOOTH_STONE);
        b.walls(x0, z0, x1, z1, G + 1, G + h, Material.LIGHT_GRAY_TERRACOTTA);
        for (int x = x0; x <= x1; x += 6) {
            b.fill(x, G + 1, z0, x, G + h, z0, Material.GRAY_CONCRETE);
            b.fill(x, G + 1, z1, x, G + h, z1, Material.GRAY_CONCRETE);
        }
        b.fill(x0, G + h + 1, z0, x1, G + h + 1, z1, Material.WEATHERED_CUT_COPPER);
        for (int x = x0 + 4; x < x1 - 2; x += 8) {
            b.fill(x, G + h + 1, z0 + 3, x + 1, G + h + 1, z1 - 3, Material.GLASS);
        }
        // Roll-up door openings.
        int mx = x0 + w / 2;
        b.clear(mx - 2, G + 1, z1, mx + 2, G + 4, z1);
        b.clear(mx - 2, G + 1, z0, mx + 2, G + 4, z0);
        b.clear(x0, G + 1, z0 + d / 2, x0, G + 2, z0 + d / 2);
        b.door(x0, G + 1, z0 + d / 2, BlockFace.EAST, "spruce");
        // Shelving rows.
        for (int z = z0 + 4; z <= z1 - 4; z += 4) {
            for (int x = x0 + 4; x <= x1 - 12; x++) {
                if (x == mx || x == mx - 1 || x == mx + 1) {
                    continue;
                }
                boolean post = (x - x0) % 4 == 0;
                b.set(x, G + 1, z, post ? Material.STRIPPED_SPRUCE_LOG : Material.BARREL);
                b.set(x, G + 2, z, post ? Material.STRIPPED_SPRUCE_LOG : Material.SPRUCE_SLAB);
                b.set(x, G + 3, z, post ? Material.STRIPPED_SPRUCE_LOG : (b.chance(0.4) ? Material.BARREL : Material.AIR));
                b.set(x, G + 4, z, Material.SPRUCE_SLAB);
            }
            bp.container(b.pick(new String[]{"crate", "ammo_box", "parcel", "toolbox"}), x0 + 3, G + 1, z, 90f, tier);
            if (b.chance(0.6)) {
                bp.container(b.pick(new String[]{"crate", "weapon_case", "parcel"}), mx + 3, G + 1, z + 1, 0f, tier);
            }
        }
        for (int x = x0 + 4; x < x1; x += 7) {
            b.set(x, G + h, z0 + d / 2, "lantern[hanging=true]");
        }
        // Office in the east end with a mezzanine catwalk.
        int ox = x1 - 9;
        b.fill(ox, G + 1, z0 + 1, ox, G + 4, z1 - 1, Material.WHITE_CONCRETE);
        b.fill(ox, G + 5, z0 + 1, x1 - 1, G + 5, z1 - 1, Material.SPRUCE_PLANKS);
        for (int x = ox + 2; x < x1 - 1; x += 3) {
            b.pane(ox, G + 3, z0 + 3, "glass_pane[north=true,south=true]");
        }
        int dz = z0 + d / 2;
        b.clear(ox, G + 1, dz, ox, G + 2, dz);
        if (lock != null) {
            b.set(ox, G + 1, dz, "iron_door[facing=west,half=lower]");
            b.set(ox, G + 2, dz, "iron_door[facing=west,half=upper]");
            bp.doors.add(new MapDef.DoorDef(lock, new Pos(ox + 0.5, G + 1, dz + 0.5, 0)));
        } else {
            b.door(ox, G + 1, dz, BlockFace.WEST, "spruce");
        }
        bp.container(lock != null ? "safe" : "filing_cabinet", ox + 2, G + 1, z0 + 2, 0f, tier + 1);
        bp.container("computer", x1 - 2, G + 1, z0 + 2, 0f, tier + 1);
        bp.container(lock != null ? "weapon_case" : "locker", x1 - 2, G + 1, z1 - 2, 180f, tier + 1);
        for (int i = 0; i < 5; i++) {
            b.set(ox - 5 + i, G + 1 + i, z1 - 2, "spruce_stairs[facing=east]");
        }
        b.clear(ox - 1, G + 5, z1 - 2, ox, G + 5, z1 - 2);
        bp.container("ammo_box", ox + 4, G + 6, z1 - 3, 90f, tier);
        bp.ai(x0 + w / 2.0, G + 1, z0 + d / 2.0, 2, 10, "patrol");
        bp.ai(ox + 4, G + 6, z0 + d / 2.0, 1, 3, "guard");
    }

    private void garageHall(int x0, int z0, int w, int d) {
        int x1 = x0 + w - 1;
        int z1 = z0 + d - 1;
        b.clear(x0, G + 1, z0, x1, G + 8, z1);
        b.fill(x0, G, z0, x1, G, z1, Material.GRAY_CONCRETE);
        b.walls(x0, z0, x1, z1, G + 1, G + 6, Material.CUT_COPPER);
        b.fill(x0, G + 7, z0, x1, G + 7, z1, Material.WEATHERED_CUT_COPPER);
        for (int i = 0; i < 3; i++) {
            int bx = x0 + 3 + i * 6;
            b.clear(bx, G + 1, z1, bx + 3, G + 4, z1);
            car(bx, z1 - 6, i % 2 == 0 ? Material.RED_CONCRETE : Material.BLUE_CONCRETE);
        }
        b.clear(x0, G + 1, z0 + d / 2, x0, G + 2, z0 + d / 2);
        for (int x = x0 + 2; x < x1; x += 5) {
            b.set(x, G + 6, z0 + d / 2, "lantern[hanging=true]");
        }
        bp.container("toolbox", x0 + 1, G + 1, z0 + 1, 0f, 0);
        bp.container("toolbox", x1 - 1, G + 1, z0 + 1, 0f, 0);
        bp.container("ammo_box", x1 - 1, G + 1, z0 + d / 2, 270f, 0);
        bp.ai(x0 + w / 2.0, G + 1, z0 + 3, 2, 8, "patrol");
    }

    private void car(int x, int z, Material paint) {
        b.fill(x, G + 1, z, x + 1, G + 1, z + 3, paint);
        b.fill(x, G + 2, z + 1, x + 1, G + 2, z + 2, "glass");
        b.set(x, G + 2, z, paint);
        b.set(x + 1, G + 2, z, paint);
        b.set(x, G + 2, z + 3, paint);
        b.set(x + 1, G + 2, z + 3, paint);
    }

    private void containerYard(int x0, int z0, int w, int d) {
        Material[] paints = {Material.ORANGE_CONCRETE, Material.RED_TERRACOTTA, Material.BLUE_CONCRETE, Material.GREEN_TERRACOTTA,
                Material.WHITE_CONCRETE, Material.CYAN_TERRACOTTA, Material.BROWN_CONCRETE};
        for (int z = z0; z + 3 <= z0 + d; z += 7) {
            for (int x = x0; x + 7 <= x0 + w; x += 10) {
                if (b.chance(0.12)) {
                    continue;
                }
                Material paint = b.pick(paints);
                boolean open = b.chance(0.45);
                shippingContainer(x, G + 1, z, paint, open);
                if (open) {
                    bp.container(b.pick(new String[]{"crate", "weapon_case", "parcel", "ammo_box", "med_box"}), x + 4, G + 1, z + 1, 90f,
                            b.chance(0.25) ? 1 : 0);
                }
                if (b.chance(0.4)) {
                    shippingContainer(x, G + 4, z, b.pick(paints), false);
                    if (b.chance(0.4)) {
                        b.set(x - 1, G + 1, z + 1, "ladder[facing=west]");
                        b.set(x - 1, G + 2, z + 1, "ladder[facing=west]");
                        b.set(x - 1, G + 3, z + 1, "ladder[facing=west]");
                        b.set(x - 1, G + 4, z + 1, "ladder[facing=west]");
                        b.set(x - 1, G + 5, z + 1, "ladder[facing=west]");
                        b.set(x - 1, G + 6, z + 1, "ladder[facing=west]");
                    }
                }
            }
            if (z % 14 == 0) {
                bp.ai(x0 + w / 2.0, G + 1, z + 4.5, 1, 12, "patrol");
            }
        }
        // Gantry crane over the yard.
        int cx = x0 + w / 2;
        for (int y = G + 1; y <= G + 14; y++) {
            b.set(cx - 12, y, z0 - 2, Material.YELLOW_CONCRETE);
            b.set(cx + 12, y, z0 - 2, Material.YELLOW_CONCRETE);
            b.set(cx - 12, y, z0 + d, Material.YELLOW_CONCRETE);
            b.set(cx + 12, y, z0 + d, Material.YELLOW_CONCRETE);
        }
        b.fill(cx - 12, G + 15, z0 - 2, cx - 12, G + 15, z0 + d, Material.YELLOW_CONCRETE);
        b.fill(cx + 12, G + 15, z0 - 2, cx + 12, G + 15, z0 + d, Material.YELLOW_CONCRETE);
        b.fill(cx - 12, G + 15, z0 + d / 2, cx + 12, G + 15, z0 + d / 2, Material.ORANGE_CONCRETE);
        bp.ai(cx, G + 16, z0 + d / 2.0, 1, 2, "sniper");
    }

    private void shippingContainer(int x, int y, int z, Material paint, boolean open) {
        b.fill(x, y, z, x + 6, y + 2, z + 2, paint);
        b.clear(x + 1, y, z + 1, x + 5, y + 1, z + 1);
        if (open) {
            b.clear(x + 6, y, z + 1, x + 6, y + 1, z + 1);
            b.set(x + 6, y, z + 2, Material.IRON_BARS);
        } else {
            b.fill(x + 6, y, z + 1, x + 6, y + 1, z + 1, Material.IRON_BARS);
        }
    }

    private void docks() {
        int[][] piers = {{58, 66}, {118, 128}, {160, 168}};
        for (int[] pier : piers) {
            for (int x = SEA_X - 2; x < S + 6; x++) {
                for (int z = pier[0]; z <= pier[1]; z++) {
                    b.set(x, G, z, Material.SPRUCE_PLANKS);
                    if (x % 5 == 0 && (z == pier[0] || z == pier[1])) {
                        b.set(x, G + 1, z, Material.SPRUCE_FENCE);
                        for (int y = G - 5; y < G; y++) {
                            b.set(x, y, z, Material.SPRUCE_LOG);
                        }
                    }
                }
            }
        }
        // Quay wall along the shore.
        for (int z = 0; z < S; z++) {
            b.set(SEA_X - 1, G, z, Material.STONE_BRICKS);
            b.set(SEA_X - 1, G - 1, z, Material.STONE_BRICKS);
            if (z % 9 == 0) {
                b.set(SEA_X - 1, G + 1, z, Material.POLISHED_BLACKSTONE_WALL);
            }
        }
        // Escape boat at the middle pier.
        int bx = S + 1;
        int bz = 129;
        b.fill(bx - 4, G - 1, bz, bx + 4, G - 1, bz + 3, Material.DARK_OAK_PLANKS);
        b.fill(bx - 4, G, bz, bx + 4, G, bz, Material.DARK_OAK_PLANKS);
        b.fill(bx - 4, G, bz + 3, bx + 4, G, bz + 3, Material.DARK_OAK_PLANKS);
        b.set(bx + 2, G + 1, bz + 1, Material.WHITE_CONCRETE);
        b.set(bx + 2, G + 2, bz + 1, Material.WHITE_STAINED_GLASS);
        // Dockside cargo and a small harbour office.
        bp.container("crate", SEA_X - 4, G + 1, 60, 0f, 0);
        bp.container("parcel", SEA_X - 4, G + 1, 124, 0f, 0);
        bp.container("ammo_box", SEA_X - 4, G + 1, 164, 0f, 0);
        bp.ai(SEA_X - 6, G + 1, 100, 2, 14, "patrol");
        bp.ai(SEA_X - 6, G + 1, 150, 1, 14, "patrol");
    }

    private void radioTower(int x0, int z0) {
        b.fill(x0 - 3, G, z0 - 3, x0 + 3, G, z0 + 3, Material.SMOOTH_STONE);
        int topY = G + 22;
        for (int y = G + 1; y < topY; y++) {
            b.set(x0 - 2, y, z0 - 2, Material.IRON_BARS);
            b.set(x0 + 2, y, z0 - 2, Material.IRON_BARS);
            b.set(x0 - 2, y, z0 + 2, Material.IRON_BARS);
            b.set(x0 + 2, y, z0 + 2, Material.IRON_BARS);
            b.set(x0, y, z0, Material.POLISHED_ANDESITE);
            b.set(x0, y, z0 + 1, "ladder[facing=south]");
            if ((y - G) % 6 == 0) {
                b.walls(x0 - 2, z0 - 2, x0 + 2, z0 + 2, y, y, Material.IRON_BARS);
            }
        }
        b.fill(x0 - 3, topY, z0 - 3, x0 + 3, topY, z0 + 3, Material.SMOOTH_STONE_SLAB);
        b.clear(x0, topY, z0 + 1, x0, topY, z0 + 1);
        b.walls(x0 - 3, z0 - 3, x0 + 3, z0 + 3, topY + 1, topY + 1, Material.IRON_BARS);
        b.set(x0, topY, z0, Material.POLISHED_ANDESITE);
        b.set(x0 + 2, topY + 1, z0 - 2, Material.STONE_BRICKS);
        b.set(x0 + 2, topY + 2, z0 - 2, "lever[face=floor,facing=north,powered=false]");
        for (int y = topY + 1; y < topY + 8; y++) {
            b.set(x0, y, z0, Material.IRON_BARS);
        }
        b.set(x0, topY + 8, z0, Material.REDSTONE_LAMP);
        bp.switches.add(new MapDef.SwitchDef("radio", "通讯塔电闸", new Pos(x0 + 2.5, topY + 2, z0 - 1.5, 0), 360));
        bp.container("ammo_box", x0 - 2, topY + 1, z0 + 2, 0f, 1);
        bp.ai(x0, G + 1, z0 + 5, 2, 8, "guard");
    }

    // ------------------------------------------------------------------ props & markers

    private void props() {
        int placedCars = 0;
        while (placedCars < 10) {
            int x = b.between(70, 180);
            int z = b.between(66, 80);
            if (b.type(x, G + 1, z) == Material.AIR && b.type(x + 1, G + 1, z + 3) == Material.AIR) {
                car(x, z, b.pick(new Material[]{Material.WHITE_CONCRETE, Material.GREEN_CONCRETE, Material.BLACK_CONCRETE,
                        Material.LIGHT_GRAY_CONCRETE}));
            }
            placedCars++;
        }
        // Concrete barriers and sandbag cover around the compound.
        for (int i = 0; i < 60; i++) {
            int x = b.between(4, SEA_X - 6);
            int z = b.between(4, S - 6);
            if (b.type(x, G + 1, z) != Material.AIR || b.type(x, G + 3, z) != Material.AIR) {
                continue;
            }
            boolean alongX = b.chance(0.5);
            int len = b.between(2, 4);
            for (int k = 0; k < len; k++) {
                int px = alongX ? x + k : x;
                int pz = alongX ? z : z + k;
                if (b.type(px, G + 1, pz) != Material.AIR) {
                    break;
                }
                if (i % 2 == 0) {
                    b.set(px, G + 1, pz, Material.SMOOTH_STONE);
                    b.set(px, G + 2, pz, Material.SMOOTH_STONE_SLAB);
                } else {
                    b.set(px, G + 1, pz, Material.PACKED_MUD);
                    b.set(px, G + 2, pz, Material.MUD_BRICK_SLAB);
                }
            }
        }
        // Loose supply crates in the open for early fights.
        int[][] crates = {{60, 40}, {100, 60}, {40, 70}, {110, 136}, {90, 180}, {150, 110}, {30, 120}, {170, 30}};
        for (int[] c : crates) {
            bp.container(b.chance(0.5) ? "crate" : "parcel", c[0], G + 1, c[1], 0f, 0);
        }
        bp.container("airdrop", 100, G + 1, 150, 0f, 2);
        bp.ai(100, G + 1, 150, 2, 6, "guard");
    }

    private void markers() {
        int y = G + 1;
        int[][] groups = {{8, 8, 135}, {176, 10, 45}, {8, 56, 225}, {176, 96, 90}, {30, 196, 315}, {106, 198, 0}};
        for (int g = 0; g < groups.length; g++) {
            int[] s = groups[g];
            for (int k = 0; k < 3; k++) {
                bp.spawn(g + 1, s[0] + k * 2, y, s[1] + (k % 2), s[2]);
            }
        }
        bp.extract("north_gate", "北门检查站", 89, y, 3, 4, ExtractType.ALWAYS, 8, 0, 0, 0, "", 0, 1.0);
        bp.extract("dock_boat", "码头快艇", 203, y, 126, 3.5, ExtractType.PAID, 8, 12_000, 0, 0, "", 0, 1.0);
        bp.extract("drain", "西南排水渠", 6, y, 200, 3.5, ExtractType.SWITCH, 8, 0, 0, 0, "radio", 0, 1.0);
        bp.extract("rooftop", "指挥中心天台", 94, G + 16, 98, 3, ExtractType.LIMITED, 10, 0, 600, 1320, "", 4, 1.0);
        bp.extract("rail", "西侧铁道", 2, y, 120, 3, ExtractType.NO_BACKPACK, 8, 0, 0, 0, "", 0, 1.0);
        bp.extract("cargo_gate", "东南货运口", 180, y, 204, 3.5, ExtractType.ALWAYS, 8, 0, 0, 0, "", 0, 0.5);
    }
}
