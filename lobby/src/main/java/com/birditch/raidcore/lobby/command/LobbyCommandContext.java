package com.birditch.raidcore.lobby.command;

import com.birditch.raidcore.api.matchmaking.MatchmakingService;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.lobby.LobbyConfig;
import com.birditch.raidcore.lobby.RaidLobbyPlugin;

/// Collaborators shared by every lobby command node.
public record LobbyCommandContext(
    RaidLobbyPlugin plugin,
    MatchmakingService matchmaking,
    Messages messages,
    LobbyConfig config) {

  public LobbyCommandContext {
    Preconditions.checkNotNull(plugin, "plugin");
    Preconditions.checkNotNull(matchmaking, "matchmaking");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(config, "config");
  }
}
