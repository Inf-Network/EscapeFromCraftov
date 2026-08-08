package com.birditch.raidcore.lobby.menu;

import com.birditch.raidcore.api.cosmetics.CosmeticsService;
import com.birditch.raidcore.api.matchmaking.MatchmakingService;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.api.settings.PlayerSettingsService;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.lobby.LobbyConfig;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/// Collaborators of [MenuListener].
public record MenuDeps(
    PlayerSettingsService settings,
    CosmeticsService cosmetics,
    MatchmakingService matchmaking,
    RaidScheduler scheduler,
    LobbyConfig config,
    Messages messages,
    Plugin plugin,
    Logger logger) {

  public MenuDeps {
    Preconditions.checkNotNull(settings, "settings");
    Preconditions.checkNotNull(cosmetics, "cosmetics");
    Preconditions.checkNotNull(matchmaking, "matchmaking");
    Preconditions.checkNotNull(scheduler, "scheduler");
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(plugin, "plugin");
    Preconditions.checkNotNull(logger, "logger");
  }
}
