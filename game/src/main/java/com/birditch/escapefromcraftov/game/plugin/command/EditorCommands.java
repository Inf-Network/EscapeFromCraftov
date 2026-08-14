package com.birditch.escapefromcraftov.game.plugin.command;

import com.birditch.escapefromcraftov.api.CraftovPermissions;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.loot.LootTableRegistry;
import com.birditch.escapefromcraftov.map.AiZoneDefinition;
import com.birditch.escapefromcraftov.map.BlockPosition;
import com.birditch.escapefromcraftov.map.CuboidBounds;
import com.birditch.escapefromcraftov.map.EditorSession;
import com.birditch.escapefromcraftov.map.EditorTool;
import com.birditch.escapefromcraftov.map.ExtractionDefinition;
import com.birditch.escapefromcraftov.map.LootPointDefinition;
import com.birditch.escapefromcraftov.map.LootZoneDefinition;
import com.birditch.escapefromcraftov.map.ManualExtractionType;
import com.birditch.escapefromcraftov.map.ManualMapEditor;
import com.birditch.escapefromcraftov.map.ManualMapValidationResult;
import com.birditch.escapefromcraftov.map.ManualCraftovMap;
import com.birditch.escapefromcraftov.map.MapObject;
import com.birditch.escapefromcraftov.map.MapObjectIndex;
import com.birditch.escapefromcraftov.map.MapObjectType;
import com.birditch.escapefromcraftov.map.MapValidationIssue;
import com.birditch.escapefromcraftov.map.MapValidationSeverity;
import com.birditch.escapefromcraftov.map.PoiDefinition;
import com.birditch.escapefromcraftov.map.SpawnDefinition;
import com.birditch.escapefromcraftov.game.plugin.editor.SafeEditorTeleport;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/// Complete in-world manual map editor. YAML remains storage, not the UI.
final class EditorCommands {

  private static final ManualMapEditor EDITOR = new ManualMapEditor();

  private EditorCommands() {}

  static LiteralArgumentBuilder<CommandSourceStack> editor(CommandContext context) {
    return Commands.literal("editor")
        .requires(source -> ManualMapCommands.allowed(context, source.getSender(),
            CraftovPermissions.EDITOR))
        // The root command is intentionally a toggle: builders can enter or
        // leave the editor without remembering a second command. Explicit
        // enter/exit branches remain available for scripts and automation.
        .executes(ctx -> player(ctx.getSource().getSender(), value -> toggle(context, value)))
        .then(Commands.literal("enter").then(ManualMapCommands.mapArgument(context).executes(ctx ->
            player(ctx.getSource().getSender(), value -> enter(context, value,
                StringArgumentType.getString(ctx, "map"))))))
        .then(Commands.literal("exit")
            .executes(ctx -> player(ctx.getSource().getSender(), value ->
                exit(context, value, false)))
            .then(Commands.literal("discard").executes(ctx ->
                player(ctx.getSource().getSender(), value -> exit(context, value, true)))))
        .then(Commands.literal("status").executes(ctx ->
            player(ctx.getSource().getSender(), value -> status(context, value))))
        .then(Commands.literal("help").executes(ctx ->
            player(ctx.getSource().getSender(), EditorCommands::help)))
        .then(Commands.literal("menu").executes(ctx ->
            player(ctx.getSource().getSender(), value -> toolbar(context, value))))
        .then(propertyBranch(context))
        .then(Commands.literal("wand").executes(ctx ->
            player(ctx.getSource().getSender(), value -> wand(context, value))))
        .then(toolBranch(context))
        .then(mapBranch(context))
        .then(spawnBranch(context))
        .then(lootBranch(context))
        .then(lootZoneBranch(context))
        .then(aiBranch(context))
        .then(extractionBranch(context))
        .then(poiBranch(context))
        .then(selectBranch(context))
        .then(deleteBranch(context))
        .then(batchBranch(context))
        .then(Commands.literal("move").executes(ctx ->
            player(ctx.getSource().getSender(), value -> move(context, value))))
        .then(Commands.literal("tp").executes(ctx ->
            player(ctx.getSource().getSender(), value -> teleport(context, value, null)))
            .then(objectArgument(context, "id").executes(ctx ->
                player(ctx.getSource().getSender(), value -> teleport(context, value,
                    StringArgumentType.getString(ctx, "id"))))))
        .then(Commands.literal("info").executes(ctx ->
            player(ctx.getSource().getSender(), value -> objectInfo(context, value, null)))
            .then(objectArgument(context, "id").executes(ctx ->
                player(ctx.getSource().getSender(), value -> objectInfo(context, value,
                    StringArgumentType.getString(ctx, "id"))))))
        .then(Commands.literal("clone").then(newObjectIdArgument(context, "new-id", "clone")
            .executes(ctx -> player(ctx.getSource().getSender(), value -> cloneSelected(context,
                value, StringArgumentType.getString(ctx, "new-id"))))))
        .then(Commands.literal("copy").executes(ctx ->
            player(ctx.getSource().getSender(), value -> copySelected(context, value))))
        .then(Commands.literal("paste").then(newObjectIdArgument(context, "new-id", "paste")
            .executes(ctx -> player(ctx.getSource().getSender(), value -> pasteSelected(context,
                value, StringArgumentType.getString(ctx, "new-id"))))))
        .then(Commands.literal("undo").executes(ctx ->
            player(ctx.getSource().getSender(), value -> undo(context, value, false))))
        .then(Commands.literal("redo").executes(ctx ->
            player(ctx.getSource().getSender(), value -> undo(context, value, true))))
        .then(visibilityBranch(context, true))
        .then(visibilityBranch(context, false))
        .then(Commands.literal("radius").then(Commands.argument("blocks",
            IntegerArgumentType.integer(16, 512))
            .suggests((_, builder) -> suggest(builder, "32", "64", "96", "128", "256"))
            .executes(ctx ->
                player(ctx.getSource().getSender(), value -> radius(context, value,
                    IntegerArgumentType.getInteger(ctx, "blocks"))))))
        .then(Commands.literal("nearby").executes(ctx ->
            player(ctx.getSource().getSender(), value -> nearby(context, value))))
        .then(Commands.literal("validate").executes(ctx ->
            player(ctx.getSource().getSender(), value -> validate(context, value))))
        .then(Commands.literal("save")
            .requires(source -> ManualMapCommands.allowed(context, source.getSender(),
                CraftovPermissions.EDITOR_SAVE))
            .executes(ctx -> player(ctx.getSource().getSender(), value -> save(context, value))))
        .then(Commands.literal("history").executes(ctx ->
            player(ctx.getSource().getSender(), value -> history(context, value))))
        .then(Commands.literal("rollback")
          .requires(source -> ManualMapCommands.allowed(context, source.getSender(),
                CraftovPermissions.EDITOR_ROLLBACK))
            .then(Commands.argument("revision", LongArgumentType.longArg(1))
                .suggests((ctx, builder) -> {
                  CommandSender sender = ctx.getSource().getSender();
                  if (sender instanceof Player player) {
                    context.editorSessions().find(player.getUniqueId())
                        .ifPresent(session -> context.manualMapService().revisions(session.mapId())
                            .forEach(revision -> builder.suggest(Long.toString(revision))));
                  }
                  return builder.buildFuture();
                })
                .executes(ctx ->
                player(ctx.getSource().getSender(), value -> rollback(context, value,
                    LongArgumentType.getLong(ctx, "revision"))))))
        .then(Commands.literal("confirm").executes(ctx ->
            player(ctx.getSource().getSender(), value -> confirm(context, value))));
  }

  private static int toolbar(CommandContext context, Player player) {
    if (!context.trMenu().open(player, "escapefromcraftov_editor_toolbar")) {
      throw new IllegalStateException("TrMenu 无法打开编辑器工具栏");
    }
    return Command.SINGLE_SUCCESS;
  }

  private static LiteralArgumentBuilder<CommandSourceStack> propertyBranch(
      CommandContext context) {
    return Commands.literal("property")
        .then(Commands.literal("open").executes(ctx ->
            player(ctx.getSource().getSender(), value -> {
              context.objectMenus().open(value);
              return Command.SINGLE_SUCCESS;
            })))
        .then(Commands.argument("action", StringArgumentType.word())
            .suggests((_, builder) -> suggest(builder, "toggle_enabled", "slot_a", "slot_b",
                "slot_c", "slot_d", "slot_e", "teleport", "delete"))
            .then(Commands.argument("direction", StringArgumentType.word())
                .suggests((_, builder) -> suggest(builder, "forward", "reverse"))
                .executes(ctx -> player(ctx.getSource().getSender(), value -> {
                  String direction = StringArgumentType.getString(ctx, "direction");
                  if (!direction.equals("forward") && !direction.equals("reverse")) {
                    throw new IllegalArgumentException("方向只能是 forward 或 reverse");
                  }
                  context.objectMenus().execute(value,
                      StringArgumentType.getString(ctx, "action"), direction.equals("reverse"));
                  return Command.SINGLE_SUCCESS;
                }))));
  }

  private static LiteralArgumentBuilder<CommandSourceStack> mapBranch(CommandContext context) {
    return Commands.literal("map")
        .requires(source -> ManualMapCommands.allowed(context, source.getSender(),
            CraftovPermissions.EDITOR_MAP))
        .then(Commands.literal("bounds").executes(ctx ->
            player(ctx.getSource().getSender(), value -> setMapBounds(context, value))));
  }

  private static LiteralArgumentBuilder<CommandSourceStack> toolBranch(CommandContext context) {
    return Commands.literal("tool")
        .then(Commands.argument("tool", StringArgumentType.word())
            .suggests((_, builder) -> suggest(builder, "map", "spawn", "loot", "lootzone",
                "ai", "extraction", "poi"))
            .executes(ctx -> player(ctx.getSource().getSender(), value -> {
              String raw = StringArgumentType.getString(ctx, "tool").toLowerCase(Locale.ROOT);
              EditorTool selected = switch (raw) {
                case "map" -> EditorTool.MAP;
                case "spawn" -> EditorTool.SPAWN;
                case "loot" -> EditorTool.LOOT;
                case "lootzone", "loot_zone" -> EditorTool.LOOT_ZONE;
                case "ai" -> EditorTool.AI_ZONE;
                case "extraction" -> EditorTool.EXTRACTION;
                case "poi" -> EditorTool.POI;
                default -> throw new IllegalArgumentException("未知编辑工具: " + raw);
              };
              session(context, value).setTool(selected);
              value.sendMessage(Component.text("编辑工具已切换为 " + selected, NamedTextColor.AQUA));
              return Command.SINGLE_SUCCESS;
            })));
  }

  private static LiteralArgumentBuilder<CommandSourceStack> spawnBranch(CommandContext context) {
    LiteralArgumentBuilder<CommandSourceStack> result = Commands.literal("spawn")
        .requires(source -> ManualMapCommands.allowed(context, source.getSender(),
            CraftovPermissions.EDITOR_SPAWN));
    result.then(Commands.literal("create").executes(ctx ->
        player(ctx.getSource().getSender(), value -> createSpawn(context, value, null)))
        .then(newObjectIdArgument(context, "id", "spawn").executes(ctx ->
            player(ctx.getSource().getSender(), value -> createSpawn(context, value,
                StringArgumentType.getString(ctx, "id"))))));
    result.then(Commands.literal("region").then(Commands.literal("create")
        .then(newObjectIdArgument(context, "id", "spawn").executes(ctx ->
            player(ctx.getSource().getSender(), value -> createSpawnRegion(context, value,
                StringArgumentType.getString(ctx, "id")))))));
    result.then(Commands.literal("team").then(Commands.argument("team", StringArgumentType.word())
        .suggests((_, builder) -> suggest(builder, "players", "red", "blue", "any"))
        .executes(ctx -> player(ctx.getSource().getSender(), value ->
            updateSpawn(context, value, StringArgumentType.getString(ctx, "team"), null, null)))));
    result.then(Commands.literal("weight").then(Commands.argument("weight",
        DoubleArgumentType.doubleArg(0.0001))
        .suggests((_, builder) -> suggest(builder, "0.5", "1.0", "2.0"))
        .executes(ctx -> player(ctx.getSource().getSender(), value ->
            updateSpawn(context, value, null, DoubleArgumentType.getDouble(ctx, "weight"), null)))));
    result.then(Commands.literal("enable").executes(ctx -> toggleSpawn(context, ctx, true)));
    result.then(Commands.literal("disable").executes(ctx -> toggleSpawn(context, ctx, false)));
    return result;
  }

  private static LiteralArgumentBuilder<CommandSourceStack> lootBranch(CommandContext context) {
    return Commands.literal("loot")
        .requires(source -> ManualMapCommands.allowed(context, source.getSender(),
            CraftovPermissions.EDITOR_LOOT))
        .then(Commands.literal("create").executes(ctx ->
            player(ctx.getSource().getSender(), value -> createLoot(context, value, null, null)))
            .then(newObjectIdArgument(context, "id", "loot")
                .executes(ctx -> player(ctx.getSource().getSender(), value ->
                    createLoot(context, value, StringArgumentType.getString(ctx, "id"), null)))
                .then(lootTableArgument(context).executes(ctx ->
                    player(ctx.getSource().getSender(), value -> createLoot(context, value,
                        StringArgumentType.getString(ctx, "id"),
                        StringArgumentType.getString(ctx, "loot-table")))))))
        .then(Commands.literal("bind").executes(ctx ->
            player(ctx.getSource().getSender(), value -> bindLoot(context, value))))
        .then(Commands.literal("type").then(Commands.argument("container-type",
            StringArgumentType.word()).suggests((_, builder) -> suggest(builder,
                "chest", "barrel", "shulker_box", "virtual"))
            .executes(ctx -> player(ctx.getSource().getSender(), value -> updateLoot(context,
                value, StringArgumentType.getString(ctx, "container-type"), null, null, null)))))
        .then(Commands.literal("table").then(lootTableArgument(context).executes(ctx ->
            player(ctx.getSource().getSender(), value -> updateLoot(context, value, null,
                StringArgumentType.getString(ctx, "loot-table"), null, null)))))
        .then(Commands.literal("tier").then(Commands.argument("tier",
            IntegerArgumentType.integer(1, 4)).suggests((_, builder) -> suggest(builder,
                "1", "2", "3", "4")).executes(ctx -> player(ctx.getSource().getSender(), value ->
                    updateLoot(context, value, null, null,
                        IntegerArgumentType.getInteger(ctx, "tier"), null)))))
        .then(Commands.literal("enable").executes(ctx -> player(ctx.getSource().getSender(), value ->
            updateLoot(context, value, null, null, null, true))))
        .then(Commands.literal("disable").executes(ctx -> player(ctx.getSource().getSender(), value ->
            updateLoot(context, value, null, null, null, false))));
  }

  private static LiteralArgumentBuilder<CommandSourceStack> lootZoneBranch(CommandContext context) {
    return Commands.literal("lootzone")
        .requires(source -> ManualMapCommands.allowed(context, source.getSender(),
            CraftovPermissions.EDITOR_LOOT))
        .then(Commands.literal("create").then(newObjectIdArgument(
            context, "id", "loot_zone")
            .executes(ctx -> player(ctx.getSource().getSender(), value ->
                createLootZone(context, value, StringArgumentType.getString(ctx, "id"), null)))
            .then(lootTableArgument(context).executes(ctx ->
                player(ctx.getSource().getSender(), value -> createLootZone(context, value,
                    StringArgumentType.getString(ctx, "id"),
                    StringArgumentType.getString(ctx, "loot-table"))))))
        .then(Commands.literal("table").then(lootTableArgument(context).executes(ctx ->
            player(ctx.getSource().getSender(), value -> updateLootZone(context, value, null,
                StringArgumentType.getString(ctx, "loot-table"), null, null, null)))))
        .then(Commands.literal("tier").then(Commands.argument("tier",
            IntegerArgumentType.integer(1, 4)).suggests((_, builder) -> suggest(builder,
                "1", "2", "3", "4")).executes(ctx -> player(ctx.getSource().getSender(), value ->
                    updateLootZone(context, value,
                        IntegerArgumentType.getInteger(ctx, "tier"), null, null, null, null)))))
        .then(Commands.literal("density").then(Commands.argument("density",
            DoubleArgumentType.doubleArg(0.0001)).suggests((_, builder) -> suggest(builder,
                "0.5", "1.0", "1.5", "2.0")).executes(ctx ->
                    player(ctx.getSource().getSender(), value -> updateLootZone(context, value,
                        null, null, DoubleArgumentType.getDouble(ctx, "density"), null, null)))))
        .then(Commands.literal("danger").then(Commands.argument("danger",
            DoubleArgumentType.doubleArg(0, 1)).suggests((_, builder) -> suggest(builder,
                "0.0", "0.25", "0.5", "0.75", "1.0")).executes(ctx ->
                    player(ctx.getSource().getSender(), value -> updateLootZone(context, value,
                        null, null, null, DoubleArgumentType.getDouble(ctx, "danger"), null)))))
        .then(Commands.literal("enable").executes(ctx -> toggleLootZone(context, ctx, true)))
        .then(Commands.literal("disable").executes(ctx -> toggleLootZone(context, ctx, false))));
  }

  private static LiteralArgumentBuilder<CommandSourceStack> aiBranch(CommandContext context) {
    return Commands.literal("ai")
        .requires(source -> ManualMapCommands.allowed(context, source.getSender(),
            CraftovPermissions.EDITOR_AI))
        .then(Commands.literal("zone").then(Commands.literal("create")
            .then(newObjectIdArgument(context, "id", "ai_zone").executes(ctx ->
                player(ctx.getSource().getSender(), value -> createAiZone(context, value,
                    StringArgumentType.getString(ctx, "id"))))))
        .then(Commands.literal("type").then(Commands.argument("ai-type", StringArgumentType.word())
            .suggests((_, builder) -> suggest(builder, "scav", "guardian", "undead"))
            .executes(ctx -> player(ctx.getSource().getSender(), value -> updateAiZone(context,
                value, StringArgumentType.getString(ctx, "ai-type"), null, null, null, null,
                null, null)))))
        .then(Commands.literal("minimum").then(Commands.argument("minimum",
            IntegerArgumentType.integer(0)).suggests((_, builder) -> suggest(builder,
                "0", "1", "2", "4", "6", "8")).executes(ctx -> player(ctx.getSource().getSender(), value ->
                updateAiZone(context, value, null,
                    IntegerArgumentType.getInteger(ctx, "minimum"), null, null, null, null, null)))))
        .then(Commands.literal("maximum").then(Commands.argument("maximum",
            IntegerArgumentType.integer(0)).suggests((_, builder) -> suggest(builder,
                "0", "2", "4", "6", "8", "12")).executes(ctx -> player(ctx.getSource().getSender(), value ->
                updateAiZone(context, value, null, null,
                    IntegerArgumentType.getInteger(ctx, "maximum"), null, null, null, null)))))
        .then(Commands.literal("respawn").then(Commands.argument("seconds",
            IntegerArgumentType.integer(0)).suggests((_, builder) -> suggest(builder,
                "0", "30", "60", "120", "300")).executes(ctx -> player(ctx.getSource().getSender(), value ->
                updateAiZone(context, value, null, null, null,
                    IntegerArgumentType.getInteger(ctx, "seconds"), null, null, null)))))
        .then(Commands.literal("difficulty").then(Commands.argument("difficulty",
            DoubleArgumentType.doubleArg(0.0001)).suggests((_, builder) -> suggest(builder,
                "0.5", "1.0", "2.0")).executes(ctx -> player(ctx.getSource().getSender(), value ->
                    updateAiZone(context, value, null, null, null, null,
                        DoubleArgumentType.getDouble(ctx, "difficulty"), null, null)))))
        .then(Commands.literal("team").then(Commands.argument("team", StringArgumentType.word())
            .suggests((_, builder) -> suggest(builder, "scav", "neutral", "undead"))
            .executes(ctx -> player(ctx.getSource().getSender(), value -> updateAiZone(context,
                value, null, null, null, null, null,
                StringArgumentType.getString(ctx, "team"), null))))))
        .then(Commands.literal("enable").executes(ctx -> toggleAiZone(context, ctx, true)))
        .then(Commands.literal("disable").executes(ctx -> toggleAiZone(context, ctx, false)));
  }

  private static LiteralArgumentBuilder<CommandSourceStack> extractionBranch(
      CommandContext context) {
    return Commands.literal("extraction")
        .requires(source -> ManualMapCommands.allowed(context, source.getSender(),
            CraftovPermissions.EDITOR_EXTRACTION))
        .then(Commands.literal("create").then(newObjectIdArgument(
            context, "id", "extraction")
            .executes(ctx -> player(ctx.getSource().getSender(), value -> createExtraction(context,
                value, StringArgumentType.getString(ctx, "id"), ManualExtractionType.NORMAL, null)))
            .then(Commands.literal("normal").executes(ctx ->
                player(ctx.getSource().getSender(), value -> createExtraction(context, value,
                    StringArgumentType.getString(ctx, "id"), ManualExtractionType.NORMAL, null))))
            .then(Commands.literal("item").then(itemArgument(context)
                .executes(ctx -> player(ctx.getSource().getSender(), value ->
                    createExtraction(context, value, StringArgumentType.getString(ctx, "id"),
                        ManualExtractionType.ITEM_REQUIRED,
                        StringArgumentType.getString(ctx, "item"))))))
            .then(Commands.literal("paid").then(Commands.argument("cost", LongArgumentType.longArg(0))
                .suggests((_, builder) -> suggest(builder, "100", "250", "500", "1000"))
                .executes(ctx -> player(ctx.getSource().getSender(), value ->
                    createExtraction(context, value, StringArgumentType.getString(ctx, "id"),
                        ManualExtractionType.PAID,
                        Long.toString(LongArgumentType.getLong(ctx, "cost")))))))
            .then(Commands.literal("timed").then(Commands.argument("seconds",
                LongArgumentType.longArg(0))
                .suggests((_, builder) -> suggest(builder, "300", "600", "900", "1200"))
                .executes(ctx ->
                    player(ctx.getSource().getSender(), value -> createExtraction(context, value,
                        StringArgumentType.getString(ctx, "id"), ManualExtractionType.TIMED,
                        Long.toString(LongArgumentType.getLong(ctx, "seconds")))))))))
        .then(Commands.literal("name").then(Commands.argument("name", StringArgumentType.greedyString())
            .suggests((_, builder) -> suggest(builder, "北门出口", "中央撤离点", "东侧通道"))
            .executes(ctx -> player(ctx.getSource().getSender(), value -> updateExtraction(context,
                value, StringArgumentType.getString(ctx, "name"), null, null, null, null)))))
        .then(Commands.literal("duration").then(Commands.argument("seconds",
            IntegerArgumentType.integer(1)).suggests((_, builder) -> suggest(builder,
                "5", "10", "15", "30")).executes(ctx -> player(ctx.getSource().getSender(), value ->
                    updateExtraction(context, value, null,
                        IntegerArgumentType.getInteger(ctx, "seconds"), null, null, null)))))
        .then(Commands.literal("type").then(Commands.argument("type", StringArgumentType.word())
            .suggests((_, builder) -> suggest(builder, "normal", "item", "paid", "timed"))
            .executes(ctx -> player(ctx.getSource().getSender(), value -> updateExtraction(context,
                value, null, null, parseExtractionType(StringArgumentType.getString(ctx, "type")),
                null, null)))
            .then(Commands.argument("condition", StringArgumentType.greedyString())
                .suggests((_, builder) -> suggest(builder, "extraction_key", "100", "300", "600"))
                .executes(ctx -> player(ctx.getSource().getSender(), value -> updateExtraction(context,
                    value, null, null,
                    parseExtractionType(StringArgumentType.getString(ctx, "type")),
                    StringArgumentType.getString(ctx, "condition"), null))))))
        .then(Commands.literal("condition").then(Commands.argument("value",
            StringArgumentType.greedyString()).suggests((_, builder) -> suggest(builder,
                "extraction_key", "100", "300", "600")).executes(ctx -> player(ctx.getSource().getSender(), value ->
                updateExtraction(context, value, null, null, null,
                    StringArgumentType.getString(ctx, "value"), null)))))
        .then(Commands.literal("enable").executes(ctx -> player(ctx.getSource().getSender(), value ->
            updateExtraction(context, value, null, null, null, null, true))))
        .then(Commands.literal("disable").executes(ctx -> player(ctx.getSource().getSender(), value ->
            updateExtraction(context, value, null, null, null, null, false))));
  }

  private static LiteralArgumentBuilder<CommandSourceStack> poiBranch(CommandContext context) {
    return Commands.literal("poi")
        .requires(source -> ManualMapCommands.allowed(context, source.getSender(),
            CraftovPermissions.EDITOR_POI))
        .then(Commands.literal("create").then(newObjectIdArgument(context, "id", "poi")
            .executes(ctx -> player(ctx.getSource().getSender(), value -> createPoi(context, value,
                StringArgumentType.getString(ctx, "id"), 2)))
            .then(Commands.argument("tier", IntegerArgumentType.integer(1, 4))
                .suggests((_, builder) -> suggest(builder, "1", "2", "3", "4"))
                .executes(ctx ->
                player(ctx.getSource().getSender(), value -> createPoi(context, value,
                    StringArgumentType.getString(ctx, "id"),
                    IntegerArgumentType.getInteger(ctx, "tier")))))))
        .then(Commands.literal("name").then(Commands.argument("name", StringArgumentType.greedyString())
            .suggests((_, builder) -> suggest(builder, "中央仓库", "北部设施", "东部仓储区"))
            .executes(ctx -> player(ctx.getSource().getSender(), value -> updatePoi(context, value,
                StringArgumentType.getString(ctx, "name"), null, null)))))
        .then(Commands.literal("tier").then(Commands.argument("tier", IntegerArgumentType.integer(1, 4))
            .suggests((_, builder) -> suggest(builder, "1", "2", "3", "4"))
            .executes(ctx -> player(ctx.getSource().getSender(), value -> updatePoi(context, value,
                null, IntegerArgumentType.getInteger(ctx, "tier"), null)))))
        .then(Commands.literal("enable").executes(ctx -> togglePoi(context, ctx, true)))
        .then(Commands.literal("disable").executes(ctx -> togglePoi(context, ctx, false)));
  }

  private static LiteralArgumentBuilder<CommandSourceStack> selectBranch(CommandContext context) {
    return Commands.literal("select").then(objectArgument(context, "id").executes(ctx ->
        player(ctx.getSource().getSender(), value -> select(context, value,
            StringArgumentType.getString(ctx, "id")))));
  }

  private static LiteralArgumentBuilder<CommandSourceStack> deleteBranch(CommandContext context) {
    return Commands.literal("delete")
        .requires(source -> ManualMapCommands.allowed(context, source.getSender(),
            CraftovPermissions.EDITOR_DELETE))
        .executes(ctx -> player(ctx.getSource().getSender(), value -> deleteSelected(context, value)))
        .then(Commands.literal("type").then(typeArgument().executes(ctx ->
            player(ctx.getSource().getSender(), value -> requestDeleteType(context, value,
                parseType(StringArgumentType.getString(ctx, "type")))))))
        .then(objectArgument(context, "id").executes(ctx ->
            player(ctx.getSource().getSender(), value -> delete(context, value,
                StringArgumentType.getString(ctx, "id")))));
  }

  private static LiteralArgumentBuilder<CommandSourceStack> batchBranch(CommandContext context) {
    LiteralArgumentBuilder<CommandSourceStack> batch = Commands.literal("batch")
        .requires(source -> ManualMapCommands.allowed(context, source.getSender(),
            CraftovPermissions.EDITOR));
    batch.then(batchEnabledBranch(context, true));
    batch.then(batchEnabledBranch(context, false));
    batch.then(Commands.literal("table").then(lootTableArgument(context).executes(ctx ->
        player(ctx.getSource().getSender(), value -> batchLootTable(context, value,
            StringArgumentType.getString(ctx, "loot-table"))))));
    batch.then(Commands.literal("tier").then(Commands.argument("tier",
        IntegerArgumentType.integer(1, 4)).suggests((_, builder) ->
            suggest(builder, "1", "2", "3", "4")).executes(ctx ->
                player(ctx.getSource().getSender(), value -> batchTier(context, value,
                    IntegerArgumentType.getInteger(ctx, "tier"))))));
    batch.then(Commands.literal("delete")
        .requires(source -> ManualMapCommands.allowed(context, source.getSender(),
            CraftovPermissions.EDITOR_DELETE))
        .executes(ctx -> player(ctx.getSource().getSender(), value ->
            requestBatchDelete(context, value, null)))
        .then(typeArgument().executes(ctx -> player(ctx.getSource().getSender(), value ->
            requestBatchDelete(context, value,
                parseType(StringArgumentType.getString(ctx, "type")))))));
    return batch;
  }

  private static LiteralArgumentBuilder<CommandSourceStack> batchEnabledBranch(
      CommandContext context, boolean enabled) {
    return Commands.literal(enabled ? "enable" : "disable")
        .executes(ctx -> player(ctx.getSource().getSender(), value ->
            batchEnabled(context, value, null, enabled)))
        .then(typeArgument().executes(ctx -> player(ctx.getSource().getSender(), value ->
            batchEnabled(context, value, parseType(StringArgumentType.getString(ctx, "type")),
                enabled))));
  }

  private static LiteralArgumentBuilder<CommandSourceStack> visibilityBranch(CommandContext context,
      boolean show) {
    return Commands.literal(show ? "show" : "hide")
        .then(Commands.literal("all").executes(ctx ->
            player(ctx.getSource().getSender(), value -> visibility(context, value, null, show))))
        .then(Commands.literal("bounds").executes(ctx ->
            player(ctx.getSource().getSender(), value -> boundsVisibility(context, value, show))))
        .then(typeArgument().executes(ctx ->
            player(ctx.getSource().getSender(), value -> visibility(context, value,
                parseType(StringArgumentType.getString(ctx, "type")), show))));
  }

  static int enter(CommandContext context, Player player, String mapId) {
    if (isLiveGameWorld(context, player.getWorld().getName())) {
      throw new IllegalStateException("当前正在进行 Craftov 行动，不能从对局世界进入编辑器");
    }
    ManualCraftovMap map = context.maps().find(mapId)
        .orElseThrow(() -> new IllegalArgumentException("地图不存在: " + mapId));
    EditorSession existing = context.editorSessions().find(player.getUniqueId()).orElse(null);
    boolean resumingDirtyDraft = existing != null && existing.dirty()
        && existing.mapId().equals(mapId);
    if (existing != null && existing.dirty() && !resumingDirtyDraft) {
      throw new IllegalStateException(
          "当前编辑会话有未保存修改；请先保存或使用 /craftov editor exit discard");
    }
    World world = Bukkit.getWorld(map.worldName());
    if (world == null) {
      java.nio.file.Path worldRoot = context.plugin().getServer().getWorldContainer().toPath()
          .toAbsolutePath().normalize();
      java.nio.file.Path source = worldRoot.resolve(map.worldName()).normalize();
      if (!source.startsWith(worldRoot)
          || !java.nio.file.Files.isRegularFile(source.resolve("level.dat"))) {
        throw new IllegalStateException("地图编辑源世界不存在: " + map.worldName());
      }
      world = new WorldCreator(map.worldName()).createWorld();
      if (world == null) throw new IllegalStateException("无法加载地图编辑源世界: "
          + map.worldName());
      world.setAutoSave(false);
    }
    // Explicit /enter may switch a clean session to another map. Never carry
    // selection state from the previous world into the new draft.
    if (existing != null && !resumingDirtyDraft) {
      context.selections().clear(player.getUniqueId());
    }
    EditorSession session = context.editorSessions().enter(player.getUniqueId(), map);
    if (!resumingDirtyDraft) {
      session.setVisibilityRadius(context.editorSettings().defaultVisibilityRadius());
    }
    player.teleport(world.getSpawnLocation());
    player.showTitle(net.kyori.adventure.title.Title.title(
        Component.text("地图编辑模式", NamedTextColor.GOLD),
        Component.text(map.displayName() + " · " + mapId, NamedTextColor.GRAY)));
    player.sendActionBar(Component.text("编辑器已就绪 · /craftov editor menu 打开工具栏",
        NamedTextColor.AQUA));
    player.sendMessage(Component.text((resumingDirtyDraft ? "已恢复未保存的 Craftov Editor: "
        : "已进入 Craftov Editor: ") + mapId, NamedTextColor.GREEN));
    return Command.SINGLE_SUCCESS;
  }

  private static int toggle(CommandContext context, Player player) {
    EditorSession existing = context.editorSessions().find(player.getUniqueId()).orElse(null);
    if (existing != null) {
      if (existing.dirty()) {
        player.sendMessage(Component.text(
            "当前编辑会话有未保存修改；请先执行 /craftov editor save，或使用 /craftov editor exit discard。",
            NamedTextColor.YELLOW));
        return Command.SINGLE_SUCCESS;
      }
      return exit(context, player, false);
    }

    String currentWorld = player.getWorld().getName();
    if (isLiveGameWorld(context, currentWorld)) {
      player.sendMessage(Component.text("当前正在进行 Craftov 行动，请先结束或离开本局再编辑地图。",
          NamedTextColor.YELLOW));
      return Command.SINGLE_SUCCESS;
    }
    ManualCraftovMap current = context.maps().all().stream()
        .filter(map -> map.worldName().equals(currentWorld))
        .findFirst().orElse(null);
    if (current != null) return enter(context, player, current.mapId());

    String defaultMap = context.serverConfig().defaultMap().value();
    if (context.maps().find(defaultMap).isPresent()) {
      player.sendMessage(Component.text("当前世界没有登记的 Craftov 地图，将打开默认地图 "
          + defaultMap + "。", NamedTextColor.GRAY));
      return enter(context, player, defaultMap);
    }
    player.sendMessage(Component.text(
        "当前世界没有对应地图。请执行 /craftov editor enter <map>，地图名称支持 Tab 补全。",
        NamedTextColor.YELLOW));
    String candidates = context.maps().all().stream().map(ManualCraftovMap::mapId)
        .sorted().limit(8).collect(java.util.stream.Collectors.joining(", "));
    if (!candidates.isBlank()) {
      player.sendMessage(Component.text("可用地图: " + candidates, NamedTextColor.GRAY));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int exit(CommandContext context, Player player, boolean discard) {
    context.editorSessions().exit(player.getUniqueId(), discard);
    context.trMenu().close(player, "escapefromcraftov_editor_toolbar", "escapefromcraftov_editor_object");
    context.editorVisualization().clear(player);
    context.editorInteraction().clear(player.getUniqueId());
    context.confirmations().cancel(player.getUniqueId());
    context.selections().clear(player.getUniqueId());
    player.sendActionBar(Component.text("已退出地图编辑模式", NamedTextColor.GRAY));
    player.sendMessage(Component.text("已退出 Craftov Editor", NamedTextColor.GRAY));
    return Command.SINGLE_SUCCESS;
  }

  private static boolean isLiveGameWorld(CommandContext context, String worldName) {
    return context.services().require(CraftovGameService.class).all().stream()
        .anyMatch(game -> ("craftov_" + game.id().value()).equals(worldName));
  }

  private static int status(CommandContext context, Player player) {
    EditorSession session = context.editorSessions().require(player.getUniqueId());
    player.sendMessage(Component.text("Craftov Editor | " + session.mapId() + " | r"
        + session.draft().revision() + " | " + (session.dirty() ? "UNSAVED" : "SAVED"),
        session.dirty() ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
    player.sendMessage(Component.text("Tool " + session.tool() + " | Selected "
        + session.selectedObjectId().orElse("-") + " | Radius " + session.visibilityRadius(),
        NamedTextColor.GRAY));
    return Command.SINGLE_SUCCESS;
  }

  private static int help(Player player) {
    helpGroup(player, "地图", "/craftov editor enter|exit|status|map bounds|wand|menu");
    helpGroup(player, "出生", "/craftov editor spawn create|region|team|weight|enable|disable");
    helpGroup(player, "物资", "/craftov editor loot|lootzone create|table|tier|density|danger");
    helpGroup(player, "生物", "/craftov editor ai zone|type|minimum|maximum|respawn|difficulty");
    helpGroup(player, "撤离", "/craftov editor extraction create|name|duration|type|condition");
    helpGroup(player, "对象", "/craftov editor select|info [id]|tp [id]|move|clone|copy|paste|delete");
    helpGroup(player, "批量", "/craftov editor batch enable|disable|table|tier|delete");
    helpGroup(player, "显示", "/craftov editor show|hide|radius|nearby；左键选择，潜行右键属性");
    helpGroup(player, "保存", "/craftov editor undo|redo|save|history|rollback|confirm");
    helpGroup(player, "验证", "/craftov editor validate；ERROR 会在世界中显示红色标记");
    player.sendMessage(Component.text("有未保存修改时不能直接退出；请先 save，或使用 /craftov editor exit discard。",
        NamedTextColor.GRAY));
    return Command.SINGLE_SUCCESS;
  }

  private static void helpGroup(Player player, String group, String commands) {
    player.sendMessage(Component.text("[" + group + "] ", NamedTextColor.GOLD)
        .append(Component.text(commands, NamedTextColor.YELLOW)));
  }

  private static int wand(CommandContext context, Player player) {
    player.getInventory().addItem(context.wand().createWand());
    player.sendMessage(Component.text("已发放选区工具", NamedTextColor.GREEN));
    return Command.SINGLE_SUCCESS;
  }

  private static int setMapBounds(CommandContext context, Player player) {
    EditorSession session = session(context, player);
    CuboidBounds bounds = selection(context, player, session);
    session.setTool(EditorTool.MAP);
    session.apply(map -> map.withBounds(bounds));
    return changed(player, "地图边界", null);
  }

  private static int createSpawn(CommandContext context, Player player, String requestedId) {
    EditorSession session = session(context, player);
    sameWorld(player, session);
    String id = requestedId == null ? nextId(session.draft(), "spawn") : requestedId;
    Location location = player.getLocation();
    SpawnDefinition spawn = new SpawnDefinition(id, position(location), location.getYaw(),
        location.getPitch(), "players", 1, true, null);
    session.setTool(EditorTool.SPAWN);
    return add(player, session, spawn);
  }

  private static int createSpawnRegion(CommandContext context, Player player, String id) {
    EditorSession session = session(context, player);
    CuboidBounds bounds = selection(context, player, session);
    SpawnDefinition spawn = new SpawnDefinition(id, bounds.center(), 0, 0, "players", 1, true,
        bounds);
    session.setTool(EditorTool.SPAWN);
    return add(player, session, spawn);
  }

  private static int createLoot(CommandContext context, Player player, String requestedId,
      String requestedTable) {
    EditorSession session = session(context, player);
    sameWorld(player, session);
    String id = requestedId == null ? nextId(session.draft(), "loot") : requestedId;
    String table = requestedTable == null ? defaultLootTable(context) : requestedTable;
    session.setTool(EditorTool.LOOT);
    return add(player, session, new LootPointDefinition(id, position(player.getLocation()),
        "virtual", table, 1, true, true));
  }

  private static int bindLoot(CommandContext context, Player player) {
    EditorSession session = session(context, player);
    sameWorld(player, session);
    Block target = player.getTargetBlockExact(6);
    if (target == null) throw new IllegalStateException("请看向 6 格内的容器方块");
    String id = nextId(session.draft(), "loot");
    String type = target.getType().name().toLowerCase(Locale.ROOT);
    session.setTool(EditorTool.LOOT);
    return add(player, session, new LootPointDefinition(id,
        new BlockPosition(target.getX(), target.getY(), target.getZ()), type,
        defaultLootTable(context), 1, false, true));
  }

  private static int createLootZone(CommandContext context, Player player, String id,
      String table) {
    EditorSession session = session(context, player);
    session.setTool(EditorTool.LOOT_ZONE);
    return add(player, session, new LootZoneDefinition(id, selection(context, player, session),
        table == null ? defaultLootTable(context) : table, 1, 1, 0.25, true));
  }

  private static int createAiZone(CommandContext context, Player player, String id) {
    EditorSession session = session(context, player);
    session.setTool(EditorTool.AI_ZONE);
    return add(player, session, new AiZoneDefinition(id, selection(context, player, session),
        "scav", 2, 4, 60, 1, "scav", true));
  }

  private static int createExtraction(CommandContext context, Player player, String id,
      ManualExtractionType type, String condition) {
    EditorSession session = session(context, player);
    session.setTool(EditorTool.EXTRACTION);
    return add(player, session, new ExtractionDefinition(id, readableName(id),
        selection(context, player, session), type,
        context.config().extraction().defaultDurationSeconds(), condition, true));
  }

  private static int createPoi(CommandContext context, Player player, String id, int tier) {
    EditorSession session = session(context, player);
    session.setTool(EditorTool.POI);
    return add(player, session, new PoiDefinition(id, readableName(id),
        selection(context, player, session), tier, true));
  }

  private static int updateSpawn(CommandContext context, Player player, String team,
      Double weight, Boolean enabled) {
    return replaceSelected(context, player, MapObjectType.SPAWN, "出生点", object -> {
      SpawnDefinition value = (SpawnDefinition) object;
      return new SpawnDefinition(value.id(), value.position(), value.yaw(), value.pitch(),
          team == null ? value.team() : team, weight == null ? value.weight() : weight,
          enabled == null ? value.enabled() : enabled, value.spawnRegion());
    });
  }

  private static int toggleSpawn(CommandContext context,
      com.mojang.brigadier.context.CommandContext<CommandSourceStack> command, boolean enabled) {
    return player(command.getSource().getSender(), value ->
        updateSpawn(context, value, null, null, enabled));
  }

  private static int updateLoot(CommandContext context, Player player, String containerType,
      String lootTable, Integer tier, Boolean enabled) {
    return replaceSelected(context, player, MapObjectType.LOOT, "物资点", object -> {
      LootPointDefinition value = (LootPointDefinition) object;
      String type = containerType == null ? value.containerType() : containerType;
      return new LootPointDefinition(value.id(), value.position(), type,
          lootTable == null ? value.lootTable() : lootTable,
          tier == null ? value.tier() : tier,
          "virtual".equalsIgnoreCase(type), enabled == null ? value.enabled() : enabled);
    });
  }

  private static int updateLootZone(CommandContext context, Player player, Integer tier,
      String lootTable, Double density, Double danger, Boolean enabled) {
    return replaceSelected(context, player, MapObjectType.LOOT_ZONE, "物资区域", object -> {
      LootZoneDefinition value = (LootZoneDefinition) object;
      return new LootZoneDefinition(value.id(), value.bounds(),
          lootTable == null ? value.lootTable() : lootTable,
          tier == null ? value.tier() : tier,
          density == null ? value.density() : density,
          danger == null ? value.danger() : danger,
          enabled == null ? value.enabled() : enabled);
    });
  }

  private static int toggleLootZone(CommandContext context,
      com.mojang.brigadier.context.CommandContext<CommandSourceStack> command, boolean enabled) {
    return player(command.getSource().getSender(), value ->
        updateLootZone(context, value, null, null, null, null, enabled));
  }

  private static int updateAiZone(CommandContext context, Player player, String aiType,
      Integer minimum, Integer maximum, Integer respawnSeconds, Double difficulty, String team,
      Boolean enabled) {
    return replaceSelected(context, player, MapObjectType.AI_ZONE, "生物区域", object -> {
      AiZoneDefinition value = (AiZoneDefinition) object;
      int min = minimum == null ? value.minimum() : minimum;
      int max = maximum == null ? value.maximum() : maximum;
      return new AiZoneDefinition(value.id(), value.bounds(),
          aiType == null ? value.aiType() : aiType, min, max,
          respawnSeconds == null ? value.respawnSeconds() : respawnSeconds,
          difficulty == null ? value.difficulty() : difficulty,
          team == null ? value.team() : team,
          enabled == null ? value.enabled() : enabled);
    });
  }

  private static int toggleAiZone(CommandContext context,
      com.mojang.brigadier.context.CommandContext<CommandSourceStack> command, boolean enabled) {
    return player(command.getSource().getSender(), value ->
        updateAiZone(context, value, null, null, null, null, null, null, enabled));
  }

  private static int updateExtraction(CommandContext context, Player player, String displayName,
      Integer durationSeconds, ManualExtractionType extractionType, String conditionValue,
      Boolean enabled) {
    return replaceSelected(context, player, MapObjectType.EXTRACTION, "撤离点", object -> {
      ExtractionDefinition value = (ExtractionDefinition) object;
      ManualExtractionType type = extractionType == null ? value.extractionType() : extractionType;
      String condition = conditionValue == null ? value.conditionValue() : conditionValue;
      if (type == ManualExtractionType.NORMAL) {
        if (conditionValue != null && !conditionValue.isBlank()) {
          throw new IllegalStateException("普通撤离点没有条件；请使用 extraction type <type> <value>");
        }
        condition = null;
      }
      return new ExtractionDefinition(value.id(), displayName == null ? value.displayName() : displayName,
          value.bounds(), type, durationSeconds == null ? value.durationSeconds() : durationSeconds,
          condition, enabled == null ? value.enabled() : enabled);
    });
  }

  private static int updatePoi(CommandContext context, Player player, String displayName,
      Integer tier, Boolean enabled) {
    return replaceSelected(context, player, MapObjectType.POI, "兴趣点", object -> {
      PoiDefinition value = (PoiDefinition) object;
      return new PoiDefinition(value.id(), displayName == null ? value.displayName() : displayName,
          value.bounds(), tier == null ? value.tier() : tier,
          enabled == null ? value.enabled() : enabled);
    });
  }

  private static int togglePoi(CommandContext context,
      com.mojang.brigadier.context.CommandContext<CommandSourceStack> command, boolean enabled) {
    return player(command.getSource().getSender(), value ->
        updatePoi(context, value, null, null, enabled));
  }

  private static int replaceSelected(CommandContext context, Player player, MapObjectType expected,
      String label, Function<MapObject, MapObject> update) {
    EditorSession session = session(context, player);
    String id = selected(session);
    MapObject current = session.draft().findObject(id).orElseThrow();
    if (current.type() != expected) {
      throw new IllegalStateException("当前选择的是 " + current.type() + "，不是 " + expected);
    }
    session.apply(map -> EDITOR.replace(map, update.apply(current)));
    return changed(player, "已更新" + label, id);
  }

  private static int select(CommandContext context, Player player, String id) {
    EditorSession session = session(context, player);
    session.select(id);
    session.setTool(tool(session.draft().findObject(id).orElseThrow().type()));
    player.sendMessage(Component.text("已选择 " + id, NamedTextColor.AQUA));
    return Command.SINGLE_SUCCESS;
  }

  private static int deleteSelected(CommandContext context, Player player) {
    return delete(context, player, selected(session(context, player)));
  }

  private static int delete(CommandContext context, Player player, String id) {
    EditorSession session = session(context, player);
    session.apply(map -> EDITOR.remove(map, id));
    return changed(player, "已删除", id);
  }

  private static int requestDeleteType(CommandContext context, Player player, MapObjectType type) {
    EditorSession session = session(context, player);
    long count = session.draft().objects().stream().filter(object -> object.type() == type).count();
    if (count == 0) throw new IllegalStateException("当前地图没有 " + type + " 对象");
    context.confirmations().request(player.getUniqueId(), "删除 " + count + " 个 " + type,
        () -> session.apply(map -> {
          ManualCraftovMap result = map;
          for (MapObject object : map.objects()) {
            if (object.type() == type) result = EDITOR.remove(result, object.id());
          }
          return result;
        }));
    player.sendMessage(Component.text("危险操作待确认: 将删除 " + count + " 个 " + type
        + "。15 秒内执行 /craftov editor confirm", NamedTextColor.RED));
    return Command.SINGLE_SUCCESS;
  }

  private static int confirm(CommandContext context, Player player) {
    String description = context.confirmations().confirm(player.getUniqueId())
        .orElseThrow(() -> new IllegalStateException("没有待确认操作，或确认已过期"));
    player.sendMessage(Component.text("已确认: " + description, NamedTextColor.YELLOW));
    return Command.SINGLE_SUCCESS;
  }

  private static int batchEnabled(CommandContext context, Player player,
      MapObjectType type, boolean enabled) {
    EditorSession session = session(context, player);
    CuboidBounds bounds = selection(context, player, session);
    int changed = batchChange(session, map -> EDITOR.setEnabledIn(map, bounds, type, enabled));
    if (changed == 0) throw new IllegalStateException("选区内没有需要修改的对象");
    return changed(player, "已批量" + (enabled ? "启用" : "停用") + " " + changed + " 个对象",
        null);
  }

  private static int batchLootTable(CommandContext context, Player player, String lootTable) {
    EditorSession session = session(context, player);
    CuboidBounds bounds = selection(context, player, session);
    int changed = batchChange(session, map -> EDITOR.setLootTableIn(map, bounds, lootTable));
    if (changed == 0) throw new IllegalStateException("选区内没有可修改的物资点或物资区域");
    return changed(player, "已为 " + changed + " 个对象设置物资组合 " + lootTable, null);
  }

  private static int batchTier(CommandContext context, Player player, int tier) {
    EditorSession session = session(context, player);
    CuboidBounds bounds = selection(context, player, session);
    int changed = batchChange(session, map -> EDITOR.setTierIn(map, bounds, tier));
    if (changed == 0) throw new IllegalStateException("选区内没有可设置等级的对象");
    return changed(player, "已为 " + changed + " 个对象设置区域等级 " + tier, null);
  }

  private static int batchChange(EditorSession session,
      java.util.function.UnaryOperator<ManualCraftovMap> operation) {
    ManualCraftovMap before = session.draft();
    if (!session.apply(operation)) return 0;
    ManualCraftovMap after = session.draft();
    return (int) before.objects().stream().filter(object ->
        !java.util.Objects.equals(object, after.findObject(object.id()).orElse(null))).count();
  }

  private static int requestBatchDelete(CommandContext context, Player player,
      MapObjectType type) {
    EditorSession session = session(context, player);
    CuboidBounds bounds = selection(context, player, session);
    List<MapObject> objects = EDITOR.objectsIn(session.draft(), bounds, type);
    if (objects.isEmpty()) throw new IllegalStateException("选区内没有符合条件的对象");
    context.confirmations().request(player.getUniqueId(), "删除选区内 " + objects.size() + " 个对象",
        () -> session.apply(map -> EDITOR.removeIn(map, bounds, type)));
    player.sendMessage(Component.text("危险操作待确认: 将删除选区内 " + objects.size()
        + " 个对象。" + context.editorSettings().confirmationSeconds()
        + " 秒内执行 /craftov editor confirm", NamedTextColor.RED));
    return Command.SINGLE_SUCCESS;
  }

  private static int move(CommandContext context, Player player) {
    EditorSession session = session(context, player);
    sameWorld(player, session);
    String id = selected(session);
    session.apply(map -> EDITOR.move(map, id, position(player.getLocation())));
    return changed(player, "已移动", id);
  }

  private static int cloneSelected(CommandContext context, Player player, String newId) {
    EditorSession session = session(context, player);
    sameWorld(player, session);
    session.apply(map -> EDITOR.cloneAt(map, selected(session), newId,
        position(player.getLocation())));
    session.select(newId);
    return changed(player, "已克隆", newId);
  }

  private static int copySelected(CommandContext context, Player player) {
    EditorSession session = session(context, player);
    String id = selected(session);
    session.copySelected();
    player.sendMessage(Component.text("已复制对象: " + id
        + "；使用 /craftov editor paste <new-id> 在当前位置粘贴", NamedTextColor.AQUA));
    return Command.SINGLE_SUCCESS;
  }

  private static int pasteSelected(CommandContext context, Player player, String newId) {
    EditorSession session = session(context, player);
    sameWorld(player, session);
    MapObject source = session.clipboard().orElseThrow(() ->
        new IllegalStateException("剪贴板为空，请先使用 /craftov editor copy"));
    session.apply(map -> EDITOR.cloneObjectAt(map, source, newId, position(player.getLocation())));
    session.select(newId);
    return changed(player, "已粘贴", newId);
  }

  private static int teleport(CommandContext context, Player player, String requestedId) {
    EditorSession session = session(context, player);
    sameWorld(player, session);
    String id = requestedId == null ? selected(session) : requestedId;
    MapObject object = session.draft().findObject(id)
        .orElseThrow(() -> new IllegalArgumentException("对象不存在: " + id));
    BlockPosition target = object.anchor();
    Location safe = SafeEditorTeleport.find(player.getWorld(), target, player.getYaw(),
        player.getPitch());
    if (safe == null) {
      throw new IllegalStateException("选中对象附近没有安全落脚位置");
    }
    player.teleport(safe);
    return Command.SINGLE_SUCCESS;
  }

  private static int objectInfo(CommandContext context, Player player, String requestedId) {
    EditorSession session = session(context, player);
    sameWorld(player, session);
    MapObject object = requestedId == null
        ? targetedObject(player, session).orElseGet(() -> session.selectedObjectId()
            .flatMap(session.draft()::findObject)
            .orElseThrow(() -> new IllegalStateException(
                "准星未指向对象且尚未选择对象；也可以使用 info <id>")))
        : session.draft().findObject(requestedId)
            .orElseThrow(() -> new IllegalArgumentException("对象不存在: " + requestedId));
    session.select(object.id());
    session.setTool(tool(object.type()));
    player.sendMessage(Component.text(object.type() + " | " + object.id(), NamedTextColor.GOLD));
    player.sendMessage(Component.text(object.toString(), NamedTextColor.GRAY));
    return Command.SINGLE_SUCCESS;
  }

  private static java.util.Optional<MapObject> targetedObject(Player player,
      EditorSession session) {
    Location eye = player.getEyeLocation();
    org.bukkit.util.Vector origin = eye.toVector();
    org.bukkit.util.Vector direction = eye.getDirection().normalize();
    return session.draft().objects().stream()
        .map(object -> new Target(object, rayDistance(origin, direction, object)))
        .filter(target -> target.distance() >= 0 && target.distance() <= 48)
        .min(java.util.Comparator.comparingDouble(Target::distance))
        .map(Target::object);
  }

  private static double rayDistance(org.bukkit.util.Vector origin,
      org.bukkit.util.Vector direction, MapObject object) {
    CuboidBounds bounds = object.region().orElseGet(() -> new CuboidBounds(
        object.anchor().x(), object.anchor().y(), object.anchor().z(),
        object.anchor().x(), object.anchor().y() + 1, object.anchor().z()));
    double minimum = 0;
    double maximum = 48;
    double[] starts = {origin.getX(), origin.getY(), origin.getZ()};
    double[] deltas = {direction.getX(), direction.getY(), direction.getZ()};
    double[] mins = {bounds.minX() - 0.35, bounds.minY() - 0.35, bounds.minZ() - 0.35};
    double[] maxes = {bounds.maxX() + 1.35, bounds.maxY() + 1.35, bounds.maxZ() + 1.35};
    for (int axis = 0; axis < 3; axis++) {
      if (Math.abs(deltas[axis]) < 1.0e-9) {
        if (starts[axis] < mins[axis] || starts[axis] > maxes[axis]) return -1;
        continue;
      }
      double first = (mins[axis] - starts[axis]) / deltas[axis];
      double second = (maxes[axis] - starts[axis]) / deltas[axis];
      if (first > second) {
        double swap = first;
        first = second;
        second = swap;
      }
      minimum = Math.max(minimum, first);
      maximum = Math.min(maximum, second);
      if (maximum < minimum) return -1;
    }
    return minimum;
  }

  private record Target(MapObject object, double distance) {}

  private static int undo(CommandContext context, Player player, boolean redo) {
    EditorSession session = session(context, player);
    boolean changed = redo ? session.redo() : session.undo();
    if (!changed) throw new IllegalStateException(redo ? "没有可重做操作" : "没有可撤销操作");
    player.sendMessage(Component.text(redo ? "已重做" : "已撤销", NamedTextColor.GREEN));
    return Command.SINGLE_SUCCESS;
  }

  private static int visibility(CommandContext context, Player player, MapObjectType type,
      boolean show) {
    EditorSession session = session(context, player);
    if (type == null) {
      if (show) session.showAll(); else session.hideAll();
    } else {
      session.setVisible(type, show);
    }
    player.sendMessage(Component.text((show ? "显示 " : "隐藏 ")
        + (type == null ? "全部对象" : type), NamedTextColor.GRAY));
    return Command.SINGLE_SUCCESS;
  }

  private static int boundsVisibility(CommandContext context, Player player, boolean show) {
    session(context, player).setBoundsVisible(show);
    player.sendMessage(Component.text((show ? "显示" : "隐藏") + "地图边界", NamedTextColor.GRAY));
    return Command.SINGLE_SUCCESS;
  }

  private static int radius(CommandContext context, Player player, int blocks) {
    session(context, player).setVisibilityRadius(blocks);
    player.sendMessage(Component.text("可视半径: " + blocks, NamedTextColor.GRAY));
    return Command.SINGLE_SUCCESS;
  }

  private static int nearby(CommandContext context, Player player) {
    EditorSession session = session(context, player);
    sameWorld(player, session);
    BlockPosition center = position(player.getLocation());
    List<MapObject> objects = new MapObjectIndex(session.draft()).nearby(
        center, session.visibilityRadius(), session.visibleTypes()).stream()
        .sorted(java.util.Comparator.comparingDouble(object ->
            center.distanceSquared(object.anchor()))).toList();
    player.sendMessage(Component.text("附近对象 (" + objects.size() + ")", NamedTextColor.GOLD));
    objects.stream().limit(50).forEach(object -> player.sendMessage(objectLink(object)));
    if (objects.size() > 50) player.sendMessage(Component.text("仅显示最近查询中的前 50 个",
        NamedTextColor.YELLOW));
    return Command.SINGLE_SUCCESS;
  }

  private static int validate(CommandContext context, Player player) {
    EditorSession session = session(context, player);
    ManualMapValidationResult result = context.manualMapService().validate(session.draft());
    session.setValidationResult(result);
    player.sendMessage(Component.text("Map Validation | Score " + result.qualityScore()
        + "/100 | ERROR " + result.errorCount() + " | WARNING " + result.warningCount(),
        result.hasErrors() ? NamedTextColor.RED : NamedTextColor.GREEN));
    if (result.issues().isEmpty()) {
      player.sendMessage(Component.text("PASS: 未发现问题", NamedTextColor.GREEN));
    } else {
      result.issues().forEach(issue -> player.sendMessage(issueComponent(issue)));
    }
    if (result.errorCount() > 0) player.sendMessage(Component.text(
        "ERROR 对象已在世界中显示红色叉号；选择后悬浮信息会列出具体原因。",
        NamedTextColor.RED));
    return Command.SINGLE_SUCCESS;
  }

  private static int save(CommandContext context, Player player) {
    EditorSession session = session(context, player);
    ManualCraftovMap saved = context.manualMapService().save(session);
    player.sendMessage(Component.text("已保存 " + saved.mapId() + " revision "
        + saved.revision(), NamedTextColor.GREEN));
    return Command.SINGLE_SUCCESS;
  }

  private static int history(CommandContext context, Player player) {
    EditorSession session = session(context, player);
    List<Long> revisions = context.manualMapService().revisions(session.mapId());
    player.sendMessage(Component.text("Revision History: " + revisions, NamedTextColor.GOLD));
    return Command.SINGLE_SUCCESS;
  }

  private static int rollback(CommandContext context, Player player, long revision) {
    EditorSession session = session(context, player);
    if (session.dirty()) throw new IllegalStateException("请先保存或丢弃当前修改，再回滚");
    ManualCraftovMap restored = context.manualMapService().rollback(session.mapId(), revision);
    session.markSaved(restored);
    session.setValidationResult(context.manualMapService().validate(restored));
    player.sendMessage(Component.text("已从 revision " + revision + " 恢复为新 revision "
        + restored.revision(), NamedTextColor.YELLOW));
    return Command.SINGLE_SUCCESS;
  }

  private static int add(Player player, EditorSession session, MapObject object) {
    session.apply(map -> EDITOR.add(map, object));
    session.select(object.id());
    return changed(player, "已创建 " + object.type(), object.id());
  }

  private static int changed(Player player, String action, String id) {
    player.sendMessage(Component.text(action + (id == null ? "" : ": " + id)
        + " (* UNSAVED)", NamedTextColor.YELLOW));
    return Command.SINGLE_SUCCESS;
  }

  private static EditorSession session(CommandContext context, Player player) {
    return context.editorSessions().require(player.getUniqueId());
  }

  private static CuboidBounds selection(CommandContext context, Player player,
      EditorSession session) {
    sameWorld(player, session);
    return context.selectionProvider().selection(player, session.draft().worldName())
        .orElseThrow(() -> new IllegalStateException("请先用 /craftov editor wand 选择两个角点"));
  }

  private static void sameWorld(Player player, EditorSession session) {
    if (!player.getWorld().getName().equals(session.draft().worldName())) {
      throw new IllegalStateException("请前往地图世界: " + session.draft().worldName());
    }
  }

  private static String selected(EditorSession session) {
    return session.selectedObjectId().orElseThrow(() ->
        new IllegalStateException("尚未选择对象，请使用 /craftov editor select <id>"));
  }

  private static String nextId(ManualCraftovMap map, String prefix) {
    for (int index = 1; index < 100_000; index++) {
      String id = "%s_%03d".formatted(prefix, index);
      if (map.findObject(id).isEmpty()) return id;
    }
    throw new IllegalStateException("无法分配对象 ID");
  }

  private static String defaultLootTable(CommandContext context) {
    return context.services().require(LootTableRegistry.class).all().stream().findFirst()
        .orElseThrow(() -> new IllegalStateException("没有可用 LootTable")).id().value();
  }

  private static String readableName(String id) {
    String[] words = id.split("_");
    StringBuilder result = new StringBuilder();
    for (String word : words) {
      if (!result.isEmpty()) result.append(' ');
      result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    return result.toString();
  }

  private static BlockPosition position(Location location) {
    return new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
  }

  private static EditorTool tool(MapObjectType type) {
    return switch (type) {
      case SPAWN -> EditorTool.SPAWN;
      case LOOT -> EditorTool.LOOT;
      case LOOT_ZONE -> EditorTool.LOOT_ZONE;
      case AI_ZONE -> EditorTool.AI_ZONE;
      case EXTRACTION -> EditorTool.EXTRACTION;
      case POI -> EditorTool.POI;
    };
  }

  private static MapObjectType parseType(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "spawn" -> MapObjectType.SPAWN;
      case "loot" -> MapObjectType.LOOT;
      case "lootzone", "loot_zone" -> MapObjectType.LOOT_ZONE;
      case "ai", "ai_zone" -> MapObjectType.AI_ZONE;
      case "extraction", "extract" -> MapObjectType.EXTRACTION;
      case "poi" -> MapObjectType.POI;
      default -> throw new IllegalArgumentException("未知对象类型: " + value);
    };
  }

  private static ManualExtractionType parseExtractionType(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "normal" -> ManualExtractionType.NORMAL;
      case "item", "item_required", "key" -> ManualExtractionType.ITEM_REQUIRED;
      case "paid", "money" -> ManualExtractionType.PAID;
      case "timed", "time" -> ManualExtractionType.TIMED;
      default -> throw new IllegalArgumentException("未知撤离类型: " + value);
    };
  }

  private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
      typeArgument() {
    return Commands.argument("type", StringArgumentType.word()).suggests((_, builder) -> {
      for (String value : List.of("spawn", "loot", "lootzone", "ai", "extraction", "poi")) {
        builder.suggest(value);
      }
      return builder.buildFuture();
    });
  }

  private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
      lootTableArgument(CommandContext context) {
    return Commands.argument("loot-table", StringArgumentType.word()).suggests((_, builder) -> {
      context.services().require(LootTableRegistry.class).all()
          .forEach(table -> builder.suggest(table.id().value()));
      return builder.buildFuture();
    });
  }

  private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
      itemArgument(CommandContext context) {
    return Commands.argument("item", StringArgumentType.word()).suggests((_, builder) -> {
      context.services().require(ItemRegistry.class).all()
          .forEach(item -> builder.suggest(item.id().value()));
      return builder.buildFuture();
    });
  }

  private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
      objectArgument(CommandContext context, String name) {
    return Commands.argument(name, StringArgumentType.word()).suggests((ctx, builder) -> {
      CommandSender sender = ctx.getSource().getSender();
      if (sender instanceof Player player) {
        context.editorSessions().find(player.getUniqueId()).ifPresent(session -> {
          // Select/delete must be discoverable even outside the preview radius.
          session.draft().objects().forEach(object -> builder.suggest(object.id()));
        });
      }
      return builder.buildFuture();
    });
  }

  private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
      newObjectIdArgument(CommandContext context, String name, String prefix) {
    return Commands.argument(name, StringArgumentType.word()).suggests((ctx, builder) -> {
      CommandSender sender = ctx.getSource().getSender();
      if (sender instanceof Player player) {
        context.editorSessions().find(player.getUniqueId())
            .ifPresent(session -> builder.suggest(nextId(session.draft(), prefix)));
      }
      return builder.buildFuture();
    });
  }

  private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
      suggest(com.mojang.brigadier.suggestion.SuggestionsBuilder builder, String... values) {
    for (String value : values) builder.suggest(value);
    return builder.buildFuture();
  }

  private static Component objectLink(MapObject object) {
    return Component.text("- " + object.type() + " " + object.id(), NamedTextColor.GRAY)
        .clickEvent(ClickEvent.runCommand("/craftov editor select " + object.id()))
        .hoverEvent(HoverEvent.showText(Component.text("点击选择", NamedTextColor.AQUA)));
  }

  static Component issueComponent(MapValidationIssue issue) {
    NamedTextColor color = switch (issue.severity()) {
      case ERROR -> NamedTextColor.RED;
      case WARNING -> NamedTextColor.YELLOW;
      case INFO -> NamedTextColor.GRAY;
    };
    Component component = Component.text("[" + issue.severity() + "] " + issue.message(), color);
    if (issue.affectedObjectId().isPresent()) {
      String id = issue.affectedObjectId().get();
      component = component.append(Component.text(" [" + id + "]", NamedTextColor.AQUA)
          .clickEvent(ClickEvent.runCommand("/craftov editor tp " + id))
          .hoverEvent(HoverEvent.showText(Component.text("点击安全传送到该对象"))));
    }
    return component;
  }

  private static int player(CommandSender sender, Function<Player, Integer> action) {
    if (!(sender instanceof Player player)) return ManualMapCommands.fail(sender,
        "该命令只能由玩家执行");
    try {
      return action.apply(player);
    } catch (RuntimeException e) {
      return ManualMapCommands.fail(sender, ManualMapCommands.reason(e));
    }
  }
}
