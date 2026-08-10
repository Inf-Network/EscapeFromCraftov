package com.birditch.raidcore.lobby.listener;

import com.birditch.raidcore.api.cosmetics.CosmeticsService;
import com.birditch.raidcore.api.matchmaking.MatchmakingService;
import com.birditch.raidcore.api.inventory.PlayerInventoryService;
import com.birditch.raidcore.api.profile.ProfileService;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.api.settings.PlayerSettingsService;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.lobby.LobbyDataGate;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/// Collaborators of [LobbyConnectionListener]. `menuOpener` opens the main
/// lobby menu for a player, keeping the listener decoupled from the menu
/// implementation.
public record ConnectionDeps(
    Plugin plugin,
    RaidScheduler scheduler,
    Messages messages,
    ProfileService profiles,
    PlayerSettingsService settings,
    CosmeticsService cosmetics,
    PlayerInventoryService inventories,
    MatchmakingService matchmaking,
    LobbyDataGate dataGate,
    Consumer<Player> menuOpener,
    Logger logger) {

  public ConnectionDeps {
    Preconditions.checkNotNull(plugin, "plugin");
    Preconditions.checkNotNull(scheduler, "scheduler");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(profiles, "profiles");
    Preconditions.checkNotNull(settings, "settings");
    Preconditions.checkNotNull(cosmetics, "cosmetics");
    Preconditions.checkNotNull(inventories, "inventories");
    Preconditions.checkNotNull(matchmaking, "matchmaking");
    Preconditions.checkNotNull(dataGate, "dataGate");
    Preconditions.checkNotNull(menuOpener, "menuOpener");
    Preconditions.checkNotNull(logger, "logger");
  }
}
