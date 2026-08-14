package com.birditch.escapefromcraftov.game.plugin.editor;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.map.BlockPosition;
import com.birditch.escapefromcraftov.map.ExtractionDefinition;
import com.birditch.escapefromcraftov.map.ManualCraftovMap;
import com.birditch.escapefromcraftov.map.MapValidationIssue;
import com.birditch.escapefromcraftov.map.MapValidationSeverity;
import com.birditch.escapefromcraftov.map.SpawnDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Validates authored player positions against blocks in an already loaded Bukkit world. */
public final class BukkitMapEnvironmentValidator implements ManualMapEnvironmentValidator {

  static final int MAXIMUM_EXTRACTION_POSITION_CHECKS = 512;
  private static final Set<Material> DANGEROUS_MATERIALS = Set.of(
      Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
      Material.FIRE, Material.SOUL_FIRE, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH,
      Material.WITHER_ROSE, Material.POINTED_DRIPSTONE, Material.NETHER_PORTAL,
      Material.END_PORTAL, Material.END_GATEWAY);

  private final Server server;

  public BukkitMapEnvironmentValidator(Server server) {
    this.server = Preconditions.checkNotNull(server, "server");
  }

  @Override
  public List<MapValidationIssue> validate(ManualCraftovMap map) {
    Preconditions.checkNotNull(map, "map");
    World world = server.getWorld(map.worldName());
    // A source world may exist only on disk. The structural/reference validator owns
    // world_missing; environment validation is intentionally silent until Bukkit loads it.
    if (world == null) return List.of();

    List<MapValidationIssue> issues = new ArrayList<>();
    for (SpawnDefinition spawn : map.spawns()) {
      if (!spawn.enabled()) continue;
      BlockPosition feet = spawn.position();
      if (!chunkLoaded(world, feet)) {
        issues.add(warning("spawn_environment_unloaded", spawn.id(),
            "出生点所在区块尚未加载，无法验证方块安全性: " + spawn.id()
                + "。请进入该位置加载区块后重新验证"));
      } else if (!safe(world, feet)) {
        issues.add(error("spawn_environment_unsafe", spawn.id(),
            "出生点脚部位置不可站立: " + spawn.id() + " (" + feet.x() + ", " + feet.y()
                + ", " + feet.z() + ")。必须保证 y-1 是安全实体方块，y 与 y+1 可通过，"
                + "并且三层均不是液体或危险方块"));
      }
    }
    int minimumFeetY = world.getMinHeight() + 1;
    int maximumFeetY = world.getMaxHeight() - 2;
    for (ExtractionDefinition extraction : map.extractions()) {
      if (!extraction.enabled()) continue;
      int[] unloadedChecks = {0};
      boolean safe = SafeFootPositionScanner.hasSafePosition(extraction.bounds(), minimumFeetY,
          maximumFeetY, MAXIMUM_EXTRACTION_POSITION_CHECKS,
          candidate -> {
            if (!chunkLoaded(world, candidate)) {
              unloadedChecks[0]++;
              return false;
            }
            return safe(world, candidate);
          });
      if (!safe) {
        if (unloadedChecks[0] > 0) {
          issues.add(warning("extraction_environment_unloaded", extraction.id(),
              "撤离区域含未加载区块，且已加载候选中没有安全脚部位置: " + extraction.id()
                  + "。请进入该区域加载区块后重新验证；区域 Y 必须覆盖玩家脚部，"
                  + "不能只圈地面方块"));
        } else {
          issues.add(error("extraction_environment_unsafe", extraction.id(),
              "撤离区域内没有可站立的玩家脚部位置: " + extraction.id()
                  + "。区域 Y 必须覆盖玩家脚部高度，而不是只圈地面方块；"
                  + "脚下 y-1 需为安全实体方块，脚部 y 与头部 y+1 需可通过"));
        }
      }
    }
    return List.copyOf(issues);
  }

  private static boolean safe(World world, BlockPosition feetPosition) {
    int y = feetPosition.y();
    if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) return false;
    Block floor = world.getBlockAt(feetPosition.x(), y - 1, feetPosition.z());
    Block feet = world.getBlockAt(feetPosition.x(), y, feetPosition.z());
    Block head = world.getBlockAt(feetPosition.x(), y + 1, feetPosition.z());
    return floor.getType().isSolid() && !floor.isLiquid() && !dangerous(floor)
        && feet.isPassable() && !feet.isLiquid() && !dangerous(feet)
        && head.isPassable() && !head.isLiquid() && !dangerous(head);
  }

  private static boolean chunkLoaded(World world, BlockPosition position) {
    return world.isChunkLoaded(position.x() >> 4, position.z() >> 4);
  }

  private static boolean dangerous(Block block) {
    return DANGEROUS_MATERIALS.contains(block.getType());
  }

  private static MapValidationIssue error(String code, String objectId, String message) {
    return new MapValidationIssue(MapValidationSeverity.ERROR, code, objectId, message);
  }

  private static MapValidationIssue warning(String code, String objectId, String message) {
    return new MapValidationIssue(MapValidationSeverity.WARNING, code, objectId, message);
  }
}
