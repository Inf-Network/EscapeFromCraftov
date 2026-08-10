package com.birditch.raidcore.game.plugin.command;

import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.config.RaidCoreConfig;
import com.birditch.raidcore.core.module.ModuleManager;
import com.birditch.raidcore.core.service.ServiceRegistry;
import com.birditch.raidcore.game.plugin.RaidGamePlugin;
import com.birditch.raidcore.game.plugin.GameServerConfig;
import com.birditch.raidcore.api.scheduler.RaidScheduler;
import com.birditch.raidcore.game.plugin.editor.EditorConfirmationService;
import com.birditch.raidcore.game.plugin.editor.EditorInteractionService;
import com.birditch.raidcore.game.plugin.editor.EditorObjectMenuController;
import com.birditch.raidcore.game.plugin.editor.EditorSelectionService;
import com.birditch.raidcore.game.plugin.editor.EditorVisualizationService;
import com.birditch.raidcore.game.plugin.editor.EditorWandListener;
import com.birditch.raidcore.game.plugin.editor.ManualMapRegistry;
import com.birditch.raidcore.game.plugin.editor.ManualMapService;
import com.birditch.raidcore.game.plugin.editor.MapEditorSettings;
import com.birditch.raidcore.game.plugin.integration.PermissionProvider;
import com.birditch.raidcore.game.plugin.integration.SelectionProvider;
import com.birditch.raidcore.game.plugin.loot.RaidLootListener;
import com.birditch.raidcore.map.EditorSessionService;
import com.birditch.raidcore.bukkit.TrMenuBridge;

/// Everything the `/raid` command tree needs at execution time.
public record CommandContext(
    RaidGamePlugin plugin,
    ServiceRegistry services,
    ModuleManager modules,
    Messages messages,
    RaidCoreConfig config,
    GameServerConfig serverConfig,
    RaidScheduler scheduler,
    ManualMapService manualMapService,
    ManualMapRegistry maps,
    EditorSessionService editorSessions,
    EditorSelectionService selections,
    EditorWandListener wand,
    EditorConfirmationService confirmations,
    EditorObjectMenuController objectMenus,
    EditorVisualizationService editorVisualization,
    EditorInteractionService editorInteraction,
    RaidLootListener lootMenus,
    TrMenuBridge trMenu,
    MapEditorSettings editorSettings,
    SelectionProvider selectionProvider,
    PermissionProvider permissionProvider) {

  public CommandContext {
    Preconditions.checkNotNull(plugin, "plugin");
    Preconditions.checkNotNull(services, "services");
    Preconditions.checkNotNull(modules, "modules");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkNotNull(serverConfig, "serverConfig");
    Preconditions.checkNotNull(scheduler, "scheduler");
    Preconditions.checkNotNull(manualMapService, "manualMapService");
    Preconditions.checkNotNull(maps, "maps");
    Preconditions.checkNotNull(editorSessions, "editorSessions");
    Preconditions.checkNotNull(selections, "selections");
    Preconditions.checkNotNull(wand, "wand");
    Preconditions.checkNotNull(confirmations, "confirmations");
    Preconditions.checkNotNull(objectMenus, "objectMenus");
    Preconditions.checkNotNull(editorVisualization, "editorVisualization");
    Preconditions.checkNotNull(editorInteraction, "editorInteraction");
    Preconditions.checkNotNull(lootMenus, "lootMenus");
    Preconditions.checkNotNull(trMenu, "trMenu");
    Preconditions.checkNotNull(editorSettings, "editorSettings");
    Preconditions.checkNotNull(selectionProvider, "selectionProvider");
    Preconditions.checkNotNull(permissionProvider, "permissionProvider");
  }
}
