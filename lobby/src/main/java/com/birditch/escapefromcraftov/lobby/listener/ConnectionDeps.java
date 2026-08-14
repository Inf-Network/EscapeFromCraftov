package com.birditch.escapefromcraftov.lobby.listener;

import com.birditch.escapefromcraftov.api.cosmetics.CosmeticsService;
import com.birditch.escapefromcraftov.api.matchmaking.MatchmakingService;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventoryService;
import com.birditch.escapefromcraftov.api.profile.ProfileService;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.settings.PlayerSettingsService;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.lobby.LobbyDataGate;
import com.birditch.escapefromcraftov.profile.StarterKitService;
import com.birditch.escapefromcraftov.lobby.party.PartyService;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/// Collaborators of [LobbyConnectionListener]. `menuOpener` opens the main
/// lobby menu for a player, keeping the listener decoupled from the menu
/// implementation.
public record ConnectionDeps(
    Plugin plugin,
    CraftovScheduler scheduler,
    Messages messages,
    ProfileService profiles,
    StarterKitService starterKit,
    PlayerSettingsService settings,
    CosmeticsService cosmetics,
    PlayerInventoryService inventories,
    MatchmakingService matchmaking,
    PartyService parties,
    LobbyDataGate dataGate,
    Consumer<Player> menuOpener,
    Logger logger) {

  public ConnectionDeps {
    Preconditions.checkNotNull(plugin, "plugin");
    Preconditions.checkNotNull(scheduler, "scheduler");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(profiles, "profiles");
    Preconditions.checkNotNull(starterKit, "starterKit");
    Preconditions.checkNotNull(settings, "settings");
    Preconditions.checkNotNull(cosmetics, "cosmetics");
    Preconditions.checkNotNull(inventories, "inventories");
    Preconditions.checkNotNull(matchmaking, "matchmaking");
    Preconditions.checkNotNull(parties, "parties");
    Preconditions.checkNotNull(dataGate, "dataGate");
    Preconditions.checkNotNull(menuOpener, "menuOpener");
    Preconditions.checkNotNull(logger, "logger");
  }
}
