package com.birditch.escapefromcraftov.lobby.command;

import com.birditch.escapefromcraftov.api.CraftovPermissions;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.matchmaking.MatchTicket;
import com.birditch.escapefromcraftov.api.network.GameServerSnapshot;
import com.birditch.escapefromcraftov.bukkit.MapNames;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.lobby.party.PartyView;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.UUID;
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

/// Brigadier roots for the lobby: `/play [map]`, `/leavequeue`, `/craftov`,
/// `/lobby` and `/menu`.
public final class LobbyCommand {

  private static final String PLAY_PERMISSION = CraftovPermissions.LOBBY_PLAY;

  private LobbyCommand() {}

  public static List<LiteralCommandNode<CommandSourceStack>> build(LobbyCommandContext context) {
    Preconditions.checkNotNull(context, "context");
    return List.of(playRoot(context), leaveQueueRoot(context),
        menuRoot(context, "craftov", false), menuRoot(context, "lobby", true),
        menuRoot(context, "menu", false));
  }

  private static LiteralCommandNode<CommandSourceStack> playRoot(LobbyCommandContext context) {
    return Commands.literal("play")
        .requires(source -> source.getSender().hasPermission(PLAY_PERMISSION))
        .executes(ctx -> play(context, ctx, null, RaidMode.NORMAL, RaidDifficulty.NORMAL))
        .then(Commands.argument("map", StringArgumentType.word())
            .suggests((_, builder) -> context.directory().activeServers()
                .completeOnTimeout(List.of(), 750, TimeUnit.MILLISECONDS)
                .handle((servers, error) -> {
                  var maps = error == null && servers != null
                      ? LobbyMapCatalog.availableMaps(context.config(), servers)
                      : context.config().enabledMaps();
                  maps.stream().map(CraftovMapId::value).sorted().forEach(builder::suggest);
                  return builder.build();
                }))
            .executes(ctx -> play(context, ctx, StringArgumentType.getString(ctx, "map"),
                RaidMode.NORMAL, RaidDifficulty.NORMAL))
            .then(Commands.argument("mode", StringArgumentType.word())
                .suggests((_, builder) -> {
                  for (RaidMode mode : RaidMode.values()) builder.suggest(mode.id());
                  return builder.buildFuture();
                })
                .executes(ctx -> play(context, ctx,
                    StringArgumentType.getString(ctx, "map"),
                    parseMode(ctx), RaidDifficulty.NORMAL))
                .then(Commands.argument("difficulty", StringArgumentType.word())
                    .suggests((_, builder) -> {
                      for (RaidDifficulty difficulty : RaidDifficulty.values()) {
                        builder.suggest(difficulty.id());
                      }
                      return builder.buildFuture();
                    })
                    .executes(ctx -> play(context, ctx,
                        StringArgumentType.getString(ctx, "map"),
                        parseMode(ctx), parseDifficulty(ctx))))))
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
          boolean removed = context.matchmaking().dequeueTeam(player.getUniqueId()) > 0;
          player.sendMessage(context.messages().raw(removed ? "queue.left" : "queue.not-queued"));
          return Command.SINGLE_SUCCESS;
        })
        .build();
  }

  private static LiteralCommandNode<CommandSourceStack> menuRoot(LobbyCommandContext context,
      String literal, boolean includeParty) {
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
    root.then(vendorNode(context));
    root.then(marketNode(context));
    root.then(Commands.literal("reports")
        .executes(ctx -> menuAction(context, ctx, context.raidReports()::show)));
    if (includeParty) root.then(partyNode(context));
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

  private static LiteralArgumentBuilder<CommandSourceStack> vendorNode(
      LobbyCommandContext context) {
    var offer = Commands.argument("offer", IntegerArgumentType.integer(0, 12))
        .suggests((_, builder) -> {
          for (int index = 0; index < 13; index++) builder.suggest(index);
          return builder.buildFuture();
        });
    offer.then(Commands.argument("quantity", IntegerArgumentType.integer(1, 16))
        .suggests((_, builder) -> {
          builder.suggest(1);
          builder.suggest(8);
          builder.suggest(16);
          return builder.buildFuture();
        })
        .executes(ctx -> menuAction(context, ctx, player ->
            context.menus().buyVendorItem(player,
                IntegerArgumentType.getInteger(ctx, "offer"),
                IntegerArgumentType.getInteger(ctx, "quantity")))));

    var sell = Commands.literal("sell")
        .executes(ctx -> menuAction(context, ctx, player ->
            context.menus().openVendorSell(player, 0)));
    sell.then(Commands.argument("slot", IntegerArgumentType.integer(0, 26))
        .suggests((_, builder) -> {
          for (int slot = 0; slot < 27; slot++) builder.suggest(slot);
          return builder.buildFuture();
        })
        .executes(ctx -> menuAction(context, ctx, player ->
            context.menus().sellVendorItem(player,
                IntegerArgumentType.getInteger(ctx, "slot")))));
    sell.then(Commands.literal("page")
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
              context.menus().vendorSellPage(player, direction.equals("next") ? 1 : -1);
            }))));

    return Commands.literal("vendor")
        .executes(ctx -> menuAction(context, ctx, context.menus()::openVendor))
        .then(Commands.literal("buy").then(offer))
        .then(sell);
  }

  private static LiteralArgumentBuilder<CommandSourceStack> marketNode(
      LobbyCommandContext context) {
    var market = Commands.literal("market")
        .executes(ctx -> menuAction(context, ctx, player ->
            context.marketMenus().openBrowse(player, 0)));
    market.then(Commands.literal("page")
        .then(Commands.argument("direction", StringArgumentType.word())
            .suggests((_, builder) -> {
              builder.suggest("previous");
              builder.suggest("next");
              return builder.buildFuture();
            })
            .executes(ctx -> menuAction(context, ctx, player ->
                context.marketMenus().page(player, "browse", direction(ctx))))));
    market.then(Commands.literal("buy")
        .then(Commands.argument("slot", IntegerArgumentType.integer(0, 35))
            .suggests(LobbyCommand::marketSlots)
            .executes(ctx -> menuAction(context, ctx, player ->
                context.marketMenus().buy(player,
                    IntegerArgumentType.getInteger(ctx, "slot"))))));

    var mine = Commands.literal("mine")
        .executes(ctx -> menuAction(context, ctx, player ->
            context.marketMenus().openMine(player, 0)));
    mine.then(Commands.literal("page")
        .then(Commands.argument("direction", StringArgumentType.word())
            .suggests((_, builder) -> {
              builder.suggest("previous");
              builder.suggest("next");
              return builder.buildFuture();
            })
            .executes(ctx -> menuAction(context, ctx, player ->
                context.marketMenus().page(player, "mine", direction(ctx))))));
    mine.then(Commands.literal("cancel")
        .then(Commands.argument("slot", IntegerArgumentType.integer(0, 35))
            .suggests(LobbyCommand::marketSlots)
            .executes(ctx -> menuAction(context, ctx, player ->
                context.marketMenus().cancel(player,
                    IntegerArgumentType.getInteger(ctx, "slot"))))));
    market.then(mine);

    var list = Commands.literal("list")
        .executes(ctx -> menuAction(context, ctx, player ->
            context.marketMenus().openList(player, 0)));
    list.then(Commands.literal("page")
        .then(Commands.argument("direction", StringArgumentType.word())
            .suggests((_, builder) -> {
              builder.suggest("previous");
              builder.suggest("next");
              return builder.buildFuture();
            })
            .executes(ctx -> menuAction(context, ctx, player ->
                context.marketMenus().page(player, "list", direction(ctx))))));
    list.then(Commands.literal("select")
        .then(Commands.argument("slot", IntegerArgumentType.integer(0, 35))
            .suggests(LobbyCommand::marketSlots)
            .executes(ctx -> menuAction(context, ctx, player ->
                context.marketMenus().selectForListing(player,
                    IntegerArgumentType.getInteger(ctx, "slot"))))));
    list.then(Commands.literal("adjust")
        .then(Commands.argument("delta", LongArgumentType.longArg(
                -1_000_000_000L, 1_000_000_000L))
            .suggests((_, builder) -> {
              builder.suggest(-1000);
              builder.suggest(-100);
              builder.suggest(100);
              builder.suggest(1000);
              return builder.buildFuture();
            })
            .executes(ctx -> menuAction(context, ctx, player ->
                context.marketMenus().adjustPrice(player,
                    LongArgumentType.getLong(ctx, "delta"))))));
    list.then(Commands.literal("price")
        .then(Commands.argument("amount", LongArgumentType.longArg(1, 1_000_000_000L))
            .suggests((_, builder) -> {
              builder.suggest(1000);
              builder.suggest(5000);
              builder.suggest(10000);
              return builder.buildFuture();
            })
            .executes(ctx -> menuAction(context, ctx, player ->
                context.marketMenus().setPrice(player,
                    LongArgumentType.getLong(ctx, "amount"))))));
    list.then(Commands.literal("confirm").executes(ctx ->
        menuAction(context, ctx, context.marketMenus()::confirmListing)));
    market.then(list);
    return market;
  }

  private static int direction(CommandContext<CommandSourceStack> ctx) {
    String value = StringArgumentType.getString(ctx, "direction");
    if (!value.equals("previous") && !value.equals("next")) {
      throw new IllegalArgumentException("方向只能是 previous 或 next");
    }
    return value.equals("next") ? 1 : -1;
  }

  private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
      marketSlots(CommandContext<CommandSourceStack> ignored,
          com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
    for (int slot = 0; slot < 36; slot++) builder.suggest(slot);
    return builder.buildFuture();
  }

  private static int play(LobbyCommandContext context, CommandContext<CommandSourceStack> ctx,
      @Nullable String rawMap, RaidMode mode, RaidDifficulty difficulty) {
    Player player = playerOrNull(ctx, context.messages());
    if (player == null) {
      return Command.SINGLE_SUCCESS;
    }
    if (!requireDataReady(context, player)) {
      return Command.SINGLE_SUCCESS;
    }
    CraftovMapId mapId;
    if (rawMap == null) {
      mapId = context.config().defaultMap();
    } else {
      try {
        mapId = CraftovMapId.of(rawMap);
      } catch (IllegalArgumentException _) {
        player.sendMessage(context.messages().raw("command.invalid-map",
            Placeholder.unparsed("map", rawMap)));
        return Command.SINGLE_SUCCESS;
      }
    }
    if (context.config().enabledMaps().contains(mapId)) {
      queueMatch(context, player, mapId, mode, difficulty);
      return Command.SINGLE_SUCCESS;
    }
    lookupLiveMap(context, player, mapId, mode, difficulty);
    return Command.SINGLE_SUCCESS;
  }

  /// A non-configured map is accepted only when at least one live Game server
  /// advertises a room for it. The Redis read stays off-thread and the queue
  /// mutation always hops back to the Paper main thread.
  private static void lookupLiveMap(LobbyCommandContext context, Player player, CraftovMapId mapId,
      RaidMode mode, RaidDifficulty difficulty) {
    player.sendMessage(context.messages().raw("command.checking-map",
        Placeholder.unparsed("map", mapId.value())));
    try {
      context.directory().activeServers().orTimeout(2, TimeUnit.SECONDS)
          .whenComplete((servers, error) ->
              scheduleMapLookupResult(
                  context, player, mapId, mode, difficulty, servers, error));
    } catch (RuntimeException error) {
      context.plugin().getLogger().warning(
          "查询可用地图失败，暂时不能加入动态地图 " + mapId.value() + ": " + error);
      player.sendMessage(context.messages().raw("command.map-check-failed"));
    }
  }

  private static void scheduleMapLookupResult(LobbyCommandContext context, Player player,
      CraftovMapId mapId, RaidMode mode, RaidDifficulty difficulty,
      @Nullable List<GameServerSnapshot> servers,
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
        queueMatch(context, player, mapId, mode, difficulty);
      }
    });
  }

  private static void queueMatch(LobbyCommandContext context, Player requester,
      CraftovMapId mapId, RaidMode mode, RaidDifficulty difficulty) {
    PartyView party = context.parties().party(requester.getUniqueId()).orElse(null);
    if (party != null && !party.isLeader(requester.getUniqueId())) {
      requester.sendMessage(Component.text("只有队长可以让全队开始匹配", NamedTextColor.RED));
      return;
    }
    List<UUID> members = party == null ? List.of(requester.getUniqueId()) : party.members();
    UUID teamKey = party == null ? requester.getUniqueId() : party.partyId();
    for (UUID memberId : members) {
      Player member = Bukkit.getPlayer(memberId);
      if (member == null || !member.isOnline()) {
        requester.sendMessage(Component.text(
            "队伍成员 " + playerName(memberId) + " 不在当前大厅，无法整队匹配",
            NamedTextColor.RED));
        return;
      }
      if (!context.dataGate().isReady(memberId)) {
        requester.sendMessage(Component.text(
            "队伍成员 " + member.getName() + " 的数据仍在加载", NamedTextColor.RED));
        return;
      }
    }
    var queued = context.matchmaking().queueParty(
        members, mapId, mode, difficulty, teamKey);
    if (queued.error().isPresent()) {
      requester.sendMessage(Component.text(queued.error().orElseThrow(), NamedTextColor.RED));
      return;
    }
    for (MatchTicket ticket : queued.value().orElseThrow()) {
      Player member = Bukkit.getPlayer(ticket.playerId());
      if (member == null) continue;
      member.sendMessage(context.messages().raw("queue.joined",
          Placeholder.component("map", MapNames.display(context.messages(), mapId))));
      member.sendMessage(Component.text("模式 " + mode.id() + " · 难度 " + difficulty.id(),
          NamedTextColor.GRAY));
    }
  }

  private static RaidMode parseMode(CommandContext<CommandSourceStack> ctx)
      throws CommandSyntaxException {
    try {
      return RaidMode.parse(StringArgumentType.getString(ctx, "mode"));
    } catch (IllegalArgumentException error) {
      throw new SimpleCommandExceptionType(
          () -> "模式只能是 normal 或 confidential").create();
    }
  }

  private static RaidDifficulty parseDifficulty(CommandContext<CommandSourceStack> ctx)
      throws CommandSyntaxException {
    try {
      return RaidDifficulty.parse(StringArgumentType.getString(ctx, "difficulty"));
    } catch (IllegalArgumentException error) {
      throw new SimpleCommandExceptionType(
          () -> "难度只能是 easy、normal 或 hard").create();
    }
  }

  private static LiteralArgumentBuilder<CommandSourceStack> partyNode(
      LobbyCommandContext context) {
    return Commands.literal("party")
        .executes(ctx -> partyInfo(context, ctx))
        .then(Commands.literal("create").executes(ctx -> partyCreate(context, ctx)))
        .then(Commands.literal("invite")
            .then(Commands.argument("player", StringArgumentType.word())
                .suggests((_, builder) -> {
                  Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted()
                      .forEach(builder::suggest);
                  return builder.buildFuture();
                })
                .executes(ctx -> partyInvite(
                    context, ctx, StringArgumentType.getString(ctx, "player")))))
        .then(Commands.literal("accept").executes(ctx -> partyAccept(context, ctx)))
        .then(Commands.literal("leave").executes(ctx -> partyLeave(context, ctx)))
        .then(Commands.literal("info").executes(ctx -> partyInfo(context, ctx)));
  }

  private static int partyCreate(LobbyCommandContext context,
      CommandContext<CommandSourceStack> ctx) {
    Player player = readyPlayer(context, ctx);
    if (player == null || !partyMutationAllowed(context, List.of(player.getUniqueId()), player)) {
      return Command.SINGLE_SUCCESS;
    }
    var result = context.parties().create(player.getUniqueId());
    if (result.error().isPresent()) {
      player.sendMessage(Component.text(result.error().orElseThrow(), NamedTextColor.RED));
    } else {
      player.sendMessage(Component.text("队伍已创建，你现在是队长", NamedTextColor.GREEN));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int partyInvite(LobbyCommandContext context,
      CommandContext<CommandSourceStack> ctx, String targetName) {
    Player inviter = readyPlayer(context, ctx);
    if (inviter == null) return Command.SINGLE_SUCCESS;
    PartyView party = context.parties().party(inviter.getUniqueId()).orElse(null);
    List<UUID> currentMembers = party == null
        ? List.of(inviter.getUniqueId()) : party.members();
    if (!partyMutationAllowed(context, currentMembers, inviter)) return Command.SINGLE_SUCCESS;
    Player invited = Bukkit.getPlayerExact(targetName);
    if (invited == null || !invited.isOnline()) {
      inviter.sendMessage(Component.text("没有找到在线玩家 " + targetName, NamedTextColor.RED));
      return Command.SINGLE_SUCCESS;
    }
    var result = context.parties().invite(inviter.getUniqueId(), invited.getUniqueId());
    if (result.error().isPresent()) {
      inviter.sendMessage(Component.text(result.error().orElseThrow(), NamedTextColor.RED));
      return Command.SINGLE_SUCCESS;
    }
    inviter.sendMessage(Component.text("已邀请 " + invited.getName(), NamedTextColor.GREEN));
    invited.sendMessage(Component.text(inviter.getName()
        + " 邀请你加入队伍，输入 /lobby party accept 接受", NamedTextColor.AQUA));
    return Command.SINGLE_SUCCESS;
  }

  private static int partyAccept(LobbyCommandContext context,
      CommandContext<CommandSourceStack> ctx) {
    Player player = readyPlayer(context, ctx);
    if (player == null) return Command.SINGLE_SUCCESS;
    UUID partyId = context.parties().invitingParty(player.getUniqueId()).orElse(null);
    PartyView invitedParty = partyId == null
        ? null : context.parties().partyById(partyId).orElse(null);
    List<UUID> affected = new java.util.ArrayList<>();
    affected.add(player.getUniqueId());
    if (invitedParty != null) affected.addAll(invitedParty.members());
    if (!partyMutationAllowed(context, affected, player)) return Command.SINGLE_SUCCESS;
    var result = context.parties().accept(player.getUniqueId());
    if (result.error().isPresent()) {
      player.sendMessage(Component.text(result.error().orElseThrow(), NamedTextColor.RED));
      return Command.SINGLE_SUCCESS;
    }
    PartyView joined = result.value().orElseThrow();
    broadcastParty(joined, Component.text(
        player.getName() + " 加入了队伍", NamedTextColor.GREEN));
    return Command.SINGLE_SUCCESS;
  }

  private static int partyLeave(LobbyCommandContext context,
      CommandContext<CommandSourceStack> ctx) {
    Player player = readyPlayer(context, ctx);
    if (player == null) return Command.SINGLE_SUCCESS;
    PartyView current = context.parties().party(player.getUniqueId()).orElse(null);
    List<UUID> affected = current == null
        ? List.of(player.getUniqueId()) : current.members();
    if (!partyMutationAllowed(context, affected, player)) return Command.SINGLE_SUCCESS;
    var result = context.parties().leave(player.getUniqueId());
    if (result.error().isPresent()) {
      player.sendMessage(Component.text(result.error().orElseThrow(), NamedTextColor.RED));
      return Command.SINGLE_SUCCESS;
    }
    player.sendMessage(Component.text("你已离开队伍", NamedTextColor.YELLOW));
    result.value().orElseThrow().ifPresent(remaining -> broadcastParty(remaining,
        Component.text(player.getName() + " 离开了队伍", NamedTextColor.YELLOW)));
    return Command.SINGLE_SUCCESS;
  }

  private static int partyInfo(LobbyCommandContext context,
      CommandContext<CommandSourceStack> ctx) {
    Player player = readyPlayer(context, ctx);
    if (player == null) return Command.SINGLE_SUCCESS;
    PartyView party = context.parties().party(player.getUniqueId()).orElse(null);
    if (party == null) {
      player.sendMessage(Component.text(
          "当前没有队伍。使用 /lobby party create 创建。", NamedTextColor.GRAY));
      return Command.SINGLE_SUCCESS;
    }
    player.sendMessage(Component.text(
        "队伍 " + party.members().size() + "/3 · 队长 " + playerName(party.leaderId()),
        NamedTextColor.GOLD));
    for (UUID memberId : party.members()) {
      player.sendMessage(Component.text(
          "- " + playerName(memberId) + (memberId.equals(party.leaderId()) ? " [队长]" : ""),
          NamedTextColor.WHITE));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static boolean partyMutationAllowed(LobbyCommandContext context,
      List<UUID> affectedPlayers, Player feedbackTarget) {
    boolean queued = affectedPlayers.stream().anyMatch(playerId ->
        context.matchmaking().ticket(playerId).isPresent());
    if (queued) {
      feedbackTarget.sendMessage(Component.text(
          "匹配队列中不能修改队伍，请先使用 /leavequeue", NamedTextColor.RED));
    }
    return !queued;
  }

  private static void broadcastParty(PartyView party, Component message) {
    for (UUID memberId : party.members()) {
      Player member = Bukkit.getPlayer(memberId);
      if (member != null) member.sendMessage(message);
    }
  }

  private static String playerName(UUID playerId) {
    Player online = Bukkit.getPlayer(playerId);
    if (online != null) return online.getName();
    String cached = Bukkit.getOfflinePlayer(playerId).getName();
    return cached == null ? playerId.toString().substring(0, 8) : cached;
  }

  private static @Nullable Player readyPlayer(LobbyCommandContext context,
      CommandContext<CommandSourceStack> ctx) {
    Player player = playerOrNull(ctx, context.messages());
    return player != null && requireDataReady(context, player) ? player : null;
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
