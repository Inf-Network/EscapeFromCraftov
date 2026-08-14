package com.birditch.escapefromcraftov.game.plugin.ai;

import com.birditch.escapefromcraftov.api.game.CraftovAiZone;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Finds a bounded, two-block-high safe position inside an authored AI region. */
final class CraftovAiSpawnResolver {

  private static final int MAXIMUM_COLUMNS = 40;
  private static final int MAXIMUM_HEIGHT_CHECKS_PER_COLUMN = 24;
  private static final Set<Material> DANGEROUS_FLOORS = Set.of(
      Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
      Material.FIRE, Material.SOUL_FIRE, Material.POWDER_SNOW);

  private CraftovAiSpawnResolver() {}

  static Optional<Location> resolve(World world, CraftovAiZone zone, long seed) {
    Preconditions.checkNotNull(world, "world");
    Preconditions.checkNotNull(zone, "zone");
    SplittableRandom random = new SplittableRandom(seed);
    LinkedHashSet<Column> columns = new LinkedHashSet<>();
    // Seeded random columns come first so several actors do not all stack on
    // the cuboid centre. The centre remains the deterministic final fallback.
    columns.add(new Column(coordinate(random, zone.minX(), zone.maxX()),
        coordinate(random, zone.minZ(), zone.maxZ())));
    int attempts = 0;
    while (columns.size() < MAXIMUM_COLUMNS && attempts++ < MAXIMUM_COLUMNS * 8) {
      columns.add(new Column(coordinate(random, zone.minX(), zone.maxX()),
          coordinate(random, zone.minZ(), zone.maxZ())));
    }
    columns.add(new Column(midpoint(zone.minX(), zone.maxX()), midpoint(zone.minZ(), zone.maxZ())));
    for (Column column : columns) {
      Optional<Location> location = resolveColumn(world, zone, column, random);
      if (location.isPresent()) return location;
    }
    return Optional.empty();
  }

  private static Optional<Location> resolveColumn(World world, CraftovAiZone zone, Column column,
      SplittableRandom random) {
    // Editors commonly select either the floor blocks or the air occupied by
    // an actor. Including maxY + 1 supports both conventions without allowing
    // a spawn to drift horizontally outside the authored cuboid.
    int minimum = Math.max(world.getMinHeight() + 1, zone.minY());
    int maximum = Math.min(world.getMaxHeight() - 2, saturatedIncrement(zone.maxY()));
    if (maximum < minimum) return Optional.empty();
    LinkedHashSet<Integer> heights = new LinkedHashSet<>();
    heights.add(maximum);
    heights.add(minimum);
    int range = maximum - minimum + 1;
    int limit = Math.min(range, MAXIMUM_HEIGHT_CHECKS_PER_COLUMN);
    int attempts = 0;
    while (heights.size() < limit && attempts++ < limit * 8) {
      heights.add(coordinate(random, minimum, maximum));
    }
    for (int y : heights) {
      if (safe(world, column.x(), y, column.z())) {
        return Optional.of(new Location(world, column.x() + 0.5, y, column.z() + 0.5));
      }
    }
    return Optional.empty();
  }

  private static boolean safe(World world, int x, int y, int z) {
    Block floor = world.getBlockAt(x, y - 1, z);
    Block feet = world.getBlockAt(x, y, z);
    Block head = world.getBlockAt(x, y + 1, z);
    return floor.getType().isSolid() && !floor.isLiquid()
        && !DANGEROUS_FLOORS.contains(floor.getType())
        && feet.isPassable() && !feet.isLiquid()
        && head.isPassable() && !head.isLiquid();
  }

  private static int midpoint(int minimum, int maximum) {
    return (int) (((long) minimum + maximum) / 2L);
  }

  private static int coordinate(SplittableRandom random, int minimum, int maximum) {
    return (int) random.nextLong(minimum, (long) maximum + 1L);
  }

  private static int saturatedIncrement(int value) {
    return value == Integer.MAX_VALUE ? value : value + 1;
  }

  private record Column(int x, int z) {}
}
