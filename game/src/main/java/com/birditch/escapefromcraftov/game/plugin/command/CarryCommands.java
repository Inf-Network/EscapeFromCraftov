package com.birditch.escapefromcraftov.game.plugin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;

/** Player-facing `/craftov carry` entry point for the in-raid grid organizer. */
final class CarryCommands {

  private CarryCommands() {}

  static LiteralArgumentBuilder<CommandSourceStack> carry(CommandContext context) {
    return Commands.literal("carry")
        .requires(source -> source.getSender() instanceof Player)
        .executes(command -> {
          context.carryMenus().open((Player) command.getSource().getSender());
          return Command.SINGLE_SUCCESS;
        });
  }
}
