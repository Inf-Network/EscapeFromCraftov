package com.birditch.escapefromcraftov.game.plugin.editor;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.map.BlockPosition;
import com.birditch.escapefromcraftov.map.CuboidBounds;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// Per-admin secondary selection used alongside the required WorldEdit adapter.
public final class EditorSelectionService {

  private final Map<UUID, Selection> selections = new HashMap<>();

  public void setFirst(UUID playerId, String worldName, BlockPosition position) {
    Selection current = selections.get(playerId);
    BlockPosition second = current != null && current.worldName().equals(worldName)
        ? current.second() : null;
    selections.put(playerId, new Selection(worldName, position, second));
  }

  public void setSecond(UUID playerId, String worldName, BlockPosition position) {
    Selection current = selections.get(playerId);
    BlockPosition first = current != null && current.worldName().equals(worldName)
        ? current.first() : null;
    selections.put(playerId, new Selection(worldName, first, position));
  }

  public Optional<CuboidBounds> bounds(UUID playerId, String worldName) {
    Selection selection = selections.get(playerId);
    if (selection == null || !selection.worldName().equals(worldName)
        || selection.first() == null || selection.second() == null) return Optional.empty();
    return Optional.of(CuboidBounds.between(selection.first(), selection.second()));
  }

  public void clear(UUID playerId) {
    selections.remove(playerId);
  }

  public record Selection(
      String worldName, @Nullable BlockPosition first, @Nullable BlockPosition second) {
    public Selection {
      Preconditions.checkNotNull(worldName, "worldName");
    }
  }
}
