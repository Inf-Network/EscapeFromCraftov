package com.birditch.escapefromcraftov.game.plugin.editor;

import com.birditch.escapefromcraftov.api.CraftovPermissions;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.loot.LootTableRegistry;
import com.birditch.escapefromcraftov.bukkit.TrMenuBridge;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.core.service.ServiceRegistry;
import com.birditch.escapefromcraftov.game.plugin.integration.PermissionProvider;
import com.birditch.escapefromcraftov.map.AiZoneDefinition;
import com.birditch.escapefromcraftov.map.EditorSession;
import com.birditch.escapefromcraftov.map.EditorSessionService;
import com.birditch.escapefromcraftov.map.ExtractionDefinition;
import com.birditch.escapefromcraftov.map.LootPointDefinition;
import com.birditch.escapefromcraftov.map.LootZoneDefinition;
import com.birditch.escapefromcraftov.map.ManualExtractionType;
import com.birditch.escapefromcraftov.map.ManualMapEditor;
import com.birditch.escapefromcraftov.map.MapObject;
import com.birditch.escapefromcraftov.map.MapObjectType;
import com.birditch.escapefromcraftov.map.PoiDefinition;
import com.birditch.escapefromcraftov.map.SpawnDefinition;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Secure action and dynamic-item backend for the TrMenu editor object menu.
 * TrMenu owns rendering and click cancellation; this controller revalidates
 * every mutation against the live editor session and LuckPerms permissions.
 */
public final class EditorObjectMenuController implements Listener, AutoCloseable {

  private static final ManualMapEditor EDITOR = new ManualMapEditor();
  private static final List<String> SPAWN_TEAMS = List.of("players", "red", "blue", "any");
  private static final List<String> AI_TEAMS = List.of("scav", "neutral", "undead");
  private static final List<String> CONTAINERS =
      List.of("chest", "barrel", "shulker_box", "virtual");

  public enum Action {
    TOGGLE_ENABLED,
    SPAWN_TEAM,
    SPAWN_WEIGHT,
    SPAWN_DIRECTION,
    TIER,
    LOOT_TABLE,
    CONTAINER_TYPE,
    DENSITY,
    DANGER,
    AI_MINIMUM,
    AI_MAXIMUM,
    AI_RESPAWN,
    AI_DIFFICULTY,
    AI_TEAM,
    EXTRACTION_TYPE,
    EXTRACTION_DURATION,
    EXTRACTION_CONDITION,
    TELEPORT,
    DELETE
  }

  private final Plugin plugin;
  private final EditorSessionService sessions;
  private final EditorConfirmationService confirmations;
  private final List<String> lootTables;
  private final List<String> itemIds;
  private final PermissionProvider permissions;
  private final Logger logger;
  private final TrMenuBridge menus;
  private final AutoCloseable itemSource;

  public EditorObjectMenuController(Plugin plugin, EditorSessionService sessions,
      EditorConfirmationService confirmations, ServiceRegistry services,
      PermissionProvider permissions, Logger logger, TrMenuBridge menus) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.sessions = Preconditions.checkNotNull(sessions, "sessions");
    this.confirmations = Preconditions.checkNotNull(confirmations, "confirmations");
    Preconditions.checkNotNull(services, "services");
    this.lootTables = services.require(LootTableRegistry.class).all().stream()
        .map(table -> table.id().value()).sorted().toList();
    this.itemIds = services.require(ItemRegistry.class).all().stream()
        .map(item -> item.id().value()).sorted().toList();
    this.permissions = Preconditions.checkNotNull(permissions, "permissions");
    this.logger = Preconditions.checkNotNull(logger, "logger");
    this.menus = Preconditions.checkNotNull(menus, "menus");
    this.itemSource = menus.registerItemSource("CRAFTOVCOREEDITOR", this::menuItem);
  }

  public void open(Player player) {
    Preconditions.checkNotNull(player, "player");
    EditorSession session = sessions.find(player.getUniqueId()).orElseThrow(() ->
        new IllegalStateException("你当前不在地图编辑器中"));
    String id = session.selectedObjectId().orElseThrow(() ->
        new IllegalStateException("尚未选择地图对象"));
    if (session.draft().findObject(id).isEmpty()) {
      throw new IllegalStateException("选中的地图对象已不存在");
    }
    if (!menus.open(player, "escapefromcraftov_editor_object")) {
      throw new IllegalStateException("TrMenu 无法打开地图对象菜单");
    }
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    menus.close(event.getPlayer(), "escapefromcraftov_editor_object");
  }

  public void execute(Player player, String rawAction, boolean reverse) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(rawAction, "rawAction");
    if (!menus.isViewing(player, "escapefromcraftov_editor_object")) {
      throw new IllegalStateException("请先打开地图对象属性菜单");
    }
    EditorSession session = sessions.find(player.getUniqueId()).orElseThrow(() ->
        new IllegalStateException("你当前不在地图编辑器中"));
    String objectId = session.selectedObjectId().orElseThrow(() ->
        new IllegalStateException("尚未选择地图对象"));
    MapObject object = session.draft().findObject(objectId).orElseThrow(() ->
        new IllegalStateException("对象已不存在"));
    Action action = resolveAction(object, rawAction);
    try {
      route(player, session, objectId, action, reverse);
    } catch (RuntimeException error) {
      logger.log(Level.WARNING, "TrMenu 编辑器属性操作失败: " + objectId, error);
      throw error;
    }
  }

  private ItemStack menuItem(Player player, String id) {
    EditorSession session = sessions.find(player.getUniqueId()).orElse(null);
    if (session == null) return air();
    MapObject object = session.selectedObjectId().flatMap(session.draft()::findObject).orElse(null);
    if (object == null) return air();
    String property = propertyId(object, id);
    if (property != null) id = property;
    return switch (id.toLowerCase(Locale.ROOT)) {
      case "summary" -> button(icon(object), object.type() + " · " + object.id(),
          "坐标: " + coordinate(object), object.enabled() ? "状态: 已启用" : "状态: 已停用");
      case "toggle_enabled" -> button(object.enabled() ? Material.LIME_DYE : Material.GRAY_DYE,
          object.enabled() ? "停用对象" : "启用对象", "立即写入当前编辑稿");
      case "spawn_team" -> object instanceof SpawnDefinition value
          ? button(Material.WHITE_BANNER, "出生队伍: " + value.team(), "左键/右键轮换队伍")
          : air();
      case "spawn_weight" -> object instanceof SpawnDefinition value
          ? button(Material.HEAVY_WEIGHTED_PRESSURE_PLATE, "出生权重: " + value.weight(),
              "左键 +0.25 · 右键 -0.25") : air();
      case "spawn_direction" -> object instanceof SpawnDefinition
          ? button(Material.COMPASS, "记录当前朝向", "将出生朝向设为你现在的视角") : air();
      case "tier" -> switch (object) {
        case LootPointDefinition value -> tier(value.tier());
        case LootZoneDefinition value -> tier(value.tier());
        case PoiDefinition value -> tier(value.tier());
        default -> air();
      };
      case "loot_table" -> switch (object) {
        case LootPointDefinition value -> button(Material.CHEST,
            "物资组合: " + value.lootTable(), "左键/右键轮换物资组合");
        case LootZoneDefinition value -> button(Material.CHEST,
            "物资组合: " + value.lootTable(), "左键/右键轮换物资组合");
        default -> air();
      };
      case "container_type" -> object instanceof LootPointDefinition value
          ? button(Material.BARREL, "容器外观: " + value.containerType(),
              "左键/右键轮换容器类型") : air();
      case "density" -> object instanceof LootZoneDefinition value
          ? button(Material.HONEYCOMB, "物资密度: " + value.density(),
              "左键 +0.25 · 右键 -0.25") : air();
      case "danger" -> object instanceof LootZoneDefinition value
          ? button(Material.REDSTONE, "危险程度: " + value.danger(),
              "左键 +0.1 · 右键 -0.1") : air();
      case "ai_minimum" -> object instanceof AiZoneDefinition value
          ? button(Material.ZOMBIE_HEAD, "最少生物: " + value.minimum(), "左键 +1 · 右键 -1")
          : air();
      case "ai_maximum" -> object instanceof AiZoneDefinition value
          ? button(Material.SKELETON_SKULL, "最多生物: " + value.maximum(), "左键 +1 · 右键 -1")
          : air();
      case "ai_respawn" -> object instanceof AiZoneDefinition value
          ? button(Material.CLOCK, "重新出现: " + value.respawnSeconds() + " 秒",
              "左键 +15 · 右键 -15") : air();
      case "ai_difficulty" -> object instanceof AiZoneDefinition value
          ? button(Material.IRON_SWORD, "强度倍率: " + value.difficulty(),
              "左键 +0.25 · 右键 -0.25") : air();
      case "ai_team" -> object instanceof AiZoneDefinition value
          ? button(Material.SHIELD, "生物阵营: " + value.team(), "左键/右键轮换阵营") : air();
      case "extraction_type" -> object instanceof ExtractionDefinition value
          ? button(Material.ENDER_EYE, "撤离方式: " + value.extractionType(),
              "左键/右键轮换撤离条件") : air();
      case "extraction_duration" -> object instanceof ExtractionDefinition value
          ? button(Material.CLOCK, "撤离等待: " + value.durationSeconds() + " 秒",
              "左键 +5 · 右键 -5") : air();
      case "extraction_condition" -> object instanceof ExtractionDefinition value
          ? button(Material.TRIPWIRE_HOOK,
              "条件: " + (value.conditionValue() == null ? "无" : value.conditionValue()),
              "物品条件轮换物品 · 数字条件按步进调整") : air();
      case "teleport" -> button(Material.ENDER_PEARL, "传送到对象", "安全传送到对象附近");
      case "delete" -> button(Material.LAVA_BUCKET, "删除对象",
          "危险操作 · 点击后仍需 /craftov editor confirm");
      default -> air();
    };
  }

  private static Action resolveAction(MapObject object, String rawAction) {
    String property = propertyId(object, rawAction);
    String resolved = property == null ? rawAction : property;
    try {
      return Action.valueOf(resolved.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("此对象不支持操作: " + rawAction, error);
    }
  }

  private static String propertyId(MapObject object, String slot) {
    return switch (slot.toLowerCase(Locale.ROOT)) {
      case "slot_a" -> switch (object) {
        case SpawnDefinition _ -> "spawn_team";
        case LootPointDefinition _, LootZoneDefinition _, PoiDefinition _ -> "tier";
        case AiZoneDefinition _ -> "ai_minimum";
        case ExtractionDefinition _ -> "extraction_type";
      };
      case "slot_b" -> switch (object) {
        case SpawnDefinition _ -> "spawn_weight";
        case LootPointDefinition _, LootZoneDefinition _ -> "loot_table";
        case AiZoneDefinition _ -> "ai_maximum";
        case ExtractionDefinition _ -> "extraction_duration";
        case PoiDefinition _ -> null;
      };
      case "slot_c" -> switch (object) {
        case SpawnDefinition _ -> "spawn_direction";
        case LootPointDefinition _ -> "container_type";
        case LootZoneDefinition _ -> "density";
        case AiZoneDefinition _ -> "ai_respawn";
        case ExtractionDefinition _ -> "extraction_condition";
        case PoiDefinition _ -> null;
      };
      case "slot_d" -> switch (object) {
        case LootZoneDefinition _ -> "danger";
        case AiZoneDefinition _ -> "ai_difficulty";
        default -> null;
      };
      case "slot_e" -> object instanceof AiZoneDefinition ? "ai_team" : null;
      default -> null;
    };
  }

  private void route(Player player, EditorSession session, String objectId, Action action,
      boolean reverse) {
    MapObject current = session.draft().findObject(objectId).orElseThrow(() ->
        new IllegalStateException("对象已不存在"));
    if (!canEdit(player, current.type(), action)) {
      menus.close(player, "escapefromcraftov_editor_object");
      throw new IllegalStateException("你没有修改此类地图对象的权限");
    }
    if (action == Action.TELEPORT) {
      teleport(player, session, current);
      menus.close(player, "escapefromcraftov_editor_object");
      return;
    }
    if (action == Action.DELETE) {
      confirmations.request(player.getUniqueId(), "删除对象 " + objectId, () -> {
        MapObject object = session.draft().findObject(objectId).orElse(null);
        if (object == null || !canEdit(player, object.type(), action)) {
          if (player.isOnline()) player.sendMessage(Component.text(
              "删除确认已失效：对象不存在或权限已变化。", NamedTextColor.RED));
          return;
        }
        session.apply(map -> EDITOR.remove(map, objectId));
        session.select(null);
        player.sendMessage(Component.text("已删除 " + objectId + "，尚未保存。",
            NamedTextColor.YELLOW));
      });
      menus.close(player, "escapefromcraftov_editor_object");
      player.sendMessage(Component.text("再次确认请输入 /craftov editor confirm",
          NamedTextColor.RED));
      return;
    }
    MapObject updated = update(player, current, action, reverse);
    if (!updated.equals(current)) {
      session.apply(map -> EDITOR.replace(map, updated));
      player.sendActionBar(Component.text("已更新 " + objectId + " · 尚未保存",
          NamedTextColor.YELLOW));
    }
    Bukkit.getScheduler().runTask(plugin, () -> {
      if (player.isOnline() && sessions.find(player.getUniqueId()).orElse(null) == session
          && session.draft().findObject(objectId).isPresent()) open(player);
    });
  }

  private boolean canEdit(Player player, MapObjectType type, Action action) {
    if (player.hasPermission(CraftovPermissions.ADMIN)) return true;
    String permission = switch (type) {
      case SPAWN -> CraftovPermissions.EDITOR_SPAWN;
      case LOOT, LOOT_ZONE -> CraftovPermissions.EDITOR_LOOT;
      case AI_ZONE -> CraftovPermissions.EDITOR_AI;
      case EXTRACTION -> CraftovPermissions.EDITOR_EXTRACTION;
      case POI -> CraftovPermissions.EDITOR_POI;
    };
    if (action == Action.DELETE) permission = CraftovPermissions.EDITOR_DELETE;
    return permissions.has(player, permission);
  }

  private MapObject update(Player player, MapObject object, Action action, boolean reverse) {
    int direction = reverse ? -1 : 1;
    if (action == Action.TOGGLE_ENABLED) return enabled(object, !object.enabled());
    return switch (object) {
      case SpawnDefinition value -> switch (action) {
        case SPAWN_TEAM -> new SpawnDefinition(value.id(), value.position(), value.yaw(),
            value.pitch(), cycle(SPAWN_TEAMS, value.team(), direction), value.weight(),
            value.enabled(), value.spawnRegion());
        case SPAWN_WEIGHT -> new SpawnDefinition(value.id(), value.position(), value.yaw(),
            value.pitch(), value.team(), Math.max(0.25, value.weight() + direction * 0.25),
            value.enabled(), value.spawnRegion());
        case SPAWN_DIRECTION -> new SpawnDefinition(value.id(), value.position(),
            player.getLocation().getYaw(), player.getLocation().getPitch(), value.team(),
            value.weight(), value.enabled(), value.spawnRegion());
        default -> value;
      };
      case LootPointDefinition value -> switch (action) {
        case TIER -> new LootPointDefinition(value.id(), value.position(), value.containerType(),
            value.lootTable(), wrap(value.tier(), direction, 1, 4), value.virtualContainer(),
            value.enabled());
        case LOOT_TABLE -> new LootPointDefinition(value.id(), value.position(),
            value.containerType(), cycle(lootTables, value.lootTable(), direction), value.tier(),
            value.virtualContainer(), value.enabled());
        case CONTAINER_TYPE -> {
          String container = cycle(CONTAINERS, value.containerType(), direction);
          yield new LootPointDefinition(value.id(), value.position(), container, value.lootTable(),
              value.tier(), container.equals("virtual"), value.enabled());
        }
        default -> value;
      };
      case LootZoneDefinition value -> switch (action) {
        case TIER -> new LootZoneDefinition(value.id(), value.bounds(), value.lootTable(),
            wrap(value.tier(), direction, 1, 4), value.density(), value.danger(), value.enabled());
        case LOOT_TABLE -> new LootZoneDefinition(value.id(), value.bounds(),
            cycle(lootTables, value.lootTable(), direction), value.tier(), value.density(),
            value.danger(), value.enabled());
        case DENSITY -> new LootZoneDefinition(value.id(), value.bounds(), value.lootTable(),
            value.tier(), Math.max(0.25, value.density() + direction * 0.25), value.danger(),
            value.enabled());
        case DANGER -> new LootZoneDefinition(value.id(), value.bounds(), value.lootTable(),
            value.tier(), value.density(), clamp(value.danger() + direction * 0.1, 0, 1),
            value.enabled());
        default -> value;
      };
      case AiZoneDefinition value -> switch (action) {
        case AI_MINIMUM -> {
          int minimum = Math.max(0, value.minimum() + direction);
          yield new AiZoneDefinition(value.id(), value.bounds(), value.aiType(), minimum,
              Math.max(minimum, value.maximum()), value.respawnSeconds(), value.difficulty(),
              value.team(), value.enabled());
        }
        case AI_MAXIMUM -> new AiZoneDefinition(value.id(), value.bounds(), value.aiType(),
            value.minimum(), Math.max(value.minimum(), value.maximum() + direction),
            value.respawnSeconds(), value.difficulty(), value.team(), value.enabled());
        case AI_RESPAWN -> new AiZoneDefinition(value.id(), value.bounds(), value.aiType(),
            value.minimum(), value.maximum(), Math.max(0, value.respawnSeconds() + direction * 15),
            value.difficulty(), value.team(), value.enabled());
        case AI_DIFFICULTY -> new AiZoneDefinition(value.id(), value.bounds(), value.aiType(),
            value.minimum(), value.maximum(), value.respawnSeconds(),
            Math.max(0.25, value.difficulty() + direction * 0.25), value.team(), value.enabled());
        case AI_TEAM -> new AiZoneDefinition(value.id(), value.bounds(), value.aiType(),
            value.minimum(), value.maximum(), value.respawnSeconds(), value.difficulty(),
            cycle(AI_TEAMS, value.team(), direction), value.enabled());
        default -> value;
      };
      case ExtractionDefinition value -> switch (action) {
        case EXTRACTION_TYPE -> extractionType(value, direction);
        case EXTRACTION_DURATION -> new ExtractionDefinition(value.id(), value.displayName(),
            value.bounds(), value.extractionType(),
            Math.max(1, value.durationSeconds() + direction * 5), value.conditionValue(),
            value.enabled());
        case EXTRACTION_CONDITION -> extractionCondition(value, direction);
        default -> value;
      };
      case PoiDefinition value -> action == Action.TIER
          ? new PoiDefinition(value.id(), value.displayName(), value.bounds(),
              wrap(value.tier(), direction, 1, 4), value.enabled()) : value;
    };
  }

  private ExtractionDefinition extractionType(ExtractionDefinition value, int direction) {
    ManualExtractionType type = cycle(List.of(ManualExtractionType.values()),
        value.extractionType(), direction);
    String condition = switch (type) {
      case NORMAL -> null;
      case ITEM_REQUIRED -> itemIds.isEmpty() ? "extraction_key" : itemIds.getFirst();
      case PAID -> "100";
      case TIMED -> "600";
    };
    return new ExtractionDefinition(value.id(), value.displayName(), value.bounds(), type,
        value.durationSeconds(), condition, value.enabled());
  }

  private ExtractionDefinition extractionCondition(ExtractionDefinition value, int direction) {
    String condition = switch (value.extractionType()) {
      case NORMAL -> null;
      case ITEM_REQUIRED -> cycle(itemIds, value.conditionValue(), direction);
      case PAID -> Long.toString(Math.max(0, parseLong(value.conditionValue()) + direction * 50));
      case TIMED -> Long.toString(Math.max(0, parseLong(value.conditionValue()) + direction * 60));
    };
    return new ExtractionDefinition(value.id(), value.displayName(), value.bounds(),
        value.extractionType(), value.durationSeconds(), condition, value.enabled());
  }

  private static MapObject enabled(MapObject object, boolean enabled) {
    return switch (object) {
      case SpawnDefinition value -> new SpawnDefinition(value.id(), value.position(), value.yaw(),
          value.pitch(), value.team(), value.weight(), enabled, value.spawnRegion());
      case LootPointDefinition value -> new LootPointDefinition(value.id(), value.position(),
          value.containerType(), value.lootTable(), value.tier(), value.virtualContainer(), enabled);
      case LootZoneDefinition value -> new LootZoneDefinition(value.id(), value.bounds(),
          value.lootTable(), value.tier(), value.density(), value.danger(), enabled);
      case AiZoneDefinition value -> new AiZoneDefinition(value.id(), value.bounds(),
          value.aiType(), value.minimum(), value.maximum(), value.respawnSeconds(),
          value.difficulty(), value.team(), enabled);
      case ExtractionDefinition value -> new ExtractionDefinition(value.id(), value.displayName(),
          value.bounds(), value.extractionType(), value.durationSeconds(), value.conditionValue(),
          enabled);
      case PoiDefinition value -> new PoiDefinition(value.id(), value.displayName(), value.bounds(),
          value.tier(), enabled);
    };
  }

  private static void teleport(Player player, EditorSession session, MapObject object) {
    if (!player.getWorld().getName().equals(session.draft().worldName())) {
      throw new IllegalStateException("请先前往地图世界: " + session.draft().worldName());
    }
    Location safe = SafeEditorTeleport.find(player.getWorld(), object.anchor(),
        player.getLocation().getYaw(), player.getLocation().getPitch());
    if (safe == null) throw new IllegalStateException("对象附近没有安全的落脚位置");
    player.teleport(safe);
  }

  private static ItemStack tier(int value) {
    return button(Material.EXPERIENCE_BOTTLE, "区域等级: " + value,
        "左键 +1 · 右键 -1 · 范围 1~4");
  }

  private static ItemStack button(Material material, String name, String... lore) {
    ItemStack item = new ItemStack(material);
    item.editMeta(meta -> {
      meta.itemName(Component.text(name, NamedTextColor.GOLD));
      meta.lore(java.util.Arrays.stream(lore)
          .map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
    });
    return item;
  }

  private static ItemStack air() {
    return new ItemStack(Material.AIR);
  }

  private static Material icon(MapObject object) {
    return switch (object.type()) {
      case SPAWN -> Material.LIME_BANNER;
      case LOOT -> Material.CHEST;
      case LOOT_ZONE -> Material.YELLOW_WOOL;
      case AI_ZONE -> Material.ZOMBIE_HEAD;
      case EXTRACTION -> Material.LIGHT_BLUE_WOOL;
      case POI -> Material.PURPLE_WOOL;
    };
  }

  private static String coordinate(MapObject object) {
    return object.anchor().x() + ", " + object.anchor().y() + ", " + object.anchor().z();
  }

  private static int wrap(int value, int direction, int minimum, int maximum) {
    return minimum + Math.floorMod(value - minimum + direction, maximum - minimum + 1);
  }

  private static <T> T cycle(List<T> values, T current, int direction) {
    if (values.isEmpty()) throw new IllegalStateException("没有可轮换的配置项");
    int index = values.indexOf(current);
    if (index < 0) index = 0;
    return values.get(Math.floorMod(index + direction, values.size()));
  }

  private static double clamp(double value, double minimum, double maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  private static long parseLong(String value) {
    try {
      return value == null ? 0 : Long.parseLong(value);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  @Override
  public void close() throws Exception {
    for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
      menus.close(player, "escapefromcraftov_editor_object");
    }
    itemSource.close();
  }
}
