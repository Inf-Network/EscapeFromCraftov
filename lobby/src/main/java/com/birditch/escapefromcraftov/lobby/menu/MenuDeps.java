package com.birditch.escapefromcraftov.lobby.menu;

import com.birditch.escapefromcraftov.api.cosmetics.CosmeticsService;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventoryService;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.matchmaking.MatchmakingService;
import com.birditch.escapefromcraftov.api.profile.ProfileService;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.settings.PlayerSettingsService;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.lobby.LobbyConfig;
import com.birditch.escapefromcraftov.lobby.LobbyDataGate;
import com.birditch.escapefromcraftov.vendor.MilitaryVendorService;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/// Persistence and gameplay collaborators used by [LobbyMenuController].
public record MenuDeps(
    PlayerSettingsService settings,
    CosmeticsService cosmetics,
    PlayerInventoryService inventories,
    ProfileService profiles,
    MilitaryVendorService vendor,
    ItemRegistry itemRegistry,
    CraftovItemStacks itemStacks,
    MatchmakingService matchmaking,
    CraftovScheduler scheduler,
    LobbyConfig config,
    LobbyDataGate dataGate,
    Messages messages,
    Plugin plugin,
    Logger logger) {

  public MenuDeps {
    Preconditions.checkNotNull(settings, "settings");
    Preconditions.checkNotNull(cosmetics, "cosmetics");
    Preconditions.checkNotNull(inventories, "inventories");
    Preconditions.checkNotNull(profiles, "profiles");
    Preconditions.checkNotNull(vendor, "vendor");
    Preconditions.checkNotNull(itemRegistry, "itemRegistry");
    Preconditions.checkNotNull(itemStacks, "itemStacks");
    Preconditions.checkNotNull(matchmaking, "matchmaking");
    Preconditions.checkNotNull(scheduler, "scheduler");
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(dataGate, "dataGate");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(plugin, "plugin");
    Preconditions.checkNotNull(logger, "logger");
  }
}
