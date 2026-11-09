package net.infnetwork.snowball.craftov.map.gen;

import net.infnetwork.snowball.craftov.map.ExtractType;
import net.infnetwork.snowball.craftov.map.MapDef;
import net.infnetwork.snowball.craftov.map.Pos;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;

import java.util.List;

/**
 * Builds "试验场 / Proving Ground": a compact, labelled test map that puts every gameplay
 * feature within a short walk. South to north: spawn bays, an armory with one container per
 * item category, a gallery of every container type, key-locked rooms, a glass house, a
 * two-storey drill building, fall towers, an extract strip with all six extract types and a
 * walled AI arena with a sniper tower and a boss.
 */
public final class ProvingGroundGenerator implements MapGenerator {

    public static final String ID = "proving";
    static final int W = 120;
    static final int D = 104;
    static final int G = 64;

    private static final TextColor TITLE = TextColor.color(0x8A4B00);
    private static final TextColor BODY = TextColor.color(0x1E1E1E);

    private final Builder b;
    private final World world;
    private final Blueprint bp = new Blueprint();

    public ProvingGroundGenerator(World world) {
        this.world = world;
        this.b = new Builder(world, 0x7E57L);
    }

    @Override
    public MapDef generate() {
        ground();
        perimeter();
        spawnBays();
        armory();
        gallery();
        keyRooms();
        glassHouse();
        drillBuilding();
        fallTowers();
        extractStrip();
        arena();
        return new MapDef(ID, "试验场", "功能测试地图：军械库、全部物资箱、门禁、玻璃、坠落台、六种撤离点和敌兵区都在一张图里。",
                "craftov_tpl_" + ID, 30 * 60, new Pos(60, 112, 70, 0),
                List.copyOf(bp.spawns), List.copyOf(bp.extracts), List.copyOf(bp.containers), List.copyOf(bp.ai),
                List.copyOf(bp.bosses), List.copyOf(bp.doors), List.copyOf(bp.switches), 0, 0, W - 1, D - 1, "day");
    }

    @Override
    public long blocksPlaced() {
        return b.placed();
    }

    // ------------------------------------------------------------------ terrain

    private void ground() {
        for (int x = -8; x < W + 8; x++) {
            for (int z = -8; z < D + 8; z++) {
                b.set(x, G - 2, z, Material.STONE);
                b.set(x, G - 1, z, Material.DIRT);
                boolean inside = x > 0 && x < W - 1 && z > 0 && z < D - 1;
                Material top;
                if (!inside) {
                    top = Material.GRASS_BLOCK;
                } else if (x % 8 == 0 || z % 8 == 0) {
                    top = Material.GRAY_CONCRETE;
                } else {
                    top = (x + z) % 2 == 0 ? Material.POLISHED_ANDESITE : Material.ANDESITE;
                }
                b.set(x, G, z, top);
            }
        }
    }

    private void perimeter() {
        for (int y = G + 1; y <= G + 7; y++) {
            Material m = y == G + 7 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.STONE_BRICKS;
            for (int x = 0; x < W; x++) {
                b.set(x, y, 0, m);
                b.set(x, y, D - 1, m);
            }
            for (int z = 0; z < D; z++) {
                b.set(0, y, z, m);
                b.set(W - 1, y, z, m);
            }
        }
        for (int x = 4; x < W; x += 12) {
            b.set(x, G + 6, D - 2, Material.SEA_LANTERN);
        }
    }

    // ------------------------------------------------------------------ spawn & supply

    private void spawnBays() {
        b.fill(1, G, 84, W - 2, G, D - 2, Material.LIGHT_GRAY_CONCRETE);
        for (int x : new int[]{30, 60, 90}) {
            b.fill(x, G + 1, 87, x, G + 3, D - 2, Material.POLISHED_ANDESITE);
        }
        int[] centres = {15, 45, 75, 105};
        for (int g = 0; g < centres.length; g++) {
            int cx = centres[g];
            b.fill(cx - 3, G, 93, cx + 3, G, 98, Material.CYAN_CONCRETE);
            bp.spawn(g + 1, cx - 2, G + 1, 95, 180f);
            bp.spawn(g + 1, cx, G + 1, 96, 180f);
            bp.spawn(g + 1, cx + 2, G + 1, 95, 180f);
            sign(cx, G + 3, D - 2, BlockFace.NORTH, "出生区 " + (g + 1), "三排同队同区", "单排各自分开", "");
        }
    }

    private static final String[][] ARMORY = {
            {"test_weapons", "全部武器", "已装满弹匣"},
            {"test_ammo", "全部弹药", "每种一组"},
            {"test_armor", "头盔与护甲", "1-6 级"},
            {"test_carry", "胸挂与背包", "装备后解锁格子"},
            {"test_meds", "全部医疗品", "绷带到手术包"},
            {"test_throwables", "投掷物", "破片/烟雾/震撼"},
            {"test_keys", "全部钥匙", "开北侧门禁室"},
            {"test_valuables", "全部物资", "白到红"},
            {"test_collectibles", "全部收藏品", "撤离后登记"},
    };

    private void armory() {
        for (int i = 0; i < ARMORY.length; i++) {
            int x = 8 + i * 13;
            b.fill(x - 2, G + 1, 74, x + 2, G + 2, 74, Material.POLISHED_DEEPSLATE);
            b.fill(x - 2, G + 3, 74, x + 2, G + 3, 74, Material.POLISHED_DEEPSLATE_SLAB);
            b.fill(x - 1, G, 76, x + 1, G, 78, Material.YELLOW_CONCRETE);
            bp.container(ARMORY[i][0], x, G + 1, 77, 0f, 0);
            sign(x, G + 2, 75, BlockFace.SOUTH, "军械库", ARMORY[i][1], ARMORY[i][2], "");
        }
        sign(2, G + 2, 75, BlockFace.SOUTH, "测试军械库", "每局一份", "不够用就输入", "/cv items");
    }

    private static final String[][] GALLERY = {
            {"crate", "杂物箱"}, {"parcel", "快递箱"}, {"weapon_case", "武器箱"}, {"ammo_box", "弹药箱"},
            {"med_box", "医疗箱"}, {"toolbox", "工具箱"}, {"computer", "电脑机箱"}, {"filing_cabinet", "文件柜"},
            {"locker", "储物柜"}, {"safe", "保险箱"}, {"vault", "高级保险箱"}, {"airdrop", "空投补给箱"},
            {"body_bag", "遗落背包"},
    };

    private void gallery() {
        for (int i = 0; i < GALLERY.length; i++) {
            int x = 6 + i * 9;
            b.fill(x - 2, G + 1, 60, x + 2, G + 2, 60, Material.POLISHED_ANDESITE);
            b.fill(x - 2, G + 3, 60, x + 2, G + 3, 60, Material.POLISHED_ANDESITE_SLAB);
            b.fill(x - 1, G, 63, x + 1, G, 65, Material.WHITE_CONCRETE);
            bp.container(GALLERY[i][0], x, G + 1, 64, 0f, 2);
            sign(x, G + 2, 61, BlockFace.SOUTH, "物资箱", GALLERY[i][1], "高品质加成", "");
        }
    }

    // ------------------------------------------------------------------ middle zone

    private void keyRooms() {
        String[][] rooms = {
                {"dorm_201", "宿舍顶层", "房间钥匙"},
                {"wh_office", "一号仓库", "办公室钥匙"},
                {"hq_server", "指挥中心", "机房门禁卡"},
        };
        for (int i = 0; i < rooms.length; i++) {
            int x0 = 4 + i * 11;
            int x1 = x0 + 9;
            int z0 = 38;
            int z1 = 48;
            b.fill(x0, G, z0, x1, G, z1, Material.SMOOTH_STONE);
            b.walls(x0, z0, x1, z1, G + 1, G + 4, Material.STONE_BRICKS);
            b.fill(x0, G + 5, z0, x1, G + 5, z1, Material.SMOOTH_STONE_SLAB);
            b.set(x0 + 5, G + 5, z0 + 5, Material.SEA_LANTERN);
            int dx = x0 + 4;
            b.set(dx, G + 1, z1, "iron_door[facing=north,half=lower]");
            b.set(dx, G + 2, z1, "iron_door[facing=north,half=upper]");
            bp.doors.add(new MapDef.DoorDef(rooms[i][0], new Pos(dx + 0.5, G + 1, z1 + 0.5, 0)));
            sign(dx, G + 3, z1 + 1, BlockFace.SOUTH, "门禁室 " + (i + 1), "需要钥匙：", rooms[i][1], rooms[i][2]);
            bp.container("safe", x0 + 4, G + 1, z0 + 2, 0f, 2);
            bp.container("filing_cabinet", x0 + 2, G + 1, z0 + 2, 0f, 1);
        }
    }

    private void glassHouse() {
        int x0 = 41;
        int x1 = 56;
        int z0 = 38;
        int z1 = 52;
        b.fill(x0, G, z0, x1, G, z1, Material.QUARTZ_BLOCK);
        for (int y = G + 1; y <= G + 3; y++) {
            for (int x = x0 + 1; x < x1; x++) {
                b.set(x, y, z0, "glass_pane[east=true,west=true]");
                boolean gap = x >= 48 && x <= 49 && y <= G + 2;
                b.set(x, y, z1, gap ? Material.AIR : Material.TINTED_GLASS);
            }
            for (int z = z0 + 1; z < z1; z++) {
                b.set(x0, y, z, "glass_pane[north=true,south=true]");
                b.set(x1, y, z, "glass_pane[north=true,south=true]");
            }
            b.set(x0, y, z0, Material.QUARTZ_PILLAR);
            b.set(x1, y, z0, Material.QUARTZ_PILLAR);
            b.set(x0, y, z1, Material.QUARTZ_PILLAR);
            b.set(x1, y, z1, Material.QUARTZ_PILLAR);
        }
        b.fill(x0, G + 4, z0, x1, G + 4, z1, Material.GLASS);
        bp.container("crate", 48, G + 1, 44, 0f, 1);
        sign(47, G + 3, z1 + 1, BlockFace.SOUTH, "玻璃房", "普通玻璃能打碎", "南墙遮光玻璃", "可以挡子弹");
    }

    private void drillBuilding() {
        int x0 = 62;
        int x1 = 88;
        int z0 = 36;
        int z1 = 56;
        b.fill(x0, G, z0, x1, G, z1, Material.SPRUCE_PLANKS);
        b.walls(x0, z0, x1, z1, G + 1, G + 9, Material.BRICKS);
        b.fill(x0 + 1, G + 5, z0 + 1, x1 - 1, G + 5, z1 - 1, Material.SPRUCE_PLANKS);
        b.fill(x0, G + 10, z0, x1, G + 10, z1, Material.SMOOTH_STONE);
        b.walls(x0, z0, x1, z1, G + 11, G + 11, Material.STONE_BRICK_WALL);
        for (int floorY : new int[]{G + 2, G + 7}) {
            for (int x = x0 + 3; x < x1 - 2; x += 4) {
                b.set(x, floorY, z0, "glass_pane[east=true,west=true]");
                b.set(x, floorY + 1, z0, "glass_pane[east=true,west=true]");
                b.set(x, floorY, z1, "glass_pane[east=true,west=true]");
                b.set(x, floorY + 1, z1, "glass_pane[east=true,west=true]");
            }
            for (int z = z0 + 3; z < z1 - 2; z += 4) {
                b.set(x0, floorY, z, "glass_pane[north=true,south=true]");
                b.set(x0, floorY + 1, z, "glass_pane[north=true,south=true]");
                b.set(x1, floorY, z, "glass_pane[north=true,south=true]");
                b.set(x1, floorY + 1, z, "glass_pane[north=true,south=true]");
            }
        }
        b.door(75, G + 1, z1, BlockFace.NORTH, "spruce");
        b.door(x0, G + 1, 46, BlockFace.EAST, "spruce");
        // Staircase along the south wall up to the first floor.
        b.clear(x0 + 1, G + 5, z1 - 2, x0 + 6, G + 5, z1 - 1);
        for (int k = 0; k < 5; k++) {
            b.set(x0 + 2 + k, G + 1 + k, z1 - 2, "spruce_stairs[facing=east]");
            b.set(x0 + 2 + k, G + 1 + k, z1 - 1, "spruce_stairs[facing=east]");
            for (int y = G + 1; y < G + 1 + k; y++) {
                b.set(x0 + 2 + k, y, z1 - 2, Material.SPRUCE_PLANKS);
                b.set(x0 + 2 + k, y, z1 - 1, Material.SPRUCE_PLANKS);
            }
        }
        // Ladder from the first floor to the roof.
        for (int y = G + 6; y <= G + 10; y++) {
            b.set(x1 - 1, y, z0 + 1, "ladder[facing=west]");
        }
        for (int x = x0 + 4; x < x1; x += 8) {
            b.set(x, G + 5, (z0 + z1) / 2, Material.SEA_LANTERN);
            b.set(x, G + 10, (z0 + z1) / 2, Material.SEA_LANTERN);
        }
        bp.container("computer", 68, G + 1, z0 + 2, 0f, 1);
        bp.container("locker", 80, G + 1, z0 + 2, 0f, 1);
        bp.container("filing_cabinet", 68, G + 6, z0 + 2, 0f, 1);
        bp.container("weapon_case", 80, G + 6, z0 + 6, 0f, 1);
        bp.container("ammo_box", 84, G + 11, z0 + 4, 0f, 1);
        sign(74, G + 3, z1 + 1, BlockFace.SOUTH, "演练楼", "两层 + 天台", "适合小队对抗", "");
    }

    private void fallTowers() {
        int[][] towers = {{8, 5}, {16, 10}, {24, 15}};
        for (int[] t : towers) {
            int x = t[0];
            int h = t[1];
            int z = 53;
            b.fill(x - 1, G + 1, z - 1, x + 1, G + h, z + 1, Material.STONE_BRICKS);
            b.fill(x - 1, G + h, z - 1, x + 1, G + h, z + 1, Material.POLISHED_ANDESITE);
            for (int y = G + 1; y <= G + h; y++) {
                b.set(x, y, z + 2, "ladder[facing=south]");
            }
            sign(x - 1, G + 2, z + 2, BlockFace.SOUTH, "坠落台", h + " 格高", h >= 10 ? "大概率骨折" : "轻微摔伤", "测试夹板");
        }
    }

    // ------------------------------------------------------------------ extracts

    private void extractStrip() {
        record Pad(String id, String name, ExtractType type, Material floor, String[] lines, int seconds, long cost,
                   int opens, String switchId, int capacity) {
        }
        List<Pad> pads = List.of(
                new Pad("t_always", "常驻撤离点", ExtractType.ALWAYS, Material.LIME_CONCRETE,
                        new String[]{"常驻撤离", "随时可用", "站圈内 6 秒"}, 6, 0, 0, "", 0),
                new Pad("t_timed", "限时撤离点", ExtractType.TIMED, Material.ORANGE_CONCRETE,
                        new String[]{"限时开放", "开局 2 分钟后", "才能使用"}, 6, 0, 120, "", 0),
                new Pad("t_paid", "付费撤离点", ExtractType.PAID, Material.YELLOW_CONCRETE,
                        new String[]{"付费撤离", "支付 ◈5,000", "余额不足不能走"}, 6, 5_000, 0, "", 0),
                new Pad("t_switch", "电闸撤离点", ExtractType.SWITCH, Material.RED_CONCRETE,
                        new String[]{"需要拉闸", "先拉西边电闸", "开放 6 分钟"}, 6, 0, 0, "test_switch", 0),
                new Pad("t_nopack", "禁背包撤离点", ExtractType.NO_BACKPACK, Material.LIGHT_BLUE_CONCRETE,
                        new String[]{"禁止背包", "脱下背包", "才能撤离"}, 6, 0, 0, "", 0),
                new Pad("t_limited", "限员撤离点", ExtractType.LIMITED, Material.PURPLE_CONCRETE,
                        new String[]{"限员撤离", "每局只能走 2 人", "先到先得"}, 6, 0, 0, "", 2));
        int x = 106;
        for (int i = 0; i < pads.size(); i++) {
            Pad p = pads.get(i);
            int z = 8 + i * 9;
            b.fill(x - 2, G, z - 2, x + 2, G, z + 2, p.floor());
            bp.extract(p.id(), p.name(), x, G + 1, z, 2.5, p.type(), p.seconds(), p.cost(), p.opens(), 0, p.switchId(),
                    p.capacity(), 1.0);
            sign(W - 2, G + 2, z, BlockFace.WEST, p.lines()[0], p.lines()[1], p.lines()[2], p.name());
        }
        // Switch post for the SWITCH extract, a short walk west of its pad.
        int sx = 97;
        int sz = 8 + 3 * 9;
        b.set(sx, G + 1, sz, Material.POLISHED_DEEPSLATE);
        b.set(sx, G + 2, sz, "lever[face=floor,facing=east,powered=false]");
        bp.switches.add(new MapDef.SwitchDef("test_switch", "试验场电闸", new Pos(sx + 0.5, G + 2, sz + 0.5, 0), 360));
        sign(sx - 1, G + 1, sz, BlockFace.WEST, "电闸", "右键拉下", "电闸撤离点", "开放 6 分钟");
    }

    // ------------------------------------------------------------------ AI arena

    private void arena() {
        int wallZ = 33;
        int wallX = 91;
        for (int y = G + 1; y <= G + 8; y++) {
            Material m = y == G + 8 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.CRACKED_STONE_BRICKS;
            for (int x = 1; x <= wallX; x++) {
                b.set(x, y, wallZ, m);
            }
            for (int z = 1; z <= wallZ; z++) {
                b.set(wallX, y, z, m);
            }
        }
        // Entrances are screened by a baffle wall so the arena has no sight line into the test area.
        for (int cx : new int[]{20, 59}) {
            b.clear(cx - 1, G + 1, wallZ, cx + 1, G + 3, wallZ);
            b.fill(cx - 3, G + 1, wallZ + 2, cx + 3, G + 4, wallZ + 2, Material.CRACKED_STONE_BRICKS);
            sign(cx, G + 3, wallZ + 3, BlockFace.SOUTH, "⚠ 敌兵区入口", "巡逻/驻守/狙击", "机密以上难度", "60秒后出首领");
        }
        b.clear(wallX, G + 1, 19, wallX, G + 3, 21);
        b.fill(wallX + 2, G + 1, 17, wallX + 2, G + 4, 23, Material.CRACKED_STONE_BRICKS);
        sign(wallX + 3, G + 3, 20, BlockFace.EAST, "⚠ 敌兵区入口", "巡逻/驻守/狙击", "机密以上难度", "60秒后出首领");
        b.fill(1, G, 1, wallX - 1, G, wallZ - 1, Material.COARSE_DIRT);
        // Cover: sandbag lines and concrete blocks.
        for (int i = 0; i < 40; i++) {
            int x = b.between(4, wallX - 5);
            int z = b.between(4, wallZ - 4);
            if (Math.abs(x - 47) < 4 && z < 13) {
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
                b.set(px, G + 1, pz, i % 2 == 0 ? Material.PACKED_MUD : Material.SMOOTH_STONE);
                b.set(px, G + 2, pz, i % 2 == 0 ? "mud_brick_slab" : "smooth_stone_slab");
            }
        }
        // Sniper tower.
        int tx = 47;
        int tz = 8;
        int top = G + 8;
        for (int y = G + 1; y < top; y++) {
            b.set(tx - 1, y, tz - 1, Material.SPRUCE_LOG);
            b.set(tx + 1, y, tz - 1, Material.SPRUCE_LOG);
            b.set(tx - 1, y, tz + 1, Material.SPRUCE_LOG);
            b.set(tx + 1, y, tz + 1, Material.SPRUCE_LOG);
            b.set(tx, y, tz + 1, Material.SPRUCE_LOG);
            b.set(tx, y, tz + 2, "ladder[facing=south]");
        }
        b.fill(tx - 2, top, tz - 2, tx + 2, top, tz + 2, Material.SPRUCE_PLANKS);
        b.set(tx, top, tz + 2, "ladder[facing=south]");
        b.walls(tx - 2, tz - 2, tx + 2, tz + 2, top + 1, top + 1, Material.SPRUCE_FENCE);
        b.set(tx, top + 1, tz + 2, Material.AIR);
        bp.ai(tx, top + 1, tz, 1, 2, "sniper");
        bp.ai(24, G + 1, 18, 3, 10, "patrol");
        bp.ai(70, G + 1, 18, 3, 10, "patrol");
        bp.ai(47, G + 1, 26, 2, 4, "guard");
        bp.bosses.add(new MapDef.BossDef("drill_master", "试验场教官", new Pos(47.5, G + 1, 16.5, 0), 2, 1.0, 60));
        bp.container("weapon_case", 10, G + 1, 6, 0f, 2);
        bp.container("ammo_box", 84, G + 1, 6, 0f, 1);
        bp.container("crate", 30, G + 1, 28, 0f, 1);
        bp.container("crate", 64, G + 1, 28, 0f, 1);
        bp.container("airdrop", 47, G + 1, 20, 0f, 2);
        for (MapDef.ContainerDef c : bp.containers) {
            int cx = (int) Math.floor(c.pos().x());
            int cz = (int) Math.floor(c.pos().z());
            if (cz < wallZ && cx < wallX) {
                b.clear(cx, G + 1, cz, cx, G + 2, cz);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Places a waxed wall sign on the block in front of {@code facing}'s opposite side. Plain dark text on birch:
     * glowing text is drawn nine times per line every frame, which dozens of signs turn into a frame-rate problem.
     */
    private void sign(int x, int y, int z, BlockFace facing, String title, String l2, String l3, String l4) {
        Block block = world.getBlockAt(x, y, z);
        BlockFace back = facing.getOppositeFace();
        if (block.getRelative(back).getType().isAir()) {
            b.set(x + back.getModX(), y, z + back.getModZ(), Material.POLISHED_ANDESITE);
        }
        b.set(x, y, z, "birch_wall_sign[facing=" + facing.name().toLowerCase() + "]");
        if (block.getState() instanceof Sign sign) {
            SignSide side = sign.getSide(Side.FRONT);
            side.line(0, Component.text(title, TITLE));
            side.line(1, Component.text(l2, BODY));
            side.line(2, Component.text(l3, BODY));
            side.line(3, Component.text(l4, BODY));
            sign.setWaxed(true);
            sign.update(true, false);
        }
    }
}
