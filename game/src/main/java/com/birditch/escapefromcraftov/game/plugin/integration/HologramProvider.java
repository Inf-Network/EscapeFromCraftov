package com.birditch.escapefromcraftov.game.plugin.integration;

import java.util.List;
import org.bukkit.entity.Player;

public interface HologramProvider extends AutoCloseable {

  /** Reconciles all personal labels that should currently exist for this player. */
  void render(Player player, List<EditorHologramView> views);

  void clear(Player player);

  @Override
  void close();
}
