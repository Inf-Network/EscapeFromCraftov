package com.birditch.raidcore.game.plugin.integration;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.map.CuboidBounds;
import java.util.List;
import java.util.Optional;
import org.bukkit.entity.Player;

public final class CompositeSelectionProvider implements SelectionProvider {

  private final List<SelectionProvider> providers;

  public CompositeSelectionProvider(List<SelectionProvider> providers) {
    this.providers = List.copyOf(Preconditions.checkNotNull(providers, "providers"));
  }

  @Override
  public Optional<CuboidBounds> selection(Player player, String expectedWorld) {
    for (SelectionProvider provider : providers) {
      Optional<CuboidBounds> value = provider.selection(player, expectedWorld);
      if (value.isPresent()) return value;
    }
    return Optional.empty();
  }
}
