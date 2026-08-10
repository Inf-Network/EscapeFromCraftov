package com.birditch.raidcore.lobby.command;

import com.birditch.raidcore.api.RaidPermissions;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.network.GameServerSnapshot;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.lobby.PlayAction;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
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
            .suggests((_, builder) -> context.directory().activeServers()
                .completeOnTimeout(List.of(), 750, TimeUnit.MILLISECONDS)
                .handle((servers, error) -> {
                  var maps = error == null && servers != null
                      ? LobbyMapCatalog.availableMaps(context.config(), servers)
                      : context.config().enabledMaps();
                  maps.stream().map(RaidMapId::value).sorted().forEach(builder::suggest);
                  return builder.build();
                }))
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
    var root = Commands.literal(literal)
        .executes(ctx -> menuAction(context, ctx, context.menus()::openMain));
    root.then(Commands.literal("settings")
        .executes(ctx -> menuAction(context, ctx, context.menus()::openSettings)));
    root.then(Commands.literal("cosmetics")
        .executes(ctx -> menuAction(context, ctx, context.menus()::openCosmetics)));
    root.then(Commands.literal("setting")
        .then(Commands.literal("toggle")
            .then(Commands.argument("setting", StringArgumentType.word())
                .suggests((_, builder) -> {
                  builder.suggest("visibility");
                  builder.suggest("queue_messages");
                  return builder.buildFuture();
                })
                .executes(ctx -> menuAction(context, ctx, player ->
                    context.menus().toggleSetting(player,
                        StringArgumentType.getString(ctx, "setting")))))));
    root.then(Commands.literal("cosmetic")
        .then(Commands.literal("equip")
            .then(Commands.argument("index", IntegerArgumentType.integer(0))
                .suggests((_, builder) -> {
                  for (int index = 0; index < context.menus().cosmeticCount(); index++) {
                    builder.suggest(index);
                  }
                  return builder.buildFuture();
                })
                .executes(ctx -> menuAction(context, ctx, player ->
                    context.menus().equipCosmetic(player,
                        IntegerArgumentType.getInteger(ctx, "index"))))))
        .then(Commands.literal("unequip").executes(ctx ->
            menuAction(context, ctx, context.menus()::unequipCosmetic))));
    root.then(Commands.literal("warehouse")
        .executes(ctx -> menuAction(context, ctx, player -> context.menus().openWarehouse(player, 0)))
        .then(Commands.literal("page")
            .then(Commands.argument("direction", StringArgumentType.word())
                .suggests((_, builder) -> {
                  builder.suggest("previous");
                  builder.suggest("next");
                  return builder.buildFuture();
                })
                .executes(ctx -> menuAction(context, ctx, player -> {
                  String direction = StringArgumentType.getString(ctx, "direction");
                  if (!direction.equals("previous") && !direction.equals("next")) {
                    throw new IllegalArgumentException("方向只能是 previous 或 next");
                  }
                  context.menus().warehousePage(player, direction.equals("next") ? 1 : -1);
                }))))
        .then(Commands.literal("move")
            .then(Commands.argument("slot", IntegerArgumentType.integer(0, 35))
                .suggests((_, builder) -> {
                  for (int slot = 0; slot < 36; slot++) builder.suggest(slot);
                  return builder.buildFuture();
                })
                .executes(ctx -> menuAction(context, ctx, player ->
                    context.menus().moveWarehouseItem(player,
                        IntegerArgumentType.getInteger(ctx, "slot")))))));
    return root.build();
  }

  private static int menuAction(LobbyCommandContext context,
      CommandContext<CommandSourceStack> ctx, Consumer<Player> action) {
    Player player = playerOrNull(ctx, context.messages());
    if (player == null || !requireDataReady(context, player)) return Command.SINGLE_SUCCESS;
    try {
      action.accept(player);
    } catch (RuntimeException error) {
      player.sendMessage(Component.text(error.getMessage() == null
          ? "菜单操作失败" : error.getMessage(), NamedTextColor.RED));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int play(LobbyCommandContext context, CommandContext<CommandSourceStack> ctx,
      @Nullable String rawMap) {
    Player player = playerOrNull(ctx, context.messages());
    if (player == null) {
      return Command.SINGLE_SUCCESS;
    }
    if (!requireDataReady(context, player)) {
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
    if (context.config().enabledMaps().contains(mapId)) {
      PlayAction.queue(context.matchmaking(), context.messages(), player, mapId);
      return Command.SINGLE_SUCCESS;
    }
    lookupLiveMap(context, player, mapId);
    return Command.SINGLE_SUCCESS;
  }

  /// A non-configured map is accepted only when at least one live Game server
  /// advertises a room for it. The Redis read stays off-thread and the queue
  /// mutation always hops back to the Paper main thread.
  private static void lookupLiveMap(LobbyCommandContext context, Player player, RaidMapId mapId) {
    player.sendMessage(context.messages().raw("command.checking-map",
        Placeholder.unparsed("map", mapId.value())));
    try {
      context.directory().activeServers().orTimeout(2, TimeUnit.SECONDS)
          .whenComplete((servers, error) ->
              scheduleMapLookupResult(context, player, mapId, servers, error));
    } catch (RuntimeException error) {
      context.plugin().getLogger().warning(
          "查询可用地图失败，暂时不能加入动态地图 " + mapId.value() + ": " + error);
      player.sendMessage(context.messages().raw("command.map-check-failed"));
    }
  }

  private static void scheduleMapLookupResult(LobbyCommandContext context, Player player,
      RaidMapId mapId, @Nullable List<GameServerSnapshot> servers,
      @Nullable Throwable error) {
    if (!context.plugin().isEnabled()) return;
    Bukkit.getScheduler().runTask(context.plugin(), () -> {
      if (!player.isOnline()) return;
      if (error != null || servers == null) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
            ? error.getCause() : error;
        context.plugin().getLogger().warning(
            "查询可用地图失败，暂时不能加入动态地图 " + mapId.value() + ": " + cause);
        player.sendMessage(context.messages().raw("command.map-check-failed"));
        return;
      }
      if (!LobbyMapCatalog.availableMaps(context.config(), servers).contains(mapId)) {
        player.sendMessage(context.messages().raw("command.invalid-map",
            Placeholder.unparsed("map", mapId.value())));
        return;
      }
      if (requireDataReady(context, player)) {
        PlayAction.queue(context.matchmaking(), context.messages(), player, mapId);
      }
    });
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

  private static boolean requireDataReady(LobbyCommandContext context, Player player) {
    if (context.dataGate().isReady(player.getUniqueId())) {
      return true;
    }
    player.sendMessage(context.messages().raw("queue.data-loading"));
    return false;
  }
}
