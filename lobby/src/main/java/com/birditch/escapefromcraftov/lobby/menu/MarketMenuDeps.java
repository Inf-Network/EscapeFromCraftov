package com.birditch.escapefromcraftov.lobby.menu;

import com.birditch.escapefromcraftov.api.inventory.PlayerInventoryService;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.matchmaking.MatchmakingService;
import com.birditch.escapefromcraftov.api.profile.ProfileService;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.lobby.LobbyDataGate;
import com.birditch.escapefromcraftov.market.PlayerMarketService;
import java.util.logging.Logger;

public record MarketMenuDeps(
    PlayerMarketService market,
    MatchmakingService matchmaking,
    PlayerInventoryService inventories,
    ProfileService profiles,
    ItemRegistry itemRegistry,
    CraftovItemStacks itemStacks,
    CraftovScheduler scheduler,
    LobbyDataGate dataGate,
    Messages messages,
    Logger logger) {

  public MarketMenuDeps {
    Preconditions.checkNotNull(market, "market");
    Preconditions.checkNotNull(matchmaking, "matchmaking");
    Preconditions.checkNotNull(inventories, "inventories");
    Preconditions.checkNotNull(profiles, "profiles");
    Preconditions.checkNotNull(itemRegistry, "itemRegistry");
    Preconditions.checkNotNull(itemStacks, "itemStacks");
    Preconditions.checkNotNull(scheduler, "scheduler");
    Preconditions.checkNotNull(dataGate, "dataGate");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(logger, "logger");
  }
}
