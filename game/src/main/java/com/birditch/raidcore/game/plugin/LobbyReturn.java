package com.birditch.raidcore.game.plugin;

import com.birditch.raidcore.bukkit.BungeeConnector;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/// The one shared "send this player back to the lobby" flow: across the proxy
/// when one is configured, otherwise a teleport to the default world spawn
/// with the `game.returned-to-lobby` notice (proxied players switch servers
/// instantly and would never see it).
public final class LobbyReturn {

  private LobbyReturn() {}

  public static void send(BungeeConnector bungee, GameServerConfig config, Messages messages,
      Player player) {
    Preconditions.checkNotNull(bungee, "bungee");
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(player, "player");
    if (config.proxyEnabled()) {
      bungee.send(player, config.lobbyServerName());
      return;
    }
    player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
    player.sendMessage(messages.raw("game.returned-to-lobby"));
  }
}
