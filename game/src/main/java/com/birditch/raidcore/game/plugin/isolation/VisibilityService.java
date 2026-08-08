package com.birditch.raidcore.game.plugin.isolation;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.game.plugin.instance.InstanceWorldIndex;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/// Keeps entity and tab-list visibility aligned with isolation groups: a
/// player only sees members of their own group (`"lobby"` or `"game:<id>"`).
///
/// MAIN-THREAD only — hide/show are Bukkit API calls. Callers (connection
/// listener, instance-world bridge) invoke [#refresh(Player)] after every
/// group transition.
public final class VisibilityService {

  private final Plugin plugin;
  private final InstanceWorldIndex index;

  public VisibilityService(Plugin plugin, InstanceWorldIndex index) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.index = Preconditions.checkNotNull(index, "index");
  }

  /// Re-evaluates visibility between `player` and every other online player,
  /// applying show/hide in both directions so the pair always agrees.
  public void refresh(Player player) {
    Preconditions.checkNotNull(player, "player");
    String group = index.groupOf(player.getUniqueId());
    for (Player other : Bukkit.getOnlinePlayers()) {
      if (other.getUniqueId().equals(player.getUniqueId())) {
        continue;
      }
      if (index.groupOf(other.getUniqueId()).equals(group)) {
        player.showPlayer(plugin, other);
        other.showPlayer(plugin, player);
      } else {
        player.hidePlayer(plugin, other);
        other.hidePlayer(plugin, player);
      }
    }
  }

  /// Refreshes every online player; pairwise updates are idempotent, so the
  /// repeated work converges to a consistent state in one pass.
  public void refreshAll() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      refresh(player);
    }
  }
}
