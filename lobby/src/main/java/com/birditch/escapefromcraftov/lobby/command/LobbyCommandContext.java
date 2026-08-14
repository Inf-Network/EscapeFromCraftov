package com.birditch.escapefromcraftov.lobby.command;

import com.birditch.escapefromcraftov.api.matchmaking.MatchmakingService;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventoryService;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.network.InstanceDirectory;
import com.birditch.escapefromcraftov.api.profile.ProfileService;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.lobby.LobbyConfig;
import com.birditch.escapefromcraftov.lobby.LobbyDataGate;
import com.birditch.escapefromcraftov.lobby.CraftovLobbyPlugin;
import com.birditch.escapefromcraftov.lobby.menu.LobbyMenuController;
import com.birditch.escapefromcraftov.lobby.menu.MarketMenuController;
import com.birditch.escapefromcraftov.lobby.menu.RaidPreparationController;
import com.birditch.escapefromcraftov.lobby.party.PartyService;
import com.birditch.escapefromcraftov.lobby.report.LobbyRaidReports;

/// Collaborators shared by every lobby command node.
public record LobbyCommandContext(
    CraftovLobbyPlugin plugin,
    MatchmakingService matchmaking,
    InstanceDirectory directory,
    Messages messages,
    LobbyConfig config,
    LobbyDataGate dataGate,
    LobbyMenuController menus,
    MarketMenuController marketMenus,
    RaidPreparationController raidPreparation,
    LobbyRaidReports raidReports,
    PartyService parties,
    PlayerInventoryService inventories,
    ProfileService profiles,
    ItemRegistry itemRegistry) {

  public LobbyCommandContext {
    Preconditions.checkNotNull(plugin, "plugin");
    Preconditions.checkNotNull(matchmaking, "matchmaking");
    Preconditions.checkNotNull(directory, "directory");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(dataGate, "dataGate");
    Preconditions.checkNotNull(menus, "menus");
    Preconditions.checkNotNull(marketMenus, "marketMenus");
    Preconditions.checkNotNull(raidPreparation, "raidPreparation");
    Preconditions.checkNotNull(raidReports, "raidReports");
    Preconditions.checkNotNull(parties, "parties");
    Preconditions.checkNotNull(inventories, "inventories");
    Preconditions.checkNotNull(profiles, "profiles");
    Preconditions.checkNotNull(itemRegistry, "itemRegistry");
  }
}
