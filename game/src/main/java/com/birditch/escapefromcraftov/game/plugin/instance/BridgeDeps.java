package com.birditch.escapefromcraftov.game.plugin.instance;

import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.event.EventBus;
import com.birditch.escapefromcraftov.api.extraction.ExtractionService;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventoryService;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.profile.ProfileService;
import com.birditch.escapefromcraftov.api.profile.ProgressionService;
import com.birditch.escapefromcraftov.bukkit.BungeeConnector;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.bukkit.TrMenuBridge;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.GameServerConfig;
import com.birditch.escapefromcraftov.game.plugin.carry.RaidCarryController;
import com.birditch.escapefromcraftov.game.plugin.isolation.VisibilityService;
import com.birditch.escapefromcraftov.game.plugin.secure.SecureContainerController;
import com.birditch.escapefromcraftov.game.plugin.report.RaidResultRuntime;
import com.birditch.escapefromcraftov.game.plugin.world.WorldService;
import java.util.logging.Logger;

/// Constructor dependencies of [InstanceWorldBridge], bundled so the bridge
/// keeps a single-parameter constructor.
public record BridgeDeps(
    CraftovGameService games,
    ProfileService profiles,
    ProgressionService progression,
    PlayerInventoryService inventories,
    ExtractionService extractions,
    CraftovItemStacks itemStacks,
    RaidCarryController carry,
    SecureContainerController secureContainers,
    RaidResultRuntime raidReports,
    EventBus eventBus,
    WorldService worlds,
    InstanceWorldIndex index,
    VisibilityService visibility,
    BungeeConnector bungee,
    GameServerConfig config,
    Messages messages,
    TrMenuBridge menus,
    CraftovScheduler scheduler,
    int extractionExperience,
    Logger logger) {

  public BridgeDeps {
    Preconditions.checkNotNull(games, "games");
    Preconditions.checkNotNull(profiles, "profiles");
    Preconditions.checkNotNull(progression, "progression");
    Preconditions.checkNotNull(inventories, "inventories");
    Preconditions.checkNotNull(extractions, "extractions");
    Preconditions.checkNotNull(itemStacks, "itemStacks");
    Preconditions.checkNotNull(carry, "carry");
    Preconditions.checkNotNull(secureContainers, "secureContainers");
    Preconditions.checkNotNull(raidReports, "raidReports");
    Preconditions.checkNotNull(eventBus, "eventBus");
    Preconditions.checkNotNull(worlds, "worlds");
    Preconditions.checkNotNull(index, "index");
    Preconditions.checkNotNull(visibility, "visibility");
    Preconditions.checkNotNull(bungee, "bungee");
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(menus, "menus");
    Preconditions.checkNotNull(scheduler, "scheduler");
    Preconditions.checkArgument(extractionExperience > 0, "extractionExperience must be positive");
    Preconditions.checkNotNull(logger, "logger");
  }
}
