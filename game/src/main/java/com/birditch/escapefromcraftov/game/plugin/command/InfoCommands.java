package com.birditch.escapefromcraftov.game.plugin.command;

import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.CraftovPermissions;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.loot.LootTableRegistry;
import com.birditch.escapefromcraftov.api.session.SessionService;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.core.module.CraftovModule;
import com.birditch.escapefromcraftov.core.service.ServiceRegistry;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.stream.Collectors;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/// `/craftov help` and `/craftov info`.
final class InfoCommands {

  /// Command usage paired with its Chinese description key in messages.yml.
  private record HelpEntry(String command, String descriptionKey) {}

  private static final List<HelpEntry> PLAYER_HELP_ENTRIES = List.of(
      new HelpEntry("craftov help", "command.help.desc.help"),
      new HelpEntry("craftov tutorial", "command.help.desc.tutorial"),
      new HelpEntry("craftov info", "command.help.desc.info"),
      new HelpEntry("craftov profile", "command.help.desc.profile"),
      new HelpEntry("craftov secure", "command.help.desc.secure"));

  private static final List<HelpEntry> ADMIN_HELP_ENTRIES = List.of(
      new HelpEntry("craftov map import <source> [target]", "command.help.desc.map-import"),
      new HelpEntry("craftov map create <map> <world>", "command.help.desc.map-create"),
      new HelpEntry("craftov map list", "command.help.desc.map-list"),
      new HelpEntry("craftov map info <map>", "command.help.desc.map-info"),
      new HelpEntry("craftov map preview <map>", "command.help.desc.map-preview"),
      new HelpEntry("craftov map enable <map>", "command.help.desc.map-enable"),
      new HelpEntry("craftov map disable <map>", "command.help.desc.map-disable"),
      new HelpEntry("craftov editor", "command.help.desc.editor-toggle"),
      new HelpEntry("craftov editor enter <map>", "command.help.desc.editor-enter"),
      new HelpEntry("craftov editor wand", "command.help.desc.editor-wand"),
      new HelpEntry("craftov editor map bounds", "command.help.desc.editor-bounds"),
      new HelpEntry("craftov editor tool <tool>", "command.help.desc.editor-tool"),
      new HelpEntry("craftov editor <spawn|loot|lootzone|ai|extraction|poi>",
          "command.help.desc.editor-create"),
      new HelpEntry("craftov editor <type> <property> ...", "command.help.desc.editor-property"),
      new HelpEntry("craftov editor select|move|delete|clone", "command.help.desc.editor-edit"),
      new HelpEntry("craftov editor info [id]", "command.help.desc.editor-info"),
      new HelpEntry("craftov editor tp [id]", "command.help.desc.editor-tp"),
      new HelpEntry("craftov editor batch enable|disable|table|tier|delete",
          "command.help.desc.editor-batch"),
      new HelpEntry("craftov editor nearby", "command.help.desc.editor-nearby"),
      new HelpEntry("craftov editor show|hide <all|bounds|type>", "command.help.desc.editor-preview"),
      new HelpEntry("craftov editor validate|save", "command.help.desc.editor-save"),
      new HelpEntry("craftov editor history|rollback <revision>",
          "command.help.desc.editor-history"),
      new HelpEntry("craftov game create [map]", "command.help.desc.game-create"),
      new HelpEntry("craftov game list", "command.help.desc.game-list"),
      new HelpEntry("craftov game start <id>", "command.help.desc.game-start"),
      new HelpEntry("craftov game stop <id>", "command.help.desc.game-stop"),
      new HelpEntry("craftov debug", "command.help.desc.debug"),
      new HelpEntry("craftov debug loot <table>", "command.help.desc.debug-loot"));

  private InfoCommands() {}

  static LiteralArgumentBuilder<CommandSourceStack> help(CommandContext context) {
    return Commands.literal("help")
        .requires(source -> source.getSender().hasPermission(CraftovCommand.PERMISSION_INFO))
        .executes(ctx -> showHelp(context, ctx.getSource().getSender()))
        .then(Commands.literal("admin")
            .requires(source -> ManualMapCommands.allowed(context, source.getSender(),
                CraftovPermissions.EDITOR))
            .executes(ctx -> showAdminHelp(context, ctx.getSource().getSender())));
  }

  static LiteralArgumentBuilder<CommandSourceStack> tutorial(CommandContext context) {
    return Commands.literal("tutorial")
        .requires(source -> source.getSender().hasPermission(CraftovCommand.PERMISSION_INFO))
        .executes(ctx -> showTutorial(context, ctx.getSource().getSender()));
  }

  static LiteralArgumentBuilder<CommandSourceStack> info(CommandContext context) {
    return Commands.literal("info")
        .requires(source -> source.getSender().hasPermission(CraftovCommand.PERMISSION_INFO))
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
    if (ManualMapCommands.allowed(context, sender, CraftovPermissions.EDITOR)) {
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
        .map(CraftovModule::id)
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
            Integer.toString(services.require(CraftovGameService.class).activeCount())),
        Placeholder.unparsed("sessions",
            Integer.toString(services.require(SessionService.class).count()))));
    return Command.SINGLE_SUCCESS;
  }
}
