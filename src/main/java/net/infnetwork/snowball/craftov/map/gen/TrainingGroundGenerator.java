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
 * Builds "训练场 / Training Ground", the course of the training raid (TrainingService): four
 * walled rooms in a row from west to east, each closed by a gate of iron bars that opens once the
 * room's lesson is done. The assembly room holds the supply crate; the range corridor looks north
 * over a waist-high wall at three targets 10, 20 and 30 m out; the trap corridor has a tripwire
 * that wounds whoever crosses it; the extract yard has the lever and the extract it powers. Open
 * to the sky, so it is bright at any time of day.
 * <p>
 * The positions TrainingService needs (targets, gates, the tripwire) are constants here; the
 * spawn, the crate, the lever and the extract are ordinary map markers.
 */
public final class TrainingGroundGenerator implements MapGenerator {

    public static final String ID = "training";
    public static final int G = 64;
    /** The rooms' outer walls run along these lines; every gate is a 3x3 opening at z 9..11. */
    public static final int GATE_Z0 = 9;
    public static final int GATE_Z1 = 11;
    /** Gate between the assembly room and the range (x), between the range and the trap, and into the yard. */
    public static final int GATE_LOOT = 15;
    public static final int GATE_RANGE = 45;
    public static final int GATE_HEAL = 60;
    /** Targets on the range, north of the firing line (z 3): x, z of each. */
    public static final int[][] TARGETS = {{22, -7}, {30, -17}, {38, -27}};
    /** Crossing this x in the trap corridor sets off the tripwire. */
    public static final int TRAP_X = 51;
    /** The crate in the assembly room, the lever and the extract in the yard. */
    public static final int CRATE_X = 11;
    public static final int CRATE_Z = 10;
    public static final int LEVER_X = 65;
    public static final int LEVER_Z = 5;
    public static final int EXTRACT_X = 74;
    public static final int EXTRACT_Z = 10;
    public static final String SWITCH = "training_switch";

    private static final TextColor TITLE = TextColor.color(0x8A4B00);
    private static final TextColor BODY = TextColor.color(0x1E1E1E);

    private final Builder b;
    private final World world;
    private final Blueprint bp = new Blueprint();

    public TrainingGroundGenerator(World world) {
        this.world = world;
        this.b = new Builder(world, 0x7121L);
    }

    @Override
    public MapDef generate() {
        b.fill(-2, G - 2, -35, 82, G - 2, 21, Material.STONE);
        b.fill(-2, G - 1, -35, 82, G - 1, 21, Material.DIRT);
        b.fill(-2, G, -35, 82, G, 21, Material.GRASS_BLOCK);
        assembly();
        range();
        trap();
        yard();
        // Last: each room's walls are drawn over the wall it shares with the room before.
        gate(GATE_LOOT);
        gate(GATE_RANGE);
        gate(GATE_HEAL);
        return new MapDef(ID, "训练场", "新手训练行动：搜箱子、开镜打靶、换弹、处理伤势、拉闸撤离。单人，装备原样归还，不计战绩。",
                "craftov_tpl_" + ID, 15 * 60, new Pos(40, G + 30, 10, 0),
                List.copyOf(bp.spawns), List.copyOf(bp.extracts), List.copyOf(bp.containers), List.copyOf(bp.ai),
                List.copyOf(bp.bosses), List.copyOf(bp.doors), List.copyOf(bp.switches), -2, -35, 82, 21, "day", true, true);
    }

    @Override
    public long blocksPlaced() {
        return b.placed();
    }

    /** A closed room: floor, walls 4 high, no roof. */
    private void room(int x1, int z1, int x2, int z2, Material floor, Material wall) {
        b.fill(x1, G, z1, x2, G, z2, floor);
        b.walls(x1, z1, x2, z2, G + 1, G + 4, wall);
        for (int x = x1; x <= x2; x++) {
            b.set(x, G + 4, z1, Material.POLISHED_BLACKSTONE_BRICKS);
            b.set(x, G + 4, z2, Material.POLISHED_BLACKSTONE_BRICKS);
        }
        for (int z = z1; z <= z2; z++) {
            b.set(x1, G + 4, z, Material.POLISHED_BLACKSTONE_BRICKS);
            b.set(x2, G + 4, z, Material.POLISHED_BLACKSTONE_BRICKS);
        }
    }

    /** A gate in the wall at {@code x}: a 3x3 opening filled with iron bars (TrainingService opens it). */
    private void gate(int x) {
        b.fill(x, G + 1, GATE_Z0, x, G + 3, GATE_Z1, "iron_bars[east=false,west=false,north=true,south=true]");
        b.set(x, G, GATE_Z0 - 1, Material.YELLOW_CONCRETE);
        b.set(x, G, GATE_Z1 + 1, Material.YELLOW_CONCRETE);
    }

    private void assembly() {
        room(0, 3, GATE_LOOT, 17, Material.LIGHT_GRAY_CONCRETE, Material.POLISHED_ANDESITE);
        b.fill(2, G, 8, 4, G, 12, Material.CYAN_CONCRETE);
        bp.spawn(1, 3, G + 1, 10, -90f);
        bp.container("crate", CRATE_X, G + 1, CRATE_Z, 90f, 0);
        sign(1, G + 2, 6, BlockFace.EAST, "训练行动", "跟着左上角", "的目标一步步来", "装备会原样归还");
        sign(1, G + 2, 14, BlockFace.EAST, "第一课", "右键物资箱搜索", "点击物品拿走", "拿齐才开门");
    }

    private void range() {
        room(GATE_LOOT, 3, GATE_RANGE, 17, Material.SMOOTH_STONE, Material.POLISHED_ANDESITE);
        // The north wall is a firing line: waist high (drawn one block, too high to jump), open above.
        b.clear(GATE_LOOT + 1, G + 1, 3, GATE_RANGE - 1, G + 4, 3);
        b.fill(GATE_LOOT + 1, G + 1, 3, GATE_RANGE - 1, G + 1, 3, "andesite_wall");
        b.fill(GATE_LOOT + 1, G, 4, GATE_RANGE - 1, G, 5, Material.YELLOW_TERRACOTTA);
        // The target field: walled high all round so nothing leaves it.
        b.fill(GATE_LOOT + 1, G, -32, GATE_RANGE - 1, G, 2, Material.COARSE_DIRT);
        for (int y = G + 1; y <= G + 7; y++) {
            for (int z = -33; z <= 2; z++) {
                b.set(GATE_LOOT, y, z, Material.STONE_BRICKS);
                b.set(GATE_RANGE, y, z, Material.STONE_BRICKS);
            }
            for (int x = GATE_LOOT; x <= GATE_RANGE; x++) {
                b.set(x, y, -33, Material.STONE_BRICKS);
            }
        }
        for (int[] t : TARGETS) {
            b.fill(t[0] - 1, G, t[1] - 1, t[0] + 1, G, t[1] + 1, Material.RED_CONCRETE);
            b.fill(t[0] - 1, G + 1, t[1] - 2, t[0] + 1, G + 3, t[1] - 2, Material.HAY_BLOCK);
        }
        sign(GATE_LOOT + 1, G + 2, 15, BlockFace.EAST, "第二课", "按住右键开镜", "三个靶子各打中", "一次，再换弹");
    }

    private void trap() {
        room(GATE_RANGE, 7, GATE_HEAL, 13, Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS);
        // The tripwire: hooks on both walls and the string across, at ankle height.
        b.set(TRAP_X, G + 1, 8, "tripwire_hook[facing=south,attached=true]");
        b.set(TRAP_X, G + 1, 12, "tripwire_hook[facing=north,attached=true]");
        for (int z = 9; z <= 11; z++) {
            b.set(TRAP_X, G + 1, z, "tripwire[attached=true,north=true,south=true]");
        }
        sign(GATE_RANGE + 1, G + 2, 12, BlockFace.EAST, "第三课", "小心脚下", "受伤后用绷带", "和夹板处理");
    }

    private void yard() {
        room(GATE_HEAL, 1, 80, 19, Material.GRAY_CONCRETE, Material.POLISHED_ANDESITE);
        b.set(LEVER_X, G + 1, LEVER_Z, Material.POLISHED_DEEPSLATE);
        b.set(LEVER_X, G + 2, LEVER_Z, "lever[face=floor,facing=south,powered=false]");
        bp.switches.add(new MapDef.SwitchDef(SWITCH, "训练场电闸", new Pos(LEVER_X + 0.5, G + 2, LEVER_Z + 0.5, 0), 120));
        b.fill(EXTRACT_X - 2, G, EXTRACT_Z - 2, EXTRACT_X + 2, G, EXTRACT_Z + 2, Material.LIME_CONCRETE);
        bp.extract("training_exit", "训练场撤离点", EXTRACT_X, G + 1, EXTRACT_Z, 2.5, ExtractType.SWITCH, 6, 0, 0, 0,
                SWITCH, 0, 1.0);
        sign(GATE_HEAL + 1, G + 2, 14, BlockFace.EAST, "第四课", "右键拉下电闸", "站进绿色撤离点", "倒计时后撤离");
    }

    private void sign(int x, int y, int z, BlockFace facing, String title, String l2, String l3, String l4) {
        Block block = world.getBlockAt(x, y, z);
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
