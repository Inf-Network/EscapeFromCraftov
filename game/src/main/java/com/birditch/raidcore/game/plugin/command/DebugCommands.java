package com.birditch.raidcore.game.plugin.command;

import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.item.ItemDefinition;
import com.birditch.raidcore.api.item.ItemInstance;
import com.birditch.raidcore.api.item.ItemRegistry;
import com.birditch.raidcore.api.loot.LootRollContext;
import com.birditch.raidcore.api.loot.LootService;
import com.birditch.raidcore.api.loot.LootTable;
import com.birditch.raidcore.api.loot.LootTableId;
import com.birditch.raidcore.api.loot.LootTableRegistry;
import com.birditch.raidcore.api.session.SessionService;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.core.service.ServiceRegistry;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/// `/raid debug` and `/raid debug loot <table>` — admin-only diagnostics.
final class DebugCommands {

  private DebugCommands() {}

  static LiteralArgumentBuilder<CommandSourceStack> debug(CommandContext context) {
    return Commands.literal("debug")
        .requires(source -> source.getSender().hasPermission(RaidCommand.PERMISSION_ADMIN))
        .executes(ctx -> status(context, ctx.getSource().getSender()))
        .then(Commands.literal("loot")
            .then(Commands.argument("table", StringArgumentType.word())
                .suggests((_, builder) -> {
                  for (LootTable table
                      : context.services().require(LootTableRegistry.class).all()) {
                    builder.suggest(table.id().value());
                  }
                  return builder.buildFuture();
                })
                .executes(ctx -> rollLoot(
                    context, ctx.getSource().getSender(),
                    StringArgumentType.getString(ctx, "table")))));
  }

  private static int status(CommandContext context, CommandSender sender) {
    ServiceRegistry services = context.services();
    sender.sendMessage(context.messages().msg(
        "command.debug.status",
        Placeholder.unparsed("debug", Boolean.toString(context.config().debug())),
        Placeholder.unparsed("modules", Integer.toString(context.modules().count())),
        Placeholder.unparsed("services", Integer.toString(services.size())),
        Placeholder.unparsed("games",
            Integer.toString(services.require(RaidGameService.class).activeCount())),
        Placeholder.unparsed("sessions",
            Integer.toString(services.require(SessionService.class).count()))));
    return Command.SINGLE_SUCCESS;
  }

  private static int rollLoot(CommandContext context, CommandSender sender, String rawTable) {
    Messages messages = context.messages();
    ServiceRegistry services = context.services();
    Optional<LootTableId> tableId =
        knownTableId(services.require(LootTableRegistry.class), rawTable);
    if (tableId.isEmpty()) {
      sender.sendMessage(messages.raw(
          "command.debug.loot.unknown-table", Placeholder.unparsed("table", rawTable)));
      return Command.SINGLE_SUCCESS;
    }
    LootRollContext rollContext = sender instanceof Player player
        ? LootRollContext.forPlayer(player.getUniqueId())
        : LootRollContext.empty();
    List<ItemInstance> items =
        services.require(LootService.class).roll(tableId.get(), rollContext);
    ItemRegistry itemRegistry = services.require(ItemRegistry.class);
    sender.sendMessage(messages.msg(
        "command.debug.loot.header", Placeholder.unparsed("table", rawTable)));
    for (ItemInstance item : items) {
      String name = itemRegistry.find(item.itemId())
          .map(ItemDefinition::displayName)
          .orElse(item.itemId().value());
      sender.sendMessage(messages.raw(
          "command.debug.loot.entry",
          Placeholder.unparsed("item", name),
          Placeholder.unparsed("quantity", Integer.toString(item.quantity()))));
    }
    return Command.SINGLE_SUCCESS;
  }

  /// Returns the id only when it is well-formed and refers to a registered table.
  private static Optional<LootTableId> knownTableId(LootTableRegistry tables, String rawTable) {
    try {
      return Optional.of(LootTableId.of(rawTable)).filter(id -> tables.find(id).isPresent());
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }
}
