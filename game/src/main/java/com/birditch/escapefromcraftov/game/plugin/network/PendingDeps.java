package com.birditch.escapefromcraftov.game.plugin.network;

import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventoryService;
import com.birditch.escapefromcraftov.api.network.AssignmentStore;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.bukkit.BungeeConnector;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.bukkit.TrMenuBridge;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.GameServerConfig;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import com.birditch.escapefromcraftov.game.plugin.isolation.VisibilityService;
import com.birditch.escapefromcraftov.game.plugin.editor.EditorSelectionService;
import com.birditch.escapefromcraftov.map.EditorSessionService;
import java.util.logging.Logger;

/// Constructor dependencies of [PendingJoinHandler], bundled so the handler
/// keeps a single-parameter constructor.
public record PendingDeps(AssignmentStore store, CraftovGameService games, GameServerConfig config,
                          InstanceWorldIndex index, BungeeConnector bungee,
                          PlayerInventoryService inventories, CraftovItemStacks itemStacks,
                          TrMenuBridge menus, VisibilityService visibility,
                          EditorSessionService editorSessions,
                          EditorSelectionService editorSelections,
                          CraftovScheduler scheduler, Messages messages,
                          Logger logger) {

  public PendingDeps {
    Preconditions.checkNotNull(store, "store");
    Preconditions.checkNotNull(games, "games");
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(index, "index");
    Preconditions.checkNotNull(bungee, "bungee");
    Preconditions.checkNotNull(inventories, "inventories");
    Preconditions.checkNotNull(itemStacks, "itemStacks");
    Preconditions.checkNotNull(menus, "menus");
    Preconditions.checkNotNull(visibility, "visibility");
    Preconditions.checkNotNull(editorSessions, "editorSessions");
    Preconditions.checkNotNull(editorSelections, "editorSelections");
    Preconditions.checkNotNull(scheduler, "scheduler");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(logger, "logger");
  }
}
