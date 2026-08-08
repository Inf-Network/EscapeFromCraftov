package com.birditch.raidcore.paper.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

/// Builds the `/raid` Brigadier tree; bare `/raid` shows the help listing.
public final class RaidCommand {

  public static final String PERMISSION_ADMIN = "raid.admin";
  public static final String PERMISSION_INFO = "raid.command.info";

  private RaidCommand() {}

  public static LiteralCommandNode<CommandSourceStack> build(CommandContext context) {
    return Commands.literal("raid")
        .executes(ctx -> InfoCommands.showHelp(context, ctx.getSource().getSender()))
        .then(InfoCommands.help(context))
        .then(InfoCommands.info(context))
        .then(ProfileCommands.profile(context))
        .then(GameCommands.game(context))
        .then(DebugCommands.debug(context))
        .build();
  }
}
