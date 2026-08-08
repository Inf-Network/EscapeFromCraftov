package com.birditch.raidcore.lobby;

import com.birditch.raidcore.api.settings.PlayerSettingsService;
import com.birditch.raidcore.api.settings.SettingKey;
import com.birditch.raidcore.common.check.Preconditions;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/// Applies [SettingKey#LOBBY_PLAYER_VISIBILITY] between one player and every
/// other online player: a pair sees each other only when BOTH have the setting
/// enabled — turning it off hides others from you and you from others.
public final class LobbyVisibility {

  private LobbyVisibility() {}

  public static void apply(Plugin plugin, PlayerSettingsService settings, Player player) {
    Preconditions.checkNotNull(plugin, "plugin");
    Preconditions.checkNotNull(settings, "settings");
    Preconditions.checkNotNull(player, "player");
    boolean playerVisible = settings.get(player.getUniqueId(), SettingKey.LOBBY_PLAYER_VISIBILITY);
    for (Player other : plugin.getServer().getOnlinePlayers()) {
      if (other.getUniqueId().equals(player.getUniqueId())) {
        continue;
      }
      boolean bothVisible = playerVisible
          && settings.get(other.getUniqueId(), SettingKey.LOBBY_PLAYER_VISIBILITY);
      if (bothVisible) {
        player.showPlayer(plugin, other);
        other.showPlayer(plugin, player);
      } else {
        player.hidePlayer(plugin, other);
        other.hidePlayer(plugin, player);
      }
    }
  }
}
