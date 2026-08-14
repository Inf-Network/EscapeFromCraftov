package com.birditch.escapefromcraftov.game.plugin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/** TrMenu action endpoints. Every endpoint revalidates its live gameplay context. */
final class UiCommands {

  private UiCommands() {}

  static LiteralArgumentBuilder<CommandSourceStack> ui(CommandContext context) {
    LiteralArgumentBuilder<CommandSourceStack> loot = Commands.literal("loot");
    loot.then(Commands.literal("take")
        .then(Commands.argument("slot", IntegerArgumentType.integer(0, 44))
            .suggests((_, builder) -> {
              for (int slot = 0; slot < 45; slot++) builder.suggest(slot);
              return builder.buildFuture();
            })
            .executes(ctx -> execute(ctx.getSource(), player -> context.lootMenus().take(
                player, IntegerArgumentType.getInteger(ctx, "slot"))))));
    loot.then(Commands.literal("page")
        .then(Commands.argument("direction", StringArgumentType.word())
            .suggests((_, builder) -> {
              builder.suggest("previous");
              builder.suggest("next");
              return builder.buildFuture();
            })
            .executes(ctx -> execute(ctx.getSource(), player -> {
              String direction = StringArgumentType.getString(ctx, "direction");
              if (!direction.equals("previous") && !direction.equals("next")) {
                throw new IllegalArgumentException("方向只能是 previous 或 next");
              }
              context.lootMenus().page(player, direction.equals("next") ? 1 : -1);
            }))));
    LiteralArgumentBuilder<CommandSourceStack> secure = Commands.literal("secure")
        .then(Commands.literal("store")
            .executes(ctx -> execute(ctx.getSource(), context.secureContainers()::storeHeld)));
    secure.then(Commands.literal("take")
        .then(Commands.argument("slot", IntegerArgumentType.integer(0, 2))
            .suggests((_, builder) -> {
              builder.suggest(0);
              builder.suggest(1);
              builder.suggest(2);
              return builder.buildFuture();
            })
            .executes(ctx -> execute(ctx.getSource(), player -> context.secureContainers().take(
                player, IntegerArgumentType.getInteger(ctx, "slot"))))));
    LiteralArgumentBuilder<CommandSourceStack> deathCrate = Commands.literal("death-crate")
        .then(Commands.literal("take")
            .then(Commands.argument("slot", IntegerArgumentType.integer(0, 44))
                .suggests((_, builder) -> {
                  for (int slot = 0; slot < 45; slot++) builder.suggest(slot);
                  return builder.buildFuture();
                })
                .executes(ctx -> execute(ctx.getSource(), player -> context.deathCrates().take(
                    player, IntegerArgumentType.getInteger(ctx, "slot"))))));
    deathCrate.then(Commands.literal("page")
        .then(Commands.argument("direction", StringArgumentType.word())
            .suggests((_, builder) -> {
              builder.suggest("previous");
              builder.suggest("next");
              return builder.buildFuture();
            })
            .executes(ctx -> execute(ctx.getSource(), player -> {
              String direction = StringArgumentType.getString(ctx, "direction");
              if (!direction.equals("previous") && !direction.equals("next")) {
                throw new IllegalArgumentException("方向只能是 previous 或 next");
              }
              context.deathCrates().page(player, direction.equals("next") ? 1 : -1);
            }))));
    return Commands.literal("ui")
        .requires(source -> source.getSender() instanceof Player)
        .then(loot)
        .then(secure)
        .then(deathCrate);
  }

  private static int execute(CommandSourceStack source, PlayerAction action) {
    Player player = (Player) source.getSender();
    try {
      action.run(player);
    } catch (RuntimeException error) {
      player.sendMessage(Component.text(error.getMessage() == null
          ? "菜单操作失败" : error.getMessage(), NamedTextColor.RED));
    }
    return Command.SINGLE_SUCCESS;
  }

  @FunctionalInterface
  private interface PlayerAction {
    void run(Player player);
  }
}
