package net.infnetwork.snowball.craftov.map.gen;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Thin block-placing helper with cached block data and no physics updates. */
public final class Builder {

    private final World world;
    private final Random random;
    private final Map<String, BlockData> cache = new HashMap<>();
    private long placed;

    public Builder(World world, long seed) {
        this.world = world;
        this.random = new Random(seed);
    }

    public World world() {
        return world;
    }

    public Random random() {
        return random;
    }

    public long placed() {
        return placed;
    }

    public BlockData data(String spec) {
        return cache.computeIfAbsent(spec, s -> Bukkit.createBlockData(s.contains(":") ? s : "minecraft:" + s));
    }

    public void set(int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material, false);
        placed++;
    }

    public void set(int x, int y, int z, String spec) {
        world.getBlockAt(x, y, z).setBlockData(data(spec), false);
        placed++;
    }

    public void set(int x, int y, int z, BlockData data) {
        world.getBlockAt(x, y, z).setBlockData(data, false);
        placed++;
    }

    public Material type(int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType();
    }

    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    set(x, y, z, material);
                }
            }
        }
    }

    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, String spec) {
        BlockData data = data(spec);
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    set(x, y, z, data);
                }
            }
        }
    }

    public void clear(int x1, int y1, int z1, int x2, int y2, int z2) {
        fill(x1, y1, z1, x2, y2, z2, Material.AIR);
    }

    /** Hollow rectangle of walls between y1..y2. */
    public void walls(int x1, int z1, int x2, int z2, int y1, int y2, Material material) {
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                set(x, y, z1, material);
                set(x, y, z2, material);
            }
            for (int z = z1; z <= z2; z++) {
                set(x1, y, z, material);
                set(x2, y, z, material);
            }
        }
    }

    public void door(int x, int y, int z, BlockFace facing, String wood) {
        String f = facing.name().toLowerCase();
        set(x, y, z, wood + "_door[facing=" + f + ",half=lower,hinge=left,open=false]");
        set(x, y + 1, z, wood + "_door[facing=" + f + ",half=upper,hinge=left,open=false]");
    }

    public void pane(int x, int y, int z, String glass) {
        set(x, y, z, glass);
    }

    public boolean chance(double p) {
        return random.nextDouble() < p;
    }

    public int between(int min, int max) {
        return min + random.nextInt(Math.max(1, max - min + 1));
    }

    public <T> T pick(T[] values) {
        return values[random.nextInt(values.length)];
    }
}
