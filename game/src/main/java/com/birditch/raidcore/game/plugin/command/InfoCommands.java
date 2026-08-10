package com.birditch.raidcore.game.plugin.command;

import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.RaidPermissions;
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

  private static final List<HelpEntry> PLAYER_HELP_ENTRIES = List.of(
      new HelpEntry("raid help", "command.help.desc.help"),
      new HelpEntry("raid tutorial", "command.help.desc.tutorial"),
      new HelpEntry("raid info", "command.help.desc.info"),
      new HelpEntry("raid profile", "command.help.desc.profile"));

  private static final List<HelpEntry> ADMIN_HELP_ENTRIES = List.of(
      new HelpEntry("raid map import <source> [target]", "command.help.desc.map-import"),
      new HelpEntry("raid map create <map> <world>", "command.help.desc.map-create"),
      new HelpEntry("raid map list", "command.help.desc.map-list"),
      new HelpEntry("raid map info <map>", "command.help.desc.map-info"),
      new HelpEntry("raid map preview <map>", "command.help.desc.map-preview"),
      new HelpEntry("raid map enable <map>", "command.help.desc.map-enable"),
      new HelpEntry("raid map disable <map>", "command.help.desc.map-disable"),
      new HelpEntry("raid editor", "command.help.desc.editor-toggle"),
      new HelpEntry("raid editor enter <map>", "command.help.desc.editor-enter"),
      new HelpEntry("raid editor wand", "command.help.desc.editor-wand"),
      new HelpEntry("raid editor map bounds", "command.help.desc.editor-bounds"),
      new HelpEntry("raid editor tool <tool>", "command.help.desc.editor-tool"),
      new HelpEntry("raid editor <spawn|loot|lootzone|ai|extraction|poi>",
          "command.help.desc.editor-create"),
      new HelpEntry("raid editor <type> <property> ...", "command.help.desc.editor-property"),
      new HelpEntry("raid editor select|move|delete|clone", "command.help.desc.editor-edit"),
      new HelpEntry("raid editor info [id]", "command.help.desc.editor-info"),
      new HelpEntry("raid editor tp [id]", "command.help.desc.editor-tp"),
      new HelpEntry("raid editor batch enable|disable|table|tier|delete",
          "command.help.desc.editor-batch"),
      new HelpEntry("raid editor nearby", "command.help.desc.editor-nearby"),
      new HelpEntry("raid editor show|hide <all|bounds|type>", "command.help.desc.editor-preview"),
      new HelpEntry("raid editor validate|save", "command.help.desc.editor-save"),
      new HelpEntry("raid editor history|rollback <revision>",
          "command.help.desc.editor-history"),
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
        .executes(ctx -> showHelp(context, ctx.getSource().getSender()))
        .then(Commands.literal("admin")
            .requires(source -> ManualMapCommands.allowed(context, source.getSender(),
                RaidPermissions.EDITOR))
            .executes(ctx -> showAdminHelp(context, ctx.getSource().getSender())));
  }

  static LiteralArgumentBuilder<CommandSourceStack> tutorial(CommandContext context) {
    return Commands.literal("tutorial")
        .requires(source -> source.getSender().hasPermission(RaidCommand.PERMISSION_INFO))
        .executes(ctx -> showTutorial(context, ctx.getSource().getSender()));
  }

  static LiteralArgumentBuilder<CommandSourceStack> info(CommandContext context) {
    return Commands.literal("info")
        .requires(source -> source.getSender().hasPermission(RaidCommand.PERMISSION_INFO))
        .executes(ctx -> showInfo(context, ctx.getSource().getSender()));
  }

  static int showHelp(CommandContext context, CommandSender sender) {
    Messages messages = context.messages();
    sender.sendMessage(messages.raw("command.help.header"));
    for (HelpEntry entry : PLAYER_HELP_ENTRIES) {
      sender.sendMessage(messages.raw(
          "command.help.entry",
          Placeholder.unparsed("command", entry.command()),
          Placeholder.component("description", messages.raw(entry.descriptionKey()))));
    }
    if (ManualMapCommands.allowed(context, sender, RaidPermissions.EDITOR)) {
      sender.sendMessage(messages.raw("command.help.admin-hint"));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int showAdminHelp(CommandContext context, CommandSender sender) {
    Messages messages = context.messages();
    sender.sendMessage(messages.raw("command.help.admin-header"));
    for (HelpEntry entry : ADMIN_HELP_ENTRIES) {
      sender.sendMessage(messages.raw("command.help.entry",
          Placeholder.unparsed("command", entry.command()),
          Placeholder.component("description", messages.raw(entry.descriptionKey()))));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int showTutorial(CommandContext context, CommandSender sender) {
    Messages messages = context.messages();
    sender.sendMessage(messages.raw("command.tutorial.header"));
    for (int line = 1; line <= 6; line++) {
      sender.sendMessage(messages.raw("command.tutorial.line-" + line));
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
