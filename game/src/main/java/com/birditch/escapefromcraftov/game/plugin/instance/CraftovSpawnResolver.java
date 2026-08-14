package com.birditch.escapefromcraftov.game.plugin.instance;

import com.birditch.escapefromcraftov.api.game.CraftovSpawnArea;
import com.birditch.escapefromcraftov.api.game.CraftovSpawnLocation;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/// Resolves a bounded number of authored spawn-region candidates on the main thread.
final class CraftovSpawnResolver {

  static final int MAXIMUM_BLOCK_CHECKS = 24;
  private static final java.util.Set<Material> DANGEROUS_FLOORS = java.util.Set.of(
      Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
      Material.FIRE, Material.SOUL_FIRE);

  private CraftovSpawnResolver() {}

  static Optional<Location> resolve(World world, CraftovSpawnLocation spawn, long seed) {
    Preconditions.checkNotNull(world, "world");
    Preconditions.checkNotNull(spawn, "spawn");
    for (Candidate candidate : candidates(spawn, seed, MAXIMUM_BLOCK_CHECKS)) {
      if (safe(world, candidate)) {
        return Optional.of(new Location(world, candidate.x() + 0.5, candidate.y(),
            candidate.z() + 0.5, spawn.yaw(), spawn.pitch()));
      }
    }
    return Optional.empty();
  }

  static List<Candidate> candidates(CraftovSpawnLocation spawn, long seed, int limit) {
    Preconditions.checkNotNull(spawn, "spawn");
    Preconditions.checkArgument(limit >= 1 && limit <= 256, "limit must be 1..256");
    LinkedHashSet<Candidate> result = new LinkedHashSet<>();
    CraftovSpawnArea area = spawn.region();
    if (area == null) return List.of(new Candidate(spawn.x(), spawn.y(), spawn.z()));
    if (area.contains(spawn.x(), spawn.y(), spawn.z())) {
      result.add(new Candidate(spawn.x(), spawn.y(), spawn.z()));
    }
    SplittableRandom random = new SplittableRandom(seed);
    int attempts = 0;
    while (result.size() < limit && attempts++ < limit * 8) {
      result.add(new Candidate(randomCoordinate(random, area.minX(), area.maxX()),
          randomCoordinate(random, area.minY(), area.maxY()),
          randomCoordinate(random, area.minZ(), area.maxZ())));
    }
    if (result.isEmpty()) {
      result.add(new Candidate(spawn.x(), spawn.y(), spawn.z()));
    }
    return List.copyOf(new ArrayList<>(result));
  }

  private static int randomCoordinate(SplittableRandom random, int minimum, int maximum) {
    return (int) random.nextLong(minimum, (long) maximum + 1L);
  }

  private static boolean safe(World world, Candidate candidate) {
    int y = candidate.y();
    if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) return false;
    Block floor = world.getBlockAt(candidate.x(), y - 1, candidate.z());
    Block feet = world.getBlockAt(candidate.x(), y, candidate.z());
    Block head = world.getBlockAt(candidate.x(), y + 1, candidate.z());
    return floor.getType().isSolid() && !floor.isLiquid()
        && !DANGEROUS_FLOORS.contains(floor.getType())
        && feet.isPassable() && !feet.isLiquid()
        && head.isPassable() && !head.isLiquid();
  }

  record Candidate(int x, int y, int z) {}
}
