package com.birditch.raidcore.lobby.command;

import com.birditch.raidcore.api.matchmaking.MatchmakingService;
import com.birditch.raidcore.api.network.InstanceDirectory;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.lobby.LobbyConfig;
import com.birditch.raidcore.lobby.LobbyDataGate;
import com.birditch.raidcore.lobby.RaidLobbyPlugin;
import com.birditch.raidcore.lobby.menu.LobbyMenuController;

/// Collaborators shared by every lobby command node.
public record LobbyCommandContext(
    RaidLobbyPlugin plugin,
    MatchmakingService matchmaking,
    InstanceDirectory directory,
    Messages messages,
    LobbyConfig config,
    LobbyDataGate dataGate,
    LobbyMenuController menus) {

  public LobbyCommandContext {
    Preconditions.checkNotNull(plugin, "plugin");
    Preconditions.checkNotNull(matchmaking, "matchmaking");
    Preconditions.checkNotNull(directory, "directory");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(dataGate, "dataGate");
    Preconditions.checkNotNull(menus, "menus");
  }
}
