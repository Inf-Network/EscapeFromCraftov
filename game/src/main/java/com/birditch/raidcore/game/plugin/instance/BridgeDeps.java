package com.birditch.raidcore.game.plugin.instance;

import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.event.EventBus;
import com.birditch.raidcore.api.extraction.ExtractionService;
import com.birditch.raidcore.api.inventory.PlayerInventoryService;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.api.profile.ProfileService;
import com.birditch.raidcore.api.profile.ProgressionService;
import com.birditch.raidcore.bukkit.BungeeConnector;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.bukkit.RaidItemStacks;
import com.birditch.raidcore.bukkit.TrMenuBridge;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.game.plugin.GameServerConfig;
import com.birditch.raidcore.game.plugin.isolation.VisibilityService;
import com.birditch.raidcore.game.plugin.world.WorldService;
import java.util.logging.Logger;

/// Constructor dependencies of [InstanceWorldBridge], bundled so the bridge
/// keeps a single-parameter constructor.
public record BridgeDeps(
    RaidGameService games,
    ProfileService profiles,
    ProgressionService progression,
    PlayerInventoryService inventories,
    ExtractionService extractions,
    RaidItemStacks itemStacks,
    EventBus eventBus,
    WorldService worlds,
    InstanceWorldIndex index,
    VisibilityService visibility,
    BungeeConnector bungee,
    GameServerConfig config,
    Messages messages,
    TrMenuBridge menus,
    RaidScheduler scheduler,
    int extractionExperience,
    Logger logger) {

  public BridgeDeps {
    Preconditions.checkNotNull(games, "games");
    Preconditions.checkNotNull(profiles, "profiles");
    Preconditions.checkNotNull(progression, "progression");
    Preconditions.checkNotNull(inventories, "inventories");
    Preconditions.checkNotNull(extractions, "extractions");
    Preconditions.checkNotNull(itemStacks, "itemStacks");
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
