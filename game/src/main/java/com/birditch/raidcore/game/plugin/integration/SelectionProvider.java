package com.birditch.raidcore.game.plugin.integration;

import com.birditch.raidcore.map.CuboidBounds;
import java.util.Optional;
import org.bukkit.entity.Player;

public interface SelectionProvider {

  Optional<CuboidBounds> selection(Player player, String expectedWorld);
}
