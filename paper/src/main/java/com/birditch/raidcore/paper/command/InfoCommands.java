package com.birditch.raidcore.paper.command;

import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.item.ItemRegistry;
import com.birditch.raidcore.api.loot.LootTableRegistry;
import com.birditch.raidcore.api.session.SessionService;
import com.birditch.raidcore.core.module.RaidModule;
import com.birditch.raidcore.core.service.ServiceRegistry;
import com.birditch.raidcore.paper.Messages;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.stream.Collectors;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/// `/raid help` and `/raid info`.
final class InfoCommands {

  private record HelpEntry(String command, String description) {}

  private static final List<HelpEntry> HELP_ENTRIES = List.of(
      new HelpEntry("raid help", "Show this help"),
      new HelpEntry("raid info", "Show plugin status"),
      new HelpEntry("raid profile", "Show your profile"),
      new HelpEntry("raid game create [map]", "Create a raid game"),
      new HelpEntry("raid game list", "List active games"),
      new HelpEntry("raid game start <id>", "Start a game"),
      new HelpEntry("raid game stop <id>", "Stop a game"),
      new HelpEntry("raid debug", "Show runtime counters"),
      new HelpEntry("raid debug loot <table>", "Roll a loot table"));

  private InfoCommands() {}

  static LiteralArgumentBuilder<CommandSourceStack> help(CommandContext context) {
    return Commands.literal("help")
        .requires(source -> source.getSender().hasPermission(RaidCommand.PERMISSION_INFO))
        .executes(ctx -> showHelp(context, ctx.getSource().getSender()));
  }

  static LiteralArgumentBuilder<CommandSourceStack> info(CommandContext context) {
    return Commands.literal("info")
        .requires(source -> source.getSender().hasPermission(RaidCommand.PERMISSION_INFO))
        .executes(ctx -> showInfo(context, ctx.getSource().getSender()));
  }

  static int showHelp(CommandContext context, CommandSender sender) {
    Messages messages = context.messages();
    sender.sendMessage(messages.msg("command.help.header"));
    for (HelpEntry entry : HELP_ENTRIES) {
      sender.sendMessage(messages.raw(
          "command.help.entry",
          Placeholder.unparsed("command", entry.command()),
          Placeholder.unparsed("description", entry.description())));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int showInfo(CommandContext context, CommandSender sender) {
    Messages messages = context.messages();
    ServiceRegistry services = context.services();
    String moduleIds = context.modules().modules().stream()
        .map(RaidModule::id)
        .collect(Collectors.joining(", "));
    sender.sendMessage(messages.msg("command.info.header"));
    sender.sendMessage(messages.raw(
        "command.info.version",
        Placeholder.unparsed("version", context.plugin().getPluginMeta().getVersion()),
        Placeholder.unparsed("minecraft", Bukkit.getMinecraftVersion()),
        Placeholder.unparsed("java", Integer.toString(Runtime.version().feature()))));
    sender.sendMessage(messages.raw(
        "command.info.modules", Placeholder.unparsed("modules", moduleIds)));
    sender.sendMessage(messages.raw(
        "command.info.stats",
        Placeholder.unparsed("items",
            Integer.toString(services.require(ItemRegistry.class).size())),
        Placeholder.unparsed("tables",
            Integer.toString(services.require(LootTableRegistry.class).size())),
        Placeholder.unparsed("games",
            Integer.toString(services.require(RaidGameService.class).activeCount())),
        Placeholder.unparsed("sessions",
            Integer.toString(services.require(SessionService.class).count()))));
    return Command.SINGLE_SUCCESS;
  }
}
