package com.birditch.raidcore.game.plugin.command;

import com.birditch.raidcore.api.profile.ProfileService;
import com.birditch.raidcore.api.session.PlayerSession;
import com.birditch.raidcore.api.session.ProfileLoadState;
import com.birditch.raidcore.api.session.SessionService;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.core.service.ServiceRegistry;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/// `/raid profile` — player-only view of the caller's own profile.
final class ProfileCommands {

  private ProfileCommands() {}

  static LiteralArgumentBuilder<CommandSourceStack> profile(CommandContext context) {
    return Commands.literal("profile")
        .requires(source -> source.getSender().hasPermission(RaidCommand.PERMISSION_INFO))
        .executes(ctx -> show(context, ctx.getSource().getSender()));
  }

  private static int show(CommandContext context, CommandSender sender) {
    Messages messages = context.messages();
    if (!(sender instanceof Player player)) {
      sender.sendMessage(messages.raw("command.player-only"));
      return Command.SINGLE_SUCCESS;
    }
    ServiceRegistry services = context.services();
    UUID playerId = player.getUniqueId();
    ProfileLoadState state = services.require(SessionService.class).find(playerId)
        .map(PlayerSession::profileState)
        .orElse(ProfileLoadState.FAILED);
    sender.sendMessage(messages.raw(
        "command.profile.header", Placeholder.unparsed("player", player.getName())));
    sender.sendMessage(messages.raw(
        "command.profile.state", Placeholder.unparsed("state", state.name())));
    services.require(ProfileService.class).find(playerId).ifPresent(profile -> {
      sender.sendMessage(messages.raw(
          "command.profile.balance",
          Placeholder.unparsed("balance", profile.balance().format())));
      sender.sendMessage(messages.raw(
          "command.profile.level",
          Placeholder.unparsed("level", Integer.toString(profile.level()))));
    });
    return Command.SINGLE_SUCCESS;
  }
}
