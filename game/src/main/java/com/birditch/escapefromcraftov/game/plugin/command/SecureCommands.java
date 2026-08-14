package com.birditch.escapefromcraftov.game.plugin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;

/** Player-facing `/craftov secure` entry point for the in-raid secure container. */
final class SecureCommands {

  private SecureCommands() {}

  static LiteralArgumentBuilder<CommandSourceStack> secure(CommandContext context) {
    return Commands.literal("secure")
        .requires(source -> source.getSender() instanceof Player)
        .executes(command -> {
          context.secureContainers().open((Player) command.getSource().getSender());
          return Command.SINGLE_SUCCESS;
        });
  }
}
