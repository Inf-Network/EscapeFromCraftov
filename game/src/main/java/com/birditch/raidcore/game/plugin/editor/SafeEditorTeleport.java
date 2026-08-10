package com.birditch.raidcore.game.plugin.editor;

import com.birditch.raidcore.map.BlockPosition;
import org.bukkit.Location;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/** Finds a two-block-high, non-liquid editor teleport landing spot. */
public final class SafeEditorTeleport {

  private SafeEditorTeleport() {}

  public static @Nullable Location find(World world, BlockPosition target, float yaw, float pitch) {
    for (int radius = 0; radius <= 3; radius++) {
      for (int dx = -radius; dx <= radius; dx++) {
        for (int dz = -radius; dz <= radius; dz++) {
          if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
          int x = target.x() + dx;
          int z = target.z() + dz;
          for (int y = Math.min(world.getMaxHeight() - 2, target.y() + 6);
              y >= Math.max(world.getMinHeight() + 1, target.y() - 8); y--) {
            var floor = world.getBlockAt(x, y - 1, z);
            var feet = world.getBlockAt(x, y, z);
            var head = world.getBlockAt(x, y + 1, z);
            if (!floor.getType().isSolid() || floor.isLiquid()
                || !feet.isPassable() || !head.isPassable()
                || feet.isLiquid() || head.isLiquid()) continue;
            return new Location(world, x + 0.5, y, z + 0.5, yaw, pitch);
          }
        }
      }
    }
    return null;
  }
}
