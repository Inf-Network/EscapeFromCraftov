package com.birditch.raidcore.game.plugin.command;

import com.birditch.raidcore.api.RaidPermissions;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

/// Builds the `/raid` Brigadier tree; bare `/raid` shows the help listing.
public final class RaidCommand {

  public static final String PERMISSION_ADMIN = RaidPermissions.ADMIN;
  public static final String PERMISSION_INFO = RaidPermissions.COMMAND_INFO;

  private RaidCommand() {}

  public static LiteralCommandNode<CommandSourceStack> build(CommandContext context) {
    return Commands.literal("raid")
        .executes(ctx -> InfoCommands.showHelp(context, ctx.getSource().getSender()))
        .then(InfoCommands.help(context))
        .then(InfoCommands.tutorial(context))
        .then(InfoCommands.info(context))
        .then(ProfileCommands.profile(context))
        .then(ManualMapCommands.map(context))
        .then(EditorCommands.editor(context))
        .then(UiCommands.ui(context))
        .then(GameCommands.game(context))
        .then(DebugCommands.debug(context))
        .build();
  }
}
