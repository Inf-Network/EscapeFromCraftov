package com.birditch.escapefromcraftov.game.plugin.integration;

import com.birditch.escapefromcraftov.map.CuboidBounds;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import java.util.Optional;
import org.bukkit.entity.Player;

/// WorldEdit public-API adapter. Polygon selections intentionally become their cuboid envelope.
public final class WorldEditSelectionProvider implements SelectionProvider {

  @Override
  public Optional<CuboidBounds> selection(Player player, String expectedWorld) {
    if (!player.getWorld().getName().equals(expectedWorld)) return Optional.empty();
    try {
      var actor = BukkitAdapter.adapt(player);
      var session = WorldEdit.getInstance().getSessionManager().get(actor);
      Region region = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
      BlockVector3 minimum = region.getMinimumPoint();
      BlockVector3 maximum = region.getMaximumPoint();
      return Optional.of(new CuboidBounds(minimum.x(), minimum.y(), minimum.z(),
          maximum.x(), maximum.y(), maximum.z()));
    } catch (IncompleteRegionException ignored) {
      return Optional.empty();
    }
  }
}
