package com.birditch.escapefromcraftov.game.plugin.command;

import com.birditch.escapefromcraftov.api.CraftovPermissions;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

/// Builds the `/craftov` Brigadier tree; bare `/craftov` shows the help listing.
public final class CraftovCommand {

  public static final String PERMISSION_ADMIN = CraftovPermissions.ADMIN;
  public static final String PERMISSION_INFO = CraftovPermissions.COMMAND_INFO;

  private CraftovCommand() {}

  public static LiteralCommandNode<CommandSourceStack> build(CommandContext context) {
    return Commands.literal("craftov")
        .executes(ctx -> InfoCommands.showHelp(context, ctx.getSource().getSender()))
        .then(InfoCommands.help(context))
        .then(InfoCommands.tutorial(context))
        .then(InfoCommands.info(context))
        .then(ProfileCommands.profile(context))
        .then(SecureCommands.secure(context))
        .then(ManualMapCommands.map(context))
        .then(EditorCommands.editor(context))
        .then(UiCommands.ui(context))
        .then(GameCommands.game(context))
        .then(DebugCommands.debug(context))
        .build();
  }
}
