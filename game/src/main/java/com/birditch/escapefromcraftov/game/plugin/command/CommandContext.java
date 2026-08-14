package com.birditch.escapefromcraftov.game.plugin.command;

import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.core.config.EscapeFromCraftovConfig;
import com.birditch.escapefromcraftov.core.module.ModuleManager;
import com.birditch.escapefromcraftov.core.service.ServiceRegistry;
import com.birditch.escapefromcraftov.game.plugin.CraftovGamePlugin;
import com.birditch.escapefromcraftov.game.plugin.GameServerConfig;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.game.plugin.editor.EditorConfirmationService;
import com.birditch.escapefromcraftov.game.plugin.editor.EditorInteractionService;
import com.birditch.escapefromcraftov.game.plugin.editor.EditorObjectMenuController;
import com.birditch.escapefromcraftov.game.plugin.editor.EditorSelectionService;
import com.birditch.escapefromcraftov.game.plugin.editor.EditorVisualizationService;
import com.birditch.escapefromcraftov.game.plugin.editor.EditorWandListener;
import com.birditch.escapefromcraftov.game.plugin.editor.ManualMapRegistry;
import com.birditch.escapefromcraftov.game.plugin.editor.ManualMapService;
import com.birditch.escapefromcraftov.game.plugin.editor.MapEditorSettings;
import com.birditch.escapefromcraftov.game.plugin.integration.PermissionProvider;
import com.birditch.escapefromcraftov.game.plugin.integration.SelectionProvider;
import com.birditch.escapefromcraftov.game.plugin.loot.CraftovLootListener;
import com.birditch.escapefromcraftov.game.plugin.secure.SecureContainerController;
import com.birditch.escapefromcraftov.map.EditorSessionService;
import com.birditch.escapefromcraftov.bukkit.TrMenuBridge;

/// Everything the `/craftov` command tree needs at execution time.
public record CommandContext(
    CraftovGamePlugin plugin,
    ServiceRegistry services,
    ModuleManager modules,
    Messages messages,
    EscapeFromCraftovConfig config,
    GameServerConfig serverConfig,
    CraftovScheduler scheduler,
    ManualMapService manualMapService,
    ManualMapRegistry maps,
    EditorSessionService editorSessions,
    EditorSelectionService selections,
    EditorWandListener wand,
    EditorConfirmationService confirmations,
    EditorObjectMenuController objectMenus,
    EditorVisualizationService editorVisualization,
    EditorInteractionService editorInteraction,
    CraftovLootListener lootMenus,
    SecureContainerController secureContainers,
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
    Preconditions.checkNotNull(secureContainers, "secureContainers");
    Preconditions.checkNotNull(trMenu, "trMenu");
    Preconditions.checkNotNull(editorSettings, "editorSettings");
    Preconditions.checkNotNull(selectionProvider, "selectionProvider");
    Preconditions.checkNotNull(permissionProvider, "permissionProvider");
  }
}
