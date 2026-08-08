package com.birditch.raidcore.paper.command;

import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.common.result.Result;
import com.birditch.raidcore.paper.Messages;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Collection;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

/// `/raid game create|list|start|stop` — admin-only game management.
final class GameCommands {

  private static final String DEFAULT_MAP = "debug_zone";

  private GameCommands() {}

  static LiteralArgumentBuilder<CommandSourceStack> game(CommandContext context) {
    return Commands.literal("game")
        .requires(source -> source.getSender().hasPermission(RaidCommand.PERMISSION_ADMIN))
        .then(Commands.literal("create")
            .executes(ctx -> create(context, ctx.getSource().getSender(), DEFAULT_MAP))
            .then(Commands.argument("map", StringArgumentType.word())
                .executes(ctx -> create(
                    context, ctx.getSource().getSender(),
                    StringArgumentType.getString(ctx, "map")))))
        .then(Commands.literal("list")
            .executes(ctx -> list(context, ctx.getSource().getSender())))
        .then(Commands.literal("start")
            .then(gameIdArgument(context)
                .executes(ctx -> start(
                    context, ctx.getSource().getSender(),
                    StringArgumentType.getString(ctx, "id")))))
        .then(Commands.literal("stop")
            .then(gameIdArgument(context)
                .executes(ctx -> stop(
                    context, ctx.getSource().getSender(),
                    StringArgumentType.getString(ctx, "id")))));
  }

  private static RequiredArgumentBuilder<CommandSourceStack, String> gameIdArgument(
      CommandContext context) {
    return Commands.argument("id", StringArgumentType.word())
        .suggests((_, builder) -> {
          for (RaidGame game : context.services().require(RaidGameService.class).all()) {
            builder.suggest(game.id().value());
          }
          return builder.buildFuture();
        });
  }

  private static int create(CommandContext context, CommandSender sender, String map) {
    Messages messages = context.messages();
    RaidMapId mapId;
    try {
      mapId = RaidMapId.of(map);
    } catch (IllegalArgumentException e) {
      sender.sendMessage(messages.msg(
          "command.game.error", Placeholder.unparsed("error", reason(e))));
      return Command.SINGLE_SUCCESS;
    }
    RaidGame game = context.services().require(RaidGameService.class).create(mapId);
    sender.sendMessage(messages.msg(
        "command.game.created",
        Placeholder.unparsed("id", game.id().value()),
        Placeholder.unparsed("map", game.mapId().value())));
    return Command.SINGLE_SUCCESS;
  }

  private static int list(CommandContext context, CommandSender sender) {
    Messages messages = context.messages();
    Collection<RaidGame> games = context.services().require(RaidGameService.class).all();
    if (games.isEmpty()) {
      sender.sendMessage(messages.msg("command.game.list-empty"));
      return Command.SINGLE_SUCCESS;
    }
    sender.sendMessage(messages.msg(
        "command.game.list-header",
        Placeholder.unparsed("count", Integer.toString(games.size()))));
    for (RaidGame game : games) {
      sender.sendMessage(messages.raw(
          "command.game.list-entry",
          Placeholder.unparsed("id", game.id().value()),
          Placeholder.unparsed("state", game.state().name()),
          Placeholder.unparsed("players", Integer.toString(game.playerCount()))));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int start(CommandContext context, CommandSender sender, String rawId) {
    RaidGameService games = context.services().require(RaidGameService.class);
    Optional<RaidGameId> gameId = knownGameId(games, rawId);
    if (gameId.isEmpty()) {
      sender.sendMessage(context.messages().msg(
          "command.game.not-found", Placeholder.unparsed("id", rawId)));
      return Command.SINGLE_SUCCESS;
    }
    sendResult(context, sender, games.start(gameId.get()), "command.game.started");
    return Command.SINGLE_SUCCESS;
  }

  private static int stop(CommandContext context, CommandSender sender, String rawId) {
    RaidGameService games = context.services().require(RaidGameService.class);
    Optional<RaidGameId> gameId = knownGameId(games, rawId);
    if (gameId.isEmpty()) {
      sender.sendMessage(context.messages().msg(
          "command.game.not-found", Placeholder.unparsed("id", rawId)));
      return Command.SINGLE_SUCCESS;
    }
    sendResult(context, sender, games.stop(gameId.get()), "command.game.stopped");
    return Command.SINGLE_SUCCESS;
  }

  /// Returns the id only when it is well-formed and refers to an existing game.
  private static Optional<RaidGameId> knownGameId(RaidGameService games, String rawId) {
    try {
      return Optional.of(RaidGameId.of(rawId)).filter(id -> games.find(id).isPresent());
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }

  private static void sendResult(
      CommandContext context, CommandSender sender, Result<RaidGame> result, String successKey) {
    switch (result) {
      case Result.Success<RaidGame>(RaidGame game) -> sender.sendMessage(context.messages().msg(
          successKey, Placeholder.unparsed("id", game.id().value())));
      case Result.Failure<RaidGame>(String message) -> sender.sendMessage(context.messages().msg(
          "command.game.error", Placeholder.unparsed("error", message)));
    }
  }

  private static String reason(IllegalArgumentException e) {
    String message = e.getMessage();
    return message != null ? message : "Invalid input";
  }
}
