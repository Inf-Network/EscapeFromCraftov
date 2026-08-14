package com.birditch.escapefromcraftov.game.plugin.command;

import com.birditch.escapefromcraftov.api.profile.ProfileService;
import com.birditch.escapefromcraftov.api.profile.ProgressionCurve;
import com.birditch.escapefromcraftov.api.session.PlayerSession;
import com.birditch.escapefromcraftov.api.session.ProfileLoadState;
import com.birditch.escapefromcraftov.api.session.SessionService;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.core.service.ServiceRegistry;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/// `/craftov profile` — player-only view of the caller's own profile.
final class ProfileCommands {

  private ProfileCommands() {}

  static LiteralArgumentBuilder<CommandSourceStack> profile(CommandContext context) {
    return Commands.literal("profile")
        .requires(source -> source.getSender().hasPermission(CraftovCommand.PERMISSION_INFO))
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
        "command.profile.state", Placeholder.component("state",
            messages.raw("state.profile." + state.name().toLowerCase(Locale.ROOT)))));
    services.require(ProfileService.class).find(playerId).ifPresent(profile -> {
      long levelStart = ProgressionCurve.experienceForLevel(profile.level());
      long levelProgress = profile.experience() - levelStart;
      long levelTarget = profile.level() == ProgressionCurve.MAX_LEVEL
          ? levelProgress
          : ProgressionCurve.experienceForLevel(profile.level() + 1) - levelStart;
      sender.sendMessage(messages.raw(
          "command.profile.balance",
          Placeholder.unparsed("balance", profile.balance().format())));
      sender.sendMessage(messages.raw(
          "command.profile.level",
          Placeholder.unparsed("level", Integer.toString(profile.level()))));
      sender.sendMessage(messages.raw(
          "command.profile.experience",
          Placeholder.unparsed("experience", Long.toString(profile.experience()))));
      sender.sendMessage(messages.raw(
          "command.profile.level-progress",
          Placeholder.unparsed("progress", Long.toString(levelProgress)),
          Placeholder.unparsed("target", Long.toString(levelTarget))));
      sender.sendMessage(messages.raw(
          "command.profile.next-level",
          Placeholder.unparsed("remaining",
              Long.toString(ProgressionCurve.experienceToNextLevel(profile.experience())))));
    });
    return Command.SINGLE_SUCCESS;
  }
}
