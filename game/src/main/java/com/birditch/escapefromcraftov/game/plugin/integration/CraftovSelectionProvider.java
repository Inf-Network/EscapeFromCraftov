package com.birditch.escapefromcraftov.game.plugin.integration;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.editor.EditorSelectionService;
import com.birditch.escapefromcraftov.map.CuboidBounds;
import java.util.Optional;
import org.bukkit.entity.Player;

public final class CraftovSelectionProvider implements SelectionProvider {

  private final EditorSelectionService selections;

  public CraftovSelectionProvider(EditorSelectionService selections) {
    this.selections = Preconditions.checkNotNull(selections, "selections");
  }

  @Override
  public Optional<CuboidBounds> selection(Player player, String expectedWorld) {
    return selections.bounds(player.getUniqueId(), expectedWorld);
  }
}
