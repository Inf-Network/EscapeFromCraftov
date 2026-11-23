package net.infnetwork.snowball.craftov.map.editor;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.gui.UiItems;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.loot.ContainerType;
import net.infnetwork.snowball.craftov.map.ExtractType;
import net.infnetwork.snowball.craftov.map.MapDef;
import net.infnetwork.snowball.craftov.map.MapRegistry;
import net.infnetwork.snowball.craftov.map.Pos;
import net.infnetwork.snowball.craftov.map.WorldService;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Bisected;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Click-to-place map editor. An admin stands in the template world, takes the tool kit and
 * right-clicks blocks: spawns, extracts, containers, AI, bosses, doors and switches are dropped on
 * the clicked block and shown with floating labels only editors can see.
 *
 * <p>Saving (the tool, /cv editor save or exit, leaving the server, a shutdown) only ever writes a
 * draft: a new map stays off the deploy screen, and changes to a published one go to
 * maps/drafts/ while raids keep using the live file. {@code /cv editor publish} makes the draft
 * live, and refuses a map that cannot carry a raid (see {@link MapRegistry#publishProblems}).
 */
public final class EditorService implements Listener {

    private enum Tool {
        SPAWN("出生点", "craftov:ui/icon_party"), EXTRACT("撤离点", "craftov:ui/icon_map"), CONTAINER("物资箱", "craftov:ui/icon_stash"),
        AI("敌兵", "craftov:ui/icon_deploy"), BOSS("首领", "craftov:ui/icon_rank"), LOCK("门禁/电闸", "craftov:ui/locked"),
        REMOVE("删除", "craftov:ui/icon_cancel"), SCAN("扫描箱子", "craftov:ui/search_active"), SAVE("保存/边界", "craftov:ui/icon_start");

        final String label;
        final String icon;

        Tool(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private static final String[] AI_KINDS = {"patrol", "guard", "sniper"};
    private static final ExtractType[] EXTRACT_TYPES = ExtractType.values();

    private final class Session {
        final MapDraft draft;
        /** The world the markers belong to: tools do nothing anywhere else, and labels go here. */
        final World world;
        final Map<Integer, ItemRecord> inventory;
        final GameMode mode;
        final Map<Tool, Integer> option = new HashMap<>();
        final List<TextDisplay> labels = new ArrayList<>();
        @Nullable Location corner;

        Session(MapDraft draft, World world, Map<Integer, ItemRecord> inventory, GameMode mode) {
            this.draft = draft;
            this.world = world;
            this.inventory = inventory;
            this.mode = mode;
        }
    }

    private final CraftovPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public EditorService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean editing(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    // ------------------------------------------------------------------ commands

    /**
     * World names and map ids: both become file names (the world folder, maps/&lt;id&gt;.yml), so
     * nothing that could leave the folder ("../config") gets through. The built-in maps fit.
     */
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[a-z0-9_]{3,32}");

    public void createWorld(Player player, String name) {
        if (!NAME.matcher(name).matches()) {
            Text.send(player, "<#FF8A80>世界名只能包含小写字母、数字和下划线，长度 3–32。");
            return;
        }
        World world = Bukkit.getWorld(name);
        if (world == null) {
            world = WorldService.creator(name).createWorld();
        }
        if (world == null) {
            Text.send(player, "<#FF8A80>无法创建或加载世界。");
            return;
        }
        WorldService.applyRules(world, false);
        Location spawn = world.getSpawnLocation();
        if (spawn.getBlock().getRelative(0, -1, 0).getType().isAir()) {
            world.getBlockAt(spawn.getBlockX(), spawn.getBlockY() - 1, spawn.getBlockZ()).setType(Material.STONE);
        }
        player.setGameMode(GameMode.CREATIVE);
        player.teleport(spawn.add(0.5, 0, 0.5));
        Text.send(player, "<#5BD16B>已进入建图世界 <white>" + name + "</white>。建好后用 <white>/cv editor start <地图ID></white> 开始标点。");
    }

    public void start(Player player, String typed) {
        if (editing(player)) {
            Text.send(player, "<#FF8A80>你已经在编辑中，先 /cv editor exit。");
            return;
        }
        String id = typed.toLowerCase(Locale.ROOT);
        // Only a new id has to fit NAME. A map saved before ids were checked ("废弃工厂", "my-map")
        // still opens: its file is already in maps/, and MapRegistry.file keeps the save there.
        MapDef existing = plugin.maps().get(typed);
        if (existing == null) {
            existing = plugin.maps().get(id);
        }
        if (existing == null && !NAME.matcher(id).matches()) {
            Text.send(player, "<#FF8A80>新地图的ID只能包含字母、数字和下划线，长度 3–32（保存为 maps/<地图ID>.yml）。");
            return;
        }
        World world = player.getWorld();
        if (plugin.lobby().isLobby(world) || plugin.raids().isRaidWorld(world)) {
            Text.send(player, "<#FF8A80>请在用于建图的世界中执行（/cv editor world <名字> 创建一个空世界）。");
            return;
        }
        Player other = existing == null ? null : editorOf(existing.id());
        if (other != null) {
            Text.send(player, "<#FF8A80>" + Text.escape(other.getName()) + " 正在编辑这张地图，两份草稿会互相覆盖。");
            return;
        }
        MapDraft draft;
        if (existing != null) {
            if (!existing.template().equals(world.getName())) {
                Text.send(player, "<#FF8A80>地图 " + Text.escape(existing.id()) + " 绑定的世界是 " + existing.template() + "，请先前往该世界。");
                return;
            }
            MapDef saved;
            try {
                // A published map picks up where its last saved draft left off.
                saved = existing.enabled() ? plugin.maps().loadDraft(existing.id()) : null;
            } catch (IOException ex) {
                Text.send(player, "<#FF8A80>草稿读取失败：" + Text.escape(ex.getMessage()) + "<#9AA0A6>（修好或删掉这个文件再开始）");
                return;
            }
            draft = MapDraft.of(saved != null ? saved : existing);
            if (saved != null) {
                Text.send(player, "<#9AA0A6>继续编辑上次保存、还没发布的草稿。");
            }
        } else {
            draft = new MapDraft();
            draft.id = id;
            draft.name = typed;
            draft.template = world.getName();
        }
        plugin.weapons().reset(player);
        Session session = new Session(draft, world, plugin.carry().snapshot(player), player.getGameMode());
        sessions.put(player.getUniqueId(), session);
        player.getInventory().clear();
        player.setGameMode(GameMode.CREATIVE);
        giveTools(player, session);
        refreshLabels(player, session);
        Text.send(player, "<#5BD16B>开始编辑地图 <white>" + Text.escape(draft.name) + "</white> (" + Text.escape(draft.id) + ")");
        Text.send(player, "<#9AA0A6>右键方块放置标记，潜行+右键空气切换工具选项，删除工具右键靠近标记处删除。");
        Text.send(player, "<#9AA0A6>用“保存”工具或 <white>/cv editor save</white> 保存草稿，<white>/cv editor publish</white> 发布后才会出现在「开始行动」里；"
                + "<white>/cv editor set name|minutes|time <值></white> 修改属性。");
    }

    /** The player editing this map, or null. */
    private @Nullable Player editorOf(String mapId) {
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            if (entry.getValue().draft.id.equals(mapId)) {
                return Bukkit.getPlayer(entry.getKey());
            }
        }
        return null;
    }

    public void exit(Player player, boolean save) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            Text.send(player, "<#9AA0A6>你不在编辑模式。");
            return;
        }
        if (save && !save(player, session)) {
            Text.send(player, "<#FF8A80>草稿没有保存，仍在编辑模式。<#9AA0A6>不要保存就退出用 /cv editor discard。");
            return;
        }
        close(player, session);
    }

    /** Ends the session: back to the inventory and game mode the editor had, labels gone. */
    private void close(Player player, Session session) {
        sessions.remove(player.getUniqueId());
        clearLabels(session);
        player.getInventory().clear();
        plugin.carry().apply(player, session.inventory, UiItems.lobbyMenu());
        player.setGameMode(session.mode);
        Text.send(player, "<#9AA0A6>已退出编辑模式。");
    }

    public void save(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            Text.send(player, "<#9AA0A6>你不在编辑模式。");
            return;
        }
        save(player, session);
    }

    /**
     * Saves the draft: maps/&lt;id&gt;.yml (unpublished) for a map nobody has published yet,
     * maps/drafts/&lt;id&gt;.yml for a published one, whose live file stays as it is. Returns
     * whether it was written.
     */
    private boolean save(Player player, Session session) {
        MapDraft d = session.draft;
        MapDef def = d.build();
        MapDef live = plugin.maps().get(d.id);
        try {
            session.world.save();
            if (live != null && live.enabled()) {
                plugin.maps().saveDraft(def);
            } else {
                plugin.maps().save(def);
            }
        } catch (IOException ex) {
            Text.send(player, "<#FF8A80>保存失败：" + Text.escape(ex.getMessage()));
            return false;
        }
        Text.send(player, "<#5BD16B>草稿已保存<#9AA0A6>（未发布）<#5BD16B>：出生点 " + d.spawns.size() + " · 撤离点 " + d.extracts.size()
                + " · 物资箱 " + d.containers.size() + " · 敌兵点 " + d.ai.size() + " · 首领 " + d.bosses.size());
        List<String> missing = MapRegistry.publishProblems(def, squads());
        Text.send(player, missing.isEmpty()
                ? "<#9AA0A6>已满足发布条件：<white>/cv editor publish</white> 发布后，下一局起出现在「开始行动」里。"
                : "<#FFC23D>发布前还需要：" + Text.escape(String.join("；", missing)));
        List<String> hints = new ArrayList<>();
        for (var close : MapRegistry.closeSpawns(def)) {
            hints.add(String.format(Locale.ROOT, "%s点 (%.0f, %.0f, %.0f) 离第 %d 组出生点只有 %.0f 格（建议 %d 格以上）",
                    "boss".equals(close.kind()) ? "首领" : "敌兵", close.post().x(), close.post().y(), close.post().z(),
                    close.group(), close.distance(), (int) MapRegistry.SPAWN_CLEARANCE));
        }
        if (!hints.isEmpty()) {
            Text.send(player, "<#FFC23D>提示：" + String.join("、", hints));
        }
        return true;
    }

    /** Squads one raid takes (match.max-squads): a published map needs a spawn group for each. */
    private int squads() {
        return Math.max(1, plugin.getConfig().getInt("match.max-squads", 4));
    }

    /**
     * /cv editor publish [id]: in a session (for its own map) saves and leaves first; otherwise
     * publishes what was saved. Refused, with every reason listed, while the map cannot carry a
     * raid.
     */
    public void publish(Player player, @Nullable String typed) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && (typed == null || typed.equalsIgnoreCase(session.draft.id))) {
            List<String> problems = MapRegistry.publishProblems(session.draft.build(), squads());
            if (!problems.isEmpty()) {
                refuse(player, session.draft.name, problems);
                return;
            }
            if (!save(player, session)) {
                return;
            }
            close(player, session);
            publishSaved(player, session.draft.id);
            return;
        }
        if (typed == null) {
            Text.send(player, "<#9AA0A6>用法：/cv editor publish <地图ID>（编辑中可以省略）");
            return;
        }
        publishSaved(player, typed);
    }

    private void publishSaved(Player player, String typed) {
        MapDef live = plugin.maps().get(typed);
        if (live == null) {
            live = plugin.maps().get(typed.toLowerCase(Locale.ROOT));
        }
        if (live == null) {
            Text.send(player, "<#FF8A80>没有地图 " + Text.escape(typed) + "。");
            return;
        }
        String id = live.id();
        Player other = editorOf(id);
        if (other != null) {
            Text.send(player, "<#FF8A80>" + Text.escape(other.getName()) + " 正在编辑这张地图，等他保存退出后再发布。");
            return;
        }
        MapDef def;
        if (live.enabled()) {
            try {
                def = plugin.maps().loadDraft(id);
            } catch (IOException ex) {
                Text.send(player, "<#FF8A80>草稿读取失败：" + Text.escape(ex.getMessage()));
                return;
            }
            if (def == null) {
                Text.send(player, "<#9AA0A6>地图 " + Text.escape(live.name()) + " 已经发布，没有未发布的改动。");
                return;
            }
        } else {
            def = live;
        }
        List<String> problems = MapRegistry.publishProblems(def, squads());
        if (!problems.isEmpty()) {
            refuse(player, def.name(), problems);
            return;
        }
        if (!new java.io.File(Bukkit.getWorldContainer(), def.template()).isDirectory()) {
            Text.send(player, "<#FF8A80>不能发布：模板世界 " + Text.escape(def.template()) + " 不存在。");
            return;
        }
        World template = Bukkit.getWorld(def.template());
        if (template != null) {
            template.save();
        }
        if (live.enabled()) {
            plugin.maps().keepPrevious(id);
        }
        try {
            plugin.maps().save(def.withEnabled(true));
        } catch (IOException ex) {
            Text.send(player, "<#FF8A80>发布失败：" + Text.escape(ex.getMessage()));
            return;
        }
        plugin.maps().deleteDraft(id);
        plugin.getLogger().info(player.getName() + " published map " + id + " (template " + def.template() + ")");
        Text.send(player, "<#5BD16B>地图 <white>" + Text.escape(def.name()) + "</white> 已发布，下一局起出现在「开始行动」里。");
    }

    private static void refuse(Player player, String name, List<String> problems) {
        Text.send(player, "<#FF8A80>地图 " + Text.escape(name) + " 还不能发布：");
        for (String problem : problems) {
            player.sendMessage(Text.mm("<#FF8A80> · " + Text.escape(problem)));
        }
    }

    public void set(Player player, String key, String value) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            Text.send(player, "<#9AA0A6>你不在编辑模式。");
            return;
        }
        // Every key but camera needs a value: without one, the list of keys below.
        switch (value.isBlank() && !key.equals("camera") ? "" : key) {
            case "name" -> session.draft.name = value;
            case "description", "desc" -> session.draft.description = value;
            case "minutes" -> {
                int minutes;
                try {
                    minutes = Integer.parseInt(value.trim());
                } catch (IllegalArgumentException ex) {
                    Text.send(player, "<#9AA0A6>用法：/cv editor set minutes <分钟数>（至少 5）");
                    return;
                }
                session.draft.raidMinutes = Math.max(5, minutes);
            }
            case "time" -> session.draft.time = value;
            case "test" -> session.draft.test = Boolean.parseBoolean(value);
            case "camera" -> session.draft.camera = Pos.of(player.getEyeLocation());
            default -> {
                Text.send(player, "<#FF8A80>可设置：name / description / minutes / time(day|dusk|night|dawn) / test(true|false) / camera");
                return;
            }
        }
        Text.send(player, "<#5BD16B>已设置 " + key + " = " + Text.escape(value));
    }

    public void info(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            Text.send(player, "<#9AA0A6>你不在编辑模式。");
            return;
        }
        MapDraft d = session.draft;
        Text.send(player, "<white>" + Text.escape(d.name) + " <#8A8F98>(" + d.id + ") 世界 " + d.template + " · " + d.raidMinutes + " 分钟 · " + d.time
                + (d.test ? " · <#FFB74D>测试图" : ""));
        Text.send(player, "<#9AA0A6>出生点 " + d.spawns.size() + " · 撤离点 " + d.extracts.size() + " · 物资箱 " + d.containers.size()
                + " · 敌兵 " + d.ai.size() + " · 首领 " + d.bosses.size() + " · 门禁 " + d.doors.size() + " · 电闸 " + d.switches.size());
        MapDef live = plugin.maps().get(d.id);
        Text.send(player, live != null && live.enabled()
                ? "<#9AA0A6>已发布的地图：改动存为草稿，发布前对局照旧用线上版本。"
                : "<#9AA0A6>还没发布：不会出现在「开始行动」里。");
        List<String> missing = MapRegistry.publishProblems(d.build(), squads());
        Text.send(player, missing.isEmpty() ? "<#5BD16B>已满足发布条件。"
                : "<#FFC23D>发布前还需要：" + Text.escape(String.join("；", missing)));
    }

    // ------------------------------------------------------------------ tools

    private void giveTools(Player player, Session session) {
        Tool[] tools = Tool.values();
        for (int i = 0; i < tools.length; i++) {
            player.getInventory().setItem(i, toolItem(session, tools[i]));
        }
    }

    private ItemStack toolItem(Session session, Tool tool) {
        String option = optionLabel(session, tool);
        return UiItems.button(tool.icon, "<#F2C94C>" + tool.label + (option.isEmpty() ? "" : " <#9AA0A6>[" + option + "]"),
                List.of("<#9AA0A6>右键方块：" + switch (tool) {
                    case SPAWN -> "放置出生点（同组为同一小队的出生位置）";
                    case EXTRACT -> "放置撤离点";
                    case CONTAINER -> "放置物资箱（朝向跟随视角）";
                    case AI -> "放置敌兵刷新点";
                    case BOSS -> "放置首领刷新点";
                    case LOCK -> "点击铁门设为门禁 / 点击拉杆或按钮设为电闸";
                    case REMOVE -> "删除 3 格内最近的标记";
                    case SCAN -> "把附近 64 格内的箱子/木桶等转换为物资箱";
                    case SAVE -> "保存草稿（潜行右键方块：依次设置边界两个角）";
                }, "<#9AA0A6>潜行+右键空气：切换选项"), "editor_" + tool.name());
    }

    private String optionLabel(Session session, Tool tool) {
        int o = session.option.getOrDefault(tool, 0);
        return switch (tool) {
            case SPAWN -> "第 " + (o + 1) + " 组";
            case EXTRACT -> EXTRACT_TYPES[o % EXTRACT_TYPES.length].displayName();
            case CONTAINER -> {
                List<ContainerType> types = containerTypes();
                yield types.isEmpty() ? "" : types.get(o % types.size()).name();
            }
            case AI -> switch (AI_KINDS[o % AI_KINDS.length]) {
                case "guard" -> "驻守";
                case "sniper" -> "狙击手";
                default -> "巡逻";
            };
            case LOCK -> {
                List<ItemDef> keys = plugin.registry().byCategory(Category.KEY);
                yield keys.isEmpty() ? "" : "钥匙:" + keys.get(o % keys.size()).name();
            }
            default -> "";
        };
    }

    private List<ContainerType> containerTypes() {
        List<ContainerType> out = new ArrayList<>();
        for (ContainerType type : plugin.loot().types()) {
            if (!type.id().equals("body_bag") && type.allOf().isEmpty()) {
                out.add(type);
            }
        }
        return out;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        String tag = ItemFactory.uiTag(event.getItem());
        if (session == null || tag == null || !tag.startsWith("editor_") || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        event.setCancelled(true);
        Tool tool = Tool.valueOf(tag.substring("editor_".length()));
        Action action = event.getAction();
        if (player.isSneaking() && action == Action.RIGHT_CLICK_AIR) {
            session.option.merge(tool, 1, Integer::sum);
            player.getInventory().setItemInMainHand(toolItem(session, tool));
            player.sendActionBar(Text.mm("<#F2C94C>" + tool.label + " · " + optionLabel(session, tool)));
            return;
        }
        if (action != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            if (tool == Tool.SAVE && action == Action.RIGHT_CLICK_AIR) {
                save(player, session);
            }
            return;
        }
        if (!player.getWorld().equals(session.world)) {
            // Coordinates from another world would land on whatever stands there in this map.
            player.sendActionBar(Text.mm("<#FF8A80>这里不是正在编辑的世界 " + session.world.getName() + "，标记没有放下"));
            return;
        }
        Block block = event.getClickedBlock();
        Location top = block.getLocation().add(0.5, 1, 0.5);
        float yaw = Math.round(player.getYaw() / 90f) * 90f + 180f;
        MapDraft d = session.draft;
        int o = session.option.getOrDefault(tool, 0);
        switch (tool) {
            case SPAWN -> d.spawns.add(new MapDef.SpawnDef(o + 1, new Pos(top.getX(), top.getY(), top.getZ(), player.getYaw())));
            case EXTRACT -> {
                ExtractType type = EXTRACT_TYPES[o % EXTRACT_TYPES.length];
                String sw = d.switches.isEmpty() ? "" : d.switches.get(d.switches.size() - 1).id();
                int n = nextNumber("extract_", d.extracts.stream().map(MapDef.ExtractDef::id).toList());
                d.extracts.add(new MapDef.ExtractDef("extract_" + n, "撤离点 " + n,
                        new Pos(top.getX(), top.getY(), top.getZ(), 0), 3.5, type, 8,
                        type == ExtractType.PAID ? 10_000 : 0, type == ExtractType.TIMED ? 600 : 0, 0,
                        type == ExtractType.SWITCH ? sw : "", type == ExtractType.LIMITED ? 4 : 0, 1.0));
                if (type == ExtractType.SWITCH && sw.isEmpty()) {
                    Text.send(player, "<#FFC23D>这是“需拉闸”撤离点，请用门禁/电闸工具点击一个拉杆来绑定。");
                }
            }
            case CONTAINER -> {
                List<ContainerType> types = containerTypes();
                if (!types.isEmpty()) {
                    d.containers.add(new MapDef.ContainerDef(types.get(o % types.size()).id(), new Pos(top.getX(), top.getY(), top.getZ(), yaw), 0));
                }
            }
            case AI -> d.ai.add(new MapDef.AiDef(new Pos(top.getX(), top.getY(), top.getZ(), 0), 2, 8, AI_KINDS[o % AI_KINDS.length]));
            case BOSS -> d.bosses.add(new MapDef.BossDef("boss_" + nextNumber("boss_", d.bosses.stream().map(MapDef.BossDef::id).toList()),
                    "首领", new Pos(top.getX(), top.getY(), top.getZ(), 0), 3, 1.0, 120));
            case LOCK -> {
                Material type = block.getType();
                if (type == Material.IRON_DOOR) {
                    Block lower = block.getBlockData() instanceof Bisected b && b.getHalf() == Bisected.Half.TOP ? block.getRelative(0, -1, 0) : block;
                    List<ItemDef> keys = plugin.registry().byCategory(Category.KEY);
                    if (keys.isEmpty()) {
                        return;
                    }
                    ItemDef key = keys.get(o % keys.size());
                    d.doors.add(new MapDef.DoorDef(key.lockId(), new Pos(lower.getX() + 0.5, lower.getY(), lower.getZ() + 0.5, 0)));
                    Text.send(player, "<#5BD16B>已设置门禁，需要钥匙：" + key.coloredName());
                } else if (type == Material.LEVER || type.name().endsWith("_BUTTON")) {
                    // Past the highest number in use, not the count: after a removal the count names
                    // a lever that still exists, and two levers with one id open each other's extracts.
                    int n = nextNumber("switch_", d.switches.stream().map(MapDef.SwitchDef::id).toList());
                    String id = "switch_" + n;
                    d.switches.add(new MapDef.SwitchDef(id, "电闸 " + n,
                            new Pos(block.getX() + 0.5, block.getY(), block.getZ() + 0.5, 0), 300));
                    // Every lever extract still waiting for a switch is bound to this one.
                    List<String> bound = new ArrayList<>();
                    for (int i = 0; i < d.extracts.size(); i++) {
                        MapDef.ExtractDef e = d.extracts.get(i);
                        if (e.type() == ExtractType.SWITCH && e.switchId().isEmpty()) {
                            d.extracts.set(i, withSwitch(e, id));
                            bound.add(e.name());
                        }
                    }
                    Text.send(player, "<#5BD16B>已设置电闸 " + id + (bound.isEmpty() ? ""
                            : "<#9AA0A6>，绑定撤离点：<white>" + Text.escape(String.join("、", bound))));
                } else {
                    Text.send(player, "<#FF8A80>请点击铁门、拉杆或按钮。");
                    return;
                }
            }
            case REMOVE -> {
                Object removed = removeNear(d, block.getLocation().add(0.5, 1, 0.5));
                if (removed == null) {
                    player.sendActionBar(Text.mm("<#FF8A80>附近 3 格内没有标记"));
                    return;
                }
                if (removed instanceof MapDef.SwitchDef sw) {
                    unbind(player, d, sw);
                }
            }
            case SCAN -> {
                int n = scan(d, player.getLocation(), 64);
                Text.send(player, "<#5BD16B>已把 " + n + " 个箱子/木桶转换为物资箱。");
            }
            case SAVE -> {
                if (player.isSneaking()) {
                    if (session.corner == null) {
                        session.corner = block.getLocation();
                        Text.send(player, "<#9AA0A6>已记录边界第一角，再点击另一角。");
                    } else {
                        d.minX = Math.min(session.corner.getX(), block.getX());
                        d.minZ = Math.min(session.corner.getZ(), block.getZ());
                        d.maxX = Math.max(session.corner.getX(), block.getX());
                        d.maxZ = Math.max(session.corner.getZ(), block.getZ());
                        session.corner = null;
                        Text.send(player, "<#5BD16B>已设置地图边界。");
                    }
                } else {
                    save(player, session);
                }
                return;
            }
        }
        player.playSound(player.getLocation(), "minecraft:block.note_block.pling", 0.6f, 1.6f);
        refreshLabels(player, session);
    }

    /** Removes the marker nearest to {@code at} within 3 blocks and returns it, or null if there is none. */
    private static @Nullable Object removeNear(MapDraft d, Location at) {
        double best = 9;
        List<?> from = null;
        Object nearest = null;
        for (List<?> list : List.of(d.spawns, d.extracts, d.containers, d.ai, d.bosses, d.doors, d.switches)) {
            for (Object o : list) {
                double dist = posOf(o).distanceSq(at);
                if (dist < best) {
                    best = dist;
                    from = list;
                    nearest = o;
                }
            }
        }
        if (from != null) {
            from.remove(nearest);
        }
        return nearest;
    }

    /**
     * Lever extracts bound to a removed switch lose the binding (unless another lever still has its
     * id): left as it was, the extract would wait for a lever that is gone. The next lever placed
     * picks them up.
     */
    private static void unbind(Player player, MapDraft d, MapDef.SwitchDef removed) {
        if (d.switches.stream().anyMatch(s -> s.id().equals(removed.id()))) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < d.extracts.size(); i++) {
            MapDef.ExtractDef e = d.extracts.get(i);
            if (e.switchId().equals(removed.id())) {
                d.extracts.set(i, withSwitch(e, ""));
                names.add(e.name());
            }
        }
        Text.send(player, "<#FFC23D>已删除电闸 " + Text.escape(removed.id()) + (names.isEmpty() ? "。"
                : "，撤离点 <white>" + Text.escape(String.join("、", names)) + "</white> 不再绑定电闸<#9AA0A6>（再点一个拉杆会自动绑定）"));
    }

    private static MapDef.ExtractDef withSwitch(MapDef.ExtractDef e, String switchId) {
        return new MapDef.ExtractDef(e.id(), e.name(), e.pos(), e.radius(), e.type(), e.seconds(), e.cost(),
                e.opensAt(), e.closesAt(), switchId, e.capacity(), e.chance());
    }

    /**
     * One past the highest number among ids of the form prefix + number ("switch_3"), so an id
     * freed by a removal is never handed out again while an older marker could still refer to it.
     */
    private static int nextNumber(String prefix, List<String> ids) {
        int max = 0;
        for (String id : ids) {
            if (id.startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(id.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // a hand-written id ("switch_gate"): not part of the numbering
                }
            }
        }
        return max + 1;
    }

    private static Pos posOf(Object o) {
        if (o instanceof MapDef.SpawnDef s) return s.pos();
        if (o instanceof MapDef.ExtractDef e) return e.pos();
        if (o instanceof MapDef.ContainerDef c) return c.pos();
        if (o instanceof MapDef.AiDef a) return a.pos();
        if (o instanceof MapDef.BossDef b) return b.pos();
        if (o instanceof MapDef.DoorDef dd) return dd.pos();
        return ((MapDef.SwitchDef) o).pos();
    }

    private int scan(MapDraft d, Location center, int radius) {
        Map<Material, String> mapping = Map.of(Material.CHEST, "crate", Material.BARREL, "crate", Material.TRAPPED_CHEST, "weapon_case",
                Material.ENDER_CHEST, "safe", Material.SHULKER_BOX, "parcel", Material.SMOKER, "toolbox", Material.BLAST_FURNACE, "computer",
                Material.LECTERN, "filing_cabinet");
        World world = center.getWorld();
        int count = 0;
        int cx = center.getBlockX() >> 4;
        int cz = center.getBlockZ() >> 4;
        int cr = (radius >> 4) + 1;
        for (int x = cx - cr; x <= cx + cr; x++) {
            for (int z = cz - cr; z <= cz + cr; z++) {
                if (!world.isChunkLoaded(x, z)) {
                    continue;
                }
                Chunk chunk = world.getChunkAt(x, z);
                for (BlockState state : chunk.getTileEntities(block -> mapping.containsKey(block.getType()) || block.getType().name().endsWith("SHULKER_BOX"), false)) {
                    Block block = state.getBlock();
                    if (block.getLocation().distanceSquared(center) > radius * radius) {
                        continue;
                    }
                    String type = mapping.getOrDefault(block.getType(), "parcel");
                    float yaw = 0;
                    if (block.getBlockData() instanceof org.bukkit.block.data.Directional dir) {
                        yaw = switch (dir.getFacing()) {
                            case EAST -> 90;
                            case SOUTH -> 180;
                            case WEST -> 270;
                            default -> 0;
                        };
                    }
                    block.setType(Material.AIR, false);
                    d.containers.add(new MapDef.ContainerDef(type, new Pos(block.getX() + 0.5, block.getY(), block.getZ() + 0.5, yaw), 0));
                    count++;
                }
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ labels

    private void refreshLabels(Player player, Session session) {
        clearLabels(session);
        World world = session.world;
        MapDraft d = session.draft;
        for (MapDef.SpawnDef s : d.spawns) label(player, session, world, s.pos(), "<#4FA3FF>◆ 出生点 · 第" + s.group() + "组");
        for (MapDef.ExtractDef e : d.extracts) label(player, session, world, e.pos(), "<#5BD16B>⬆ " + Text.escape(e.name()) + " <#8A8F98>" + e.type().displayName()
                + (e.switchId().isEmpty() ? "" : " → " + e.switchId()));
        for (MapDef.ContainerDef c : d.containers) {
            ContainerType type = plugin.loot().type(c.type());
            label(player, session, world, c.pos(), "<#F2C94C>▣ " + (type == null ? c.type() : type.name()));
        }
        for (MapDef.AiDef a : d.ai) label(player, session, world, a.pos(), "<#FF8A80>✦ 敌兵 ×" + a.count() + " " + a.kind());
        for (MapDef.BossDef b : d.bosses) label(player, session, world, b.pos(), "<#FF4D4D>☠ " + Text.escape(b.name()));
        for (MapDef.DoorDef dd : d.doors) label(player, session, world, dd.pos(), "<#B66BFF>🔒 门禁 " + dd.lock());
        for (MapDef.SwitchDef sw : d.switches) label(player, session, world, sw.pos(), "<#FFC23D>⚡ " + Text.escape(sw.name()) + " (" + sw.id() + ")");
    }

    private void label(Player player, Session session, World world, Pos pos, String text) {
        TextDisplay display = world.spawn(new Location(world, pos.x(), pos.y() + 0.6, pos.z()), TextDisplay.class, t -> {
            t.text(Text.mm(text));
            t.setBillboard(Display.Billboard.CENTER);
            t.setSeeThrough(true);
            t.setPersistent(false);
            t.setVisibleByDefault(false);
            t.setViewRange(0.6f);
            t.getPersistentDataContainer().set(Keys.ENTITY_ROLE, PersistentDataType.STRING, "editor_label");
        });
        player.showEntity(plugin, display);
        session.labels.add(display);
    }

    private static void clearLabels(Session session) {
        session.labels.forEach(TextDisplay::remove);
        session.labels.clear();
    }

    /** Leaving the server saves the draft (never publishes it) and ends the session even if the save failed. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            save(event.getPlayer(), session);
            close(event.getPlayer(), session);
        }
    }

    /** A shutdown saves every open draft, as leaving does. */
    public void shutdown() {
        for (Map.Entry<UUID, Session> entry : new ArrayList<>(sessions.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                save(player, entry.getValue());
                close(player, entry.getValue());
            }
        }
    }
}
