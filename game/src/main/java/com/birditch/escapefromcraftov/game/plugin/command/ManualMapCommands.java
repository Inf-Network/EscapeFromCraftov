package com.birditch.escapefromcraftov.game.plugin.command;

import com.birditch.escapefromcraftov.api.CraftovPermissions;
import com.birditch.escapefromcraftov.map.ManualCraftovMap;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/// Authoritative map lifecycle and administrator-override commands.
final class ManualMapCommands {

  private ManualMapCommands() {}

  static LiteralArgumentBuilder<CommandSourceStack> map(CommandContext context) {
    return Commands.literal("map")
        .requires(source -> allowed(context, source.getSender(), CraftovPermissions.EDITOR_MAP))
        .then(Commands.literal("create")
            .then(Commands.argument("map", StringArgumentType.word())
                .suggests((_, builder) -> {
                  suggestedMapIds(context).forEach(builder::suggest);
                  return builder.buildFuture();
                })
                .then(Commands.argument("world", StringArgumentType.string())
                    .suggests((_, builder) -> {
                      worldNames(context).forEach(value ->
                          builder.suggest(StringArgumentType.escapeIfRequired(value)));
                      return builder.buildFuture();
                    })
                    .executes(ctx -> create(context, ctx.getSource().getSender(),
                        StringArgumentType.getString(ctx, "map"),
                        StringArgumentType.getString(ctx, "world"))))))
        .then(Commands.literal("import")
            .then(Commands.argument("source", StringArgumentType.string())
                .suggests((_, builder) -> {
                  mapPackages(context).forEach(value ->
                      builder.suggest(StringArgumentType.escapeIfRequired(value)));
                  return builder.buildFuture();
                })
                .executes(ctx -> importPackage(context, ctx.getSource().getSender(),
                    StringArgumentType.getString(ctx, "source"), null))
                .then(Commands.argument("target", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                      String source = normalizedId(StringArgumentType.getString(ctx, "source"));
                      if (!source.isBlank() && context.maps().find(source).isEmpty()) {
                        builder.suggest(source);
                      }
                      String copy = source + "_copy";
                      if (!source.isBlank() && context.maps().find(copy).isEmpty()) {
                        builder.suggest(copy);
                      }
                      return builder.buildFuture();
                    })
                    .executes(ctx -> importPackage(context, ctx.getSource().getSender(),
                        StringArgumentType.getString(ctx, "source"),
                        StringArgumentType.getString(ctx, "target"))))))
        .then(Commands.literal("list").executes(ctx -> list(context,
            ctx.getSource().getSender())))
        .then(Commands.literal("info").then(mapArgument(context).executes(ctx ->
            info(context, ctx.getSource().getSender(),
                StringArgumentType.getString(ctx, "map")))))
        .then(Commands.literal("preview").then(mapArgument(context).executes(ctx ->
            preview(context, ctx.getSource().getSender(),
                StringArgumentType.getString(ctx, "map")))))
        .then(Commands.literal("enable").then(mapArgument(context).executes(ctx ->
            enabled(context, ctx.getSource().getSender(),
                StringArgumentType.getString(ctx, "map"), true))))
        .then(Commands.literal("disable").then(mapArgument(context).executes(ctx ->
            enabled(context, ctx.getSource().getSender(),
                StringArgumentType.getString(ctx, "map"), false))));
  }

  private static int create(CommandContext context, CommandSender sender, String mapId,
      String worldName) {
    World world = Bukkit.getWorld(worldName);
    if (world == null) return fail(sender, "世界未加载或不存在: " + worldName);
    try {
      ManualCraftovMap map = context.manualMapService().create(mapId, worldName);
      sender.sendMessage(Component.text("已创建地图草稿 " + map.mapId() + " (revision "
          + map.revision() + ")", NamedTextColor.GREEN));
      sender.sendMessage(Component.text("下一步: /craftov editor enter " + map.mapId(),
          NamedTextColor.GRAY));
    } catch (RuntimeException e) {
      return fail(sender, reason(e));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int importPackage(CommandContext context, CommandSender sender, String sourceName,
      String requestedTarget) {
    Path source = resolvePackage(context, sourceName);
    if (source == null) {
      return fail(sender, "找不到地图包目录: " + sourceName
          + "。请把完整地图包放进 plugins/EscapeFromCraftovGame/imports/ 后再执行导入");
    }
    String target = requestedTarget == null
        ? normalizedId(source.getFileName().toString()) : requestedTarget;
    if (target.isBlank()) return fail(sender, "无法从文件夹名生成有效地图 ID，请手工填写 target");
    sender.sendMessage(Component.text("正在后台导入地图 " + source.getFileName()
        + "，完成后会通知你", NamedTextColor.YELLOW));
    context.scheduler().runAsync(() -> {
      try {
        ManualCraftovMap map = context.manualMapService().importPackage(target, source);
        context.scheduler().runSync(() -> {
          if (!context.plugin().isEnabled()) return;
          sender.sendMessage(Component.text("已导入地图 " + source.getFileName()
              + " → " + map.mapId() + " (revision " + map.revision() + ")",
              NamedTextColor.GREEN));
          sender.sendMessage(Component.text("地图默认关闭，请执行 /craftov editor enter "
              + map.mapId() + " 检查点位、验证并启用", NamedTextColor.GRAY));
        });
      } catch (RuntimeException e) {
        String message = reason(e);
        context.scheduler().runSync(() -> {
          if (context.plugin().isEnabled()) fail(sender, message);
        });
      }
    });
    return Command.SINGLE_SUCCESS;
  }

  private static int list(CommandContext context, CommandSender sender) {
    sender.sendMessage(Component.text("Craftov Maps (" + context.maps().all().size() + ")",
        NamedTextColor.GOLD));
    for (ManualCraftovMap map : context.maps().all()) {
      sender.sendMessage(Component.text("- " + map.mapId() + " | " + map.worldName()
          + " | r" + map.revision() + " | " + (map.enabled() ? "ENABLED" : "DISABLED")
          + " | Spawn " + map.spawns().size() + " | Loot " + map.lootPoints().size()
          + " | LootZone " + map.lootZones().size() + " | AI " + map.aiZones().size()
          + " | Extraction " + map.extractions().size() + " | POI " + map.pois().size(),
          map.enabled() ? NamedTextColor.GREEN : NamedTextColor.GRAY));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int info(CommandContext context, CommandSender sender, String mapId) {
    ManualCraftovMap map = context.maps().find(mapId).orElse(null);
    if (map == null) return fail(sender, "地图不存在: " + mapId);
    sender.sendMessage(Component.text(map.displayName() + " [" + map.mapId() + "]",
        NamedTextColor.GOLD));
    sender.sendMessage(Component.text("World " + map.worldName() + " | revision " + map.revision()
        + " | " + (map.enabled() ? "ENABLED" : "DISABLED"), NamedTextColor.GRAY));
    sender.sendMessage(Component.text("Spawn " + map.spawns().size() + " | Loot "
        + map.lootPoints().size() + " | LootZone " + map.lootZones().size() + " | AI "
        + map.aiZones().size() + " | Extraction " + map.extractions().size() + " | POI "
        + map.pois().size(), NamedTextColor.AQUA));
    return Command.SINGLE_SUCCESS;
  }

  private static int preview(CommandContext context, CommandSender sender, String mapId) {
    if (!(sender instanceof org.bukkit.entity.Player player)) {
      return fail(sender, "地图预览只能由游戏内管理员使用");
    }
    try {
      EditorCommands.enter(context, player, mapId);
      context.editorSessions().require(player.getUniqueId()).showAll();
      player.sendMessage(Component.text(
          "预览已开启: 橙色边界 / 绿色出生 / 黄色物资 / 红色 AI / 青色撤离 / 紫色 POI",
          NamedTextColor.AQUA));
    } catch (RuntimeException error) {
      return fail(sender, reason(error));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int enabled(CommandContext context, CommandSender sender, String mapId,
      boolean enabled) {
    try {
      ManualCraftovMap map = context.manualMapService().setEnabled(mapId, enabled);
      sender.sendMessage(Component.text("地图 " + map.mapId() + " 已"
          + (enabled ? "启用" : "禁用") + "，revision " + map.revision(),
          enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
    } catch (RuntimeException e) {
      return fail(sender, reason(e));
    }
    return Command.SINGLE_SUCCESS;
  }

  static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
      mapArgument(CommandContext context) {
    return Commands.argument("map", StringArgumentType.word()).suggests((_, builder) -> {
      context.maps().all().forEach(map -> builder.suggest(map.mapId()));
      return builder.buildFuture();
    });
  }

  private static Set<String> mapPackages(CommandContext context) {
    Set<String> result = new LinkedHashSet<>();
    for (Path root : packageRoots(context)) {
      if (!Files.isDirectory(root)) continue;
      try (Stream<Path> entries = Files.list(root)) {
        entries.filter(Files::isDirectory)
            .filter(path -> !Files.isSymbolicLink(path))
            .filter(path -> !path.getFileName().toString().startsWith("craftov_"))
            .filter(path -> Files.isRegularFile(path.resolve("map.yml"))
                || Files.isRegularFile(path.resolve("level.dat")))
            .map(path -> path.getFileName().toString())
            .forEach(result::add);
      } catch (java.io.IOException ignored) {
        // Optional suggestion roots are best effort only.
      }
    }
    return new LinkedHashSet<>(result.stream().sorted().toList());
  }

  private static Path resolvePackage(CommandContext context, String name) {
    for (Path root : packageRoots(context)) {
      Path candidate = root.resolve(name).normalize();
      String fileName = candidate.getFileName() == null ? ""
          : candidate.getFileName().toString();
      // Instance worlds are disposable runtime state, never import sources.
      if (candidate.startsWith(root.normalize()) && Files.isDirectory(candidate)
          && !Files.isSymbolicLink(candidate) && !fileName.startsWith("craftov_")) {
        try {
          Path realRoot = root.toRealPath();
          Path realCandidate = candidate.toRealPath();
          if (realCandidate.startsWith(realRoot)) return realCandidate;
        } catch (java.io.IOException ignored) {
          // A disappearing or unreadable directory is not an import source.
        }
      }
    }
    return null;
  }

  private static List<Path> packageRoots(CommandContext context) {
    Path data = context.plugin().getDataFolder().toPath().toAbsolutePath().normalize();
    Path template = data.resolve(context.serverConfig().mapTemplateDir()).normalize();
    Path imports = data.resolve("imports").normalize();
    Path worlds = context.plugin().getServer().getWorldContainer().toPath()
        .toAbsolutePath().normalize();
    return List.of(imports, template, worlds);
  }

  private static Set<String> worldNames(CommandContext context) {
    Set<String> result = new LinkedHashSet<>();
    Bukkit.getWorlds().forEach(world -> result.add(world.getName()));
    Path root = context.plugin().getServer().getWorldContainer().toPath();
    if (Files.isDirectory(root)) {
      try (Stream<Path> entries = Files.list(root)) {
        entries.filter(Files::isDirectory)
            .filter(path -> Files.isRegularFile(path.resolve("level.dat")))
            .map(path -> path.getFileName().toString())
            .forEach(result::add);
      } catch (java.io.IOException ignored) {
        // Optional disk suggestions are best effort only.
      }
    }
    return new LinkedHashSet<>(result.stream().sorted().toList());
  }

  private static Set<String> suggestedMapIds(CommandContext context) {
    Set<String> result = new LinkedHashSet<>();
    worldNames(context).stream().map(ManualMapCommands::normalizedId).forEach(result::add);
    mapPackages(context).stream().map(ManualMapCommands::normalizedId).forEach(result::add);
    result.removeIf(id -> id.isBlank() || context.maps().find(id).isPresent());
    return new LinkedHashSet<>(result.stream().sorted().toList());
  }

  private static String normalizedId(String value) {
    return value.toLowerCase(java.util.Locale.ROOT)
        .replaceAll("[^a-z0-9_]+", "_")
        .replaceAll("^_+|_+$", "");
  }

  static boolean allowed(CommandContext context, CommandSender sender, String permission) {
    return context.permissionProvider().has(sender, CraftovPermissions.ADMIN)
        || context.permissionProvider().has(sender, permission);
  }

  static int fail(CommandSender sender, String message) {
    sender.sendMessage(Component.text("EscapeFromCraftov | " + message, NamedTextColor.RED));
    return Command.SINGLE_SUCCESS;
  }

  static String reason(Throwable throwable) {
    String message = throwable.getMessage();
    return message == null ? throwable.getClass().getSimpleName() : message;
  }
}
