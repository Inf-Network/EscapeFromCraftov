package com.birditch.raidcore.lobby.command;

import com.birditch.raidcore.api.RaidPermissions;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.lobby.PlayAction;
import com.birditch.raidcore.lobby.menu.LobbyMenus;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/// Brigadier roots for the lobby: `/play [map]`, `/leavequeue`, `/lobby` and
/// its `/menu` alias.
public final class LobbyCommand {

  private static final String PLAY_PERMISSION = RaidPermissions.LOBBY_PLAY;

  private LobbyCommand() {}

  public static List<LiteralCommandNode<CommandSourceStack>> build(LobbyCommandContext context) {
    Preconditions.checkNotNull(context, "context");
    return List.of(playRoot(context), leaveQueueRoot(context),
        menuRoot(context, "lobby"), menuRoot(context, "menu"));
  }

  private static LiteralCommandNode<CommandSourceStack> playRoot(LobbyCommandContext context) {
    return Commands.literal("play")
        .requires(source -> source.getSender().hasPermission(PLAY_PERMISSION))
        .executes(ctx -> play(context, ctx, null))
        .then(Commands.argument("map", StringArgumentType.word())
            .suggests((_, builder) -> {
              builder.suggest(context.config().defaultMap().value());
              return builder.buildFuture();
            })
            .executes(ctx -> play(context, ctx, StringArgumentType.getString(ctx, "map"))))
        .build();
  }

  private static LiteralCommandNode<CommandSourceStack> leaveQueueRoot(LobbyCommandContext context) {
    return Commands.literal("leavequeue")
        .requires(source -> source.getSender().hasPermission(PLAY_PERMISSION))
        .executes(ctx -> {
          Player player = playerOrNull(ctx, context.messages());
          if (player == null) {
            return Command.SINGLE_SUCCESS;
          }
          boolean removed = context.matchmaking().dequeue(player.getUniqueId());
          player.sendMessage(context.messages().raw(removed ? "queue.left" : "queue.not-queued"));
          return Command.SINGLE_SUCCESS;
        })
        .build();
  }

  private static LiteralCommandNode<CommandSourceStack> menuRoot(LobbyCommandContext context,
      String literal) {
    return Commands.literal(literal)
        .executes(ctx -> {
          Player player = playerOrNull(ctx, context.messages());
          if (player != null) {
            player.openInventory(LobbyMenus.MainMenu.build(context.messages()));
          }
          return Command.SINGLE_SUCCESS;
        })
        .build();
  }

  private static int play(LobbyCommandContext context, CommandContext<CommandSourceStack> ctx,
      @Nullable String rawMap) {
    Player player = playerOrNull(ctx, context.messages());
    if (player == null) {
      return Command.SINGLE_SUCCESS;
    }
    RaidMapId mapId;
    if (rawMap == null) {
      mapId = context.config().defaultMap();
    } else {
      try {
        mapId = RaidMapId.of(rawMap);
      } catch (IllegalArgumentException _) {
        player.sendMessage(context.messages().raw("command.invalid-map",
            Placeholder.unparsed("map", rawMap)));
        return Command.SINGLE_SUCCESS;
      }
    }
    PlayAction.queue(context.matchmaking(), context.messages(), player, mapId);
    return Command.SINGLE_SUCCESS;
  }

  private static @Nullable Player playerOrNull(CommandContext<CommandSourceStack> ctx,
      Messages messages) {
    CommandSender sender = ctx.getSource().getSender();
    if (sender instanceof Player player) {
      return player;
    }
    sender.sendMessage(messages.raw("command.player-only"));
    return null;
  }
}
