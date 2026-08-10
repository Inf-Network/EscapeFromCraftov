package com.birditch.raidcore.game.plugin.integration;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.game.plugin.editor.EditorSelectionService;
import com.birditch.raidcore.map.CuboidBounds;
import java.util.Optional;
import org.bukkit.entity.Player;

public final class RaidSelectionProvider implements SelectionProvider {

  private final EditorSelectionService selections;

  public RaidSelectionProvider(EditorSelectionService selections) {
    this.selections = Preconditions.checkNotNull(selections, "selections");
  }

  @Override
  public Optional<CuboidBounds> selection(Player player, String expectedWorld) {
    return selections.bounds(player.getUniqueId(), expectedWorld);
  }
}
