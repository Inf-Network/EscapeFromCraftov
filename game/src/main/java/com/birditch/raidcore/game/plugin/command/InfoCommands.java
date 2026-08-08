package com.birditch.raidcore.game.plugin.command;

import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.item.ItemRegistry;
import com.birditch.raidcore.api.loot.LootTableRegistry;
import com.birditch.raidcore.api.session.SessionService;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.core.module.RaidModule;
import com.birditch.raidcore.core.service.ServiceRegistry;
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

  /// Command usage paired with its Chinese description key in messages.yml.
  private record HelpEntry(String command, String descriptionKey) {}

  private static final List<HelpEntry> HELP_ENTRIES = List.of(
      new HelpEntry("raid help", "command.help.desc.help"),
      new HelpEntry("raid info", "command.help.desc.info"),
      new HelpEntry("raid profile", "command.help.desc.profile"),
      new HelpEntry("raid game create [map]", "command.help.desc.game-create"),
      new HelpEntry("raid game list", "command.help.desc.game-list"),
      new HelpEntry("raid game start <id>", "command.help.desc.game-start"),
      new HelpEntry("raid game stop <id>", "command.help.desc.game-stop"),
      new HelpEntry("raid debug", "command.help.desc.debug"),
      new HelpEntry("raid debug loot <table>", "command.help.desc.debug-loot"));

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
    sender.sendMessage(messages.raw("command.help.header"));
    for (HelpEntry entry : HELP_ENTRIES) {
      sender.sendMessage(messages.raw(
          "command.help.entry",
          Placeholder.unparsed("command", entry.command()),
          Placeholder.component("description", messages.raw(entry.descriptionKey()))));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int showInfo(CommandContext context, CommandSender sender) {
    Messages messages = context.messages();
    ServiceRegistry services = context.services();
    String moduleIds = context.modules().modules().stream()
        .map(RaidModule::id)
        .collect(Collectors.joining(", "));
    sender.sendMessage(messages.raw("command.info.header"));
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
