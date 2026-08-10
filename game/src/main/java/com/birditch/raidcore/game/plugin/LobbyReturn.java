package com.birditch.raidcore.game.plugin;

import com.birditch.raidcore.bukkit.BungeeConnector;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import org.bukkit.entity.Player;

/// The one shared "send this player back to the lobby" flow: across the proxy
/// through the proxy. Game servers are dedicated instances and never retain a
/// local lobby fallback.
public final class LobbyReturn {

  private LobbyReturn() {}

  public static void send(BungeeConnector bungee, GameServerConfig config, Messages messages,
      Player player) {
    Preconditions.checkNotNull(bungee, "bungee");
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(player, "player");
    bungee.send(player, config.lobbyServerName());
  }
}
