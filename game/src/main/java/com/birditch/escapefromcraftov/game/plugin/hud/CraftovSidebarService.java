package com.birditch.escapefromcraftov.game.plugin.hud;

import com.birditch.escapefromcraftov.api.combat.CombatService;
import com.birditch.escapefromcraftov.api.combat.PlayerCombatState;
import com.birditch.escapefromcraftov.api.extraction.ExtractionService;
import com.birditch.escapefromcraftov.api.extraction.ExtractionSession;
import com.birditch.escapefromcraftov.api.extraction.ExtractionState;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.bukkit.MapNames;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.GameplaySettings;
import com.birditch.escapefromcraftov.game.plugin.carry.RaidCarryController;
import com.birditch.escapefromcraftov.game.plugin.editor.ManualMapService;
import com.birditch.escapefromcraftov.game.plugin.listener.GameCombatListener;
import com.birditch.escapefromcraftov.game.plugin.start.CraftovStartStatus;
import com.birditch.escapefromcraftov.game.plugin.secure.SecureContainerController;
import com.birditch.escapefromcraftov.map.EditorSession;
import com.birditch.escapefromcraftov.map.EditorSessionService;
import com.birditch.escapefromcraftov.map.ManualMapValidationResult;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.jspecify.annotations.Nullable;

/// Stable personal Sidebar renderer for both manual map editing and live craftovs.
/// A player's previous scoreboard is restored when the service closes or the
/// player leaves the managed view.
public final class CraftovSidebarService implements AutoCloseable {

  private static final String[] ENTRIES = {
      "\u00a70", "\u00a71", "\u00a72", "\u00a73", "\u00a74",
      "\u00a75", "\u00a76", "\u00a77", "\u00a78", "\u00a79",
      "\u00a7a", "\u00a7b", "\u00a7c", "\u00a7d", "\u00a7e"};

  private final Plugin plugin;
  private final CraftovGameService games;
  private final EditorSessionService editors;
  private final ManualMapService maps;
  private final ExtractionService extractions;
  private final CombatService combat;
  private final CraftovItemStacks itemStacks;
  private final ItemRegistry items;
  private final RaidCarryController carry;
  private final SecureContainerController secure;
  private final Messages messages;
  private final GameplaySettings settings;
  private final GameCombatListener combatListener;
  private final CraftovStartStatus starts;
  private final int durationSeconds;
  private final int maxPlayers;
  private final int minimumPlayers;
  private final Map<UUID, View> views = new HashMap<>();
  private final Map<UUID, String> modes = new HashMap<>();
  private @Nullable BukkitTask task;
  private boolean closed;

  public CraftovSidebarService(Plugin plugin, CraftovGameService games, EditorSessionService editors,
      ManualMapService maps, ExtractionService extractions,
      CombatService combat,
      CraftovItemStacks itemStacks, ItemRegistry items, RaidCarryController carry,
      SecureContainerController secure, Messages messages, GameplaySettings settings,
      GameCombatListener combatListener, CraftovStartStatus starts,
      int durationSeconds, int maxPlayers, int minimumPlayers) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.games = Preconditions.checkNotNull(games, "games");
    this.editors = Preconditions.checkNotNull(editors, "editors");
    this.maps = Preconditions.checkNotNull(maps, "maps");
    this.extractions = Preconditions.checkNotNull(extractions, "extractions");
    this.combat = Preconditions.checkNotNull(combat, "combat");
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    this.items = Preconditions.checkNotNull(items, "items");
    this.carry = Preconditions.checkNotNull(carry, "carry");
    this.secure = Preconditions.checkNotNull(secure, "secure");
    this.messages = Preconditions.checkNotNull(messages, "messages");
    this.settings = Preconditions.checkNotNull(settings, "settings");
    this.combatListener = Preconditions.checkNotNull(combatListener, "combatListener");
    this.starts = Preconditions.checkNotNull(starts, "starts");
    Preconditions.checkArgument(durationSeconds > 0, "durationSeconds must be positive");
    this.durationSeconds = durationSeconds;
    Preconditions.checkArgument(maxPlayers > 0, "maxPlayers must be positive");
    this.maxPlayers = maxPlayers;
    Preconditions.checkArgument(minimumPlayers > 0, "minimumPlayers must be positive");
    this.minimumPlayers = minimumPlayers;
  }

  public void start() {
    if (!settings.scoreboardEnabled() || task != null) return;
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 0L,
        settings.scoreboardRefreshTicks());
  }

  public void refreshAll() {
    if (closed || !settings.scoreboardEnabled()) return;
    Map<UUID, EditorSession> editorByPlayer = new HashMap<>();
    for (EditorSession session : editors.all()) editorByPlayer.put(session.playerId(), session);
    Map<UUID, CraftovGame> gamesByPlayer = new HashMap<>();
    for (CraftovGame game : games.all()) {
      for (CraftovPlayer player : game.players()) gamesByPlayer.put(player.playerId(), game);
    }
    for (Player player : Bukkit.getOnlinePlayers()) {
      UUID id = player.getUniqueId();
      if (gamesByPlayer.containsKey(id)) renderGame(player, gamesByPlayer.get(id));
      else if (editorByPlayer.containsKey(id)) renderEditor(player, editorByPlayer.get(id));
      else closeView(id);
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    if (task != null) task.cancel();
    for (UUID id : List.copyOf(views.keySet())) closeView(id);
    views.clear();
    modes.clear();
  }

  private void renderEditor(Player player, EditorSession session) {
    ensure(player, "editor", "地图编辑器", Component.text(session.mapId(), NamedTextColor.GRAY));
    // Editor mutations invalidate the session result. Reuse it between HUD
    // ticks so a large map is not fully validated every half second.
    ManualMapValidationResult validation = session.validationResult().orElseGet(() -> {
      ManualMapValidationResult computed = maps.validate(session.draft());
      session.setValidationResult(computed);
      return computed;
    });
    int completion = editorCompletion(session);
    List<Component> lines = List.of(
        text("地图  " + session.mapId(), NamedTextColor.GOLD),
        text("版本  " + session.draft().revision() + (session.dirty() ? " *" : ""),
            session.dirty() ? NamedTextColor.YELLOW : NamedTextColor.GRAY),
        text("世界  " + session.draft().worldName(), NamedTextColor.GRAY),
        text("工具  " + session.tool(), NamedTextColor.AQUA),
        text("选中  " + session.selectedObjectId().orElse("无"), NamedTextColor.GRAY),
        text("进度  " + bar(completion) + " " + completion + "%", NamedTextColor.GREEN),
        text("评分  " + validation.qualityScore() + " / 100", NamedTextColor.GOLD),
        text("错误  " + validation.errorCount() + "  警告  " + validation.warningCount(),
            validation.hasErrors() ? NamedTextColor.RED : NamedTextColor.YELLOW),
        text("出生  " + session.draft().spawns().size() + "  物资  "
            + session.draft().lootPoints().size(), NamedTextColor.GREEN),
        text("物资区 " + session.draft().lootZones().size() + "  AI区  "
            + session.draft().aiZones().size(), NamedTextColor.YELLOW),
        text("撤离  " + session.draft().extractions().size() + "  POI  "
            + session.draft().pois().size(), NamedTextColor.RED),
        text("对象总数 " + session.draft().objects().size(), NamedTextColor.LIGHT_PURPLE),
        text(session.draft().bounds() == null ? "边界  未设置"
                : "边界  已设置 · " + (session.boundsVisible() ? "显示" : "隐藏"),
            session.draft().bounds() == null ? NamedTextColor.RED : NamedTextColor.GREEN),
        text("左键选择 · 右键工具", NamedTextColor.DARK_GRAY));
    update(player, lines);
    View view = views.get(player.getUniqueId());
    if (view != null) view.boss().bar().removeViewer(player);
    player.sendActionBar(text(session.dirty() ? "有未保存修改 · /craftov editor save"
        : "编辑器就绪 · 用选区工具快速设置", session.dirty() ? NamedTextColor.YELLOW
            : NamedTextColor.GRAY));
  }

  private void renderGame(Player player, CraftovGame game) {
    if (game.state() == CraftovGameState.WAITING) {
      renderWaiting(player, game);
      return;
    }
    Component mapName = MapNames.display(messages, game.mapId());
    ensure(player, "game-running", "行动中", mapName);
    long seconds = game.startedAt().map(start -> Math.max(0,
        Duration.ofSeconds(durationSeconds).minus(Duration.between(start, Instant.now())).toSeconds()))
        .orElse(0L);
    int team = game.players().stream()
        .filter(value -> value.playerId().equals(player.getUniqueId()))
        .findFirst()
        .map(value -> value.teamId().isPresent() ? value.teamId().getAsInt() : 0)
        .orElse(0);
    int teammates = team == 0 ? 0 : game.teams().stream()
        .filter(value -> value.teamId() == team).mapToInt(value -> Math.max(0, value.size() - 1))
        .findFirst().orElse(0);
    PlayerCombatState combatState = combat.stateOf(player.getUniqueId());
    long alive = game.players().stream().filter(value -> !value.extracted()).count();
    var carrySnapshot = carry.snapshot(player);
    int backpack = carrySnapshot.map(value -> value.usedCargoSlots())
        .orElseGet(() -> itemStacks.usedSlots(player.getInventory().getStorageContents()));
    int capacity = carrySnapshot.map(value -> value.capacity().cargoSlots()).orElse(36);
    List<ItemInstance> protectedItems = secure.contents(game.id(), player.getUniqueId());
    long physicalWeight = carrySnapshot.map(value -> value.totalWeightGrams())
        .orElseGet(() -> physicalWeight(player));
    long weight = saturatedAdd(physicalWeight, itemWeight(protectedItems));
    long maximumWeight = carrySnapshot.map(value -> value.capacity().maximumWeightGrams()).orElse(0L);
    long comfortableWeight = carrySnapshot.map(value -> value.capacity().comfortableWeightGrams())
        .orElse(Long.MAX_VALUE);
    long value = saturatedAdd(itemStacks.totalValue(player.getInventory().getContents()),
        itemValue(protectedItems));
    ExtractionSession extractionSession = extractions.activeSession(player.getUniqueId()).orElse(null);
    String extraction = extractionSession == null ? "寻找撤离点"
        : extractionSession.state() == ExtractionState.SETTLING ? "物资结算中 · 请稍候"
        : "撤离中 · " + extractionSession.pointId().value() + " · "
            + extractionSeconds(game, extractionSession) + "s";
    List<Component> lines = List.of(
        text("地图  ", NamedTextColor.GOLD).append(mapName),
        text("版本  " + game.mapRevision(), NamedTextColor.GRAY),
        text("规则  " + ruleName(game), game.actionProfile().isPresent()
            ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GRAY),
        text("状态  " + phase(game.state()), NamedTextColor.GREEN),
        text("时间  " + formatTime(seconds), seconds < 120 ? NamedTextColor.RED : NamedTextColor.WHITE),
        text("玩家  " + alive + " / " + maxPlayers, NamedTextColor.AQUA),
        text("队伍  " + (team == 0 ? "无" : "第 " + team + " 队 · 队友 " + teammates),
            NamedTextColor.GREEN),
        text("状态  " + combatStateName(combatState),
            combatState == PlayerCombatState.ALIVE ? NamedTextColor.GREEN : NamedTextColor.RED),
        text("携行  " + backpack + " / " + capacity + " 格", NamedTextColor.GRAY),
        text("负重  " + formatWeight(weight)
                + (maximumWeight > 0 ? " / " + formatWeight(maximumWeight) : ""),
            maximumWeight > 0 && weight > maximumWeight ? NamedTextColor.RED
                : weight > comfortableWeight ? NamedTextColor.YELLOW : NamedTextColor.GREEN),
        text("价值  " + String.format(java.util.Locale.ROOT, "%,d", value), NamedTextColor.GOLD),
        text("击杀  " + combatKills(player), NamedTextColor.RED),
        text(extraction, NamedTextColor.AQUA),
        text("撤离成功才算带回物资", NamedTextColor.YELLOW));
    update(player, lines);
    player.sendActionBar(text(extraction, NamedTextColor.AQUA));
    updateBossBar(player, game);
  }

  private static String modeName(CraftovGame game) {
    return switch (game.mode()) {
      case NORMAL -> "常规";
      case CONFIDENTIAL -> "机密";
    };
  }

  private static String ruleName(CraftovGame game) {
    return game.actionProfile().map(profile -> profile.displayName())
        .orElseGet(() -> modeName(game) + " · " + difficultyName(game));
  }

  private long physicalWeight(Player player) {
    long total = 0L;
    for (org.bukkit.inventory.ItemStack stack : player.getInventory().getContents()) {
      if (stack == null || stack.isEmpty()) continue;
      var tagged = itemStacks.read(stack).orElse(null);
      if (tagged == null) continue;
      try {
        total = Math.addExact(total, Math.multiplyExact(
            (long) items.require(tagged.itemId()).weightGrams(), stack.getAmount()));
      } catch (ArithmeticException overflow) {
        return Long.MAX_VALUE;
      }
    }
    return total;
  }

  private long itemWeight(List<ItemInstance> instances) {
    long total = 0L;
    for (ItemInstance item : instances) {
      try {
        total = Math.addExact(total, Math.multiplyExact(
            (long) items.require(item.itemId()).weightGrams(), item.quantity()));
      } catch (ArithmeticException overflow) {
        return Long.MAX_VALUE;
      }
    }
    return total;
  }

  private long itemValue(List<ItemInstance> instances) {
    long total = 0L;
    for (ItemInstance item : instances) {
      try {
        total = Math.addExact(total, Math.multiplyExact(
            items.require(item.itemId()).basePrice().units(), item.quantity()));
      } catch (ArithmeticException overflow) {
        return Long.MAX_VALUE;
      }
    }
    return total;
  }

  private static long saturatedAdd(long first, long second) {
    if (first == Long.MAX_VALUE || second == Long.MAX_VALUE) return Long.MAX_VALUE;
    try {
      return Math.addExact(first, second);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private static String formatWeight(long grams) {
    if (grams == Long.MAX_VALUE) return "超出范围";
    return grams < 1_000 ? grams + "g"
        : String.format(java.util.Locale.ROOT, "%.1fkg", grams / 1_000.0);
  }

  private static String difficultyName(CraftovGame game) {
    return switch (game.difficulty()) {
      case EASY -> "简单";
      case NORMAL -> "标准";
      case HARD -> "困难";
    };
  }

  private void renderWaiting(Player player, CraftovGame game) {
    Component mapName = MapNames.display(messages, game.mapId());
    ensure(player, "game-waiting", "等待行动", mapName);
    var remaining = starts.remainingSeconds(game.id());
    int missing = Math.max(0, minimumPlayers - game.playerCount());
    String status = remaining.isPresent()
        ? remaining.getAsInt() + " 秒后开始"
        : "还需 " + missing + " 名玩家";
    List<Component> lines = List.of(
        text("地图  ", NamedTextColor.GOLD).append(mapName),
        text("房间  " + game.id().value(), NamedTextColor.GRAY),
        text("状态  " + status, remaining.isPresent()
            ? NamedTextColor.YELLOW : NamedTextColor.AQUA),
        text("玩家  " + game.playerCount() + " / " + maxPlayers, NamedTextColor.AQUA),
        text("开局  至少 " + minimumPlayers + " 人", NamedTextColor.GRAY),
        text("队伍  每队自动分配", NamedTextColor.GREEN),
        text("装备  已锁定到本局", NamedTextColor.GOLD),
        text("等待时不会受到伤害", NamedTextColor.GREEN),
        text("行动开始后寻找物资", NamedTextColor.YELLOW),
        text("活着撤离才能带回", NamedTextColor.LIGHT_PURPLE));
    update(player, lines);
    View view = views.get(player.getUniqueId());
    if (view != null) view.boss().bar().removeViewer(player);
    player.sendActionBar(text(remaining.isPresent()
        ? "行动将在 " + remaining.getAsInt() + " 秒后开始"
        : "等待更多玩家 · " + game.playerCount() + " / " + minimumPlayers,
        remaining.isPresent() ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
  }

  private int combatKills(Player player) {
    return combatListener.kills(player.getUniqueId());
  }

  private void ensure(Player player, String mode, String title, Component subtitle) {
    View view = views.get(player.getUniqueId());
    if (view == null) {
      ScoreboardManager manager = Bukkit.getScoreboardManager();
      Scoreboard previous = player.getScoreboard();
      Scoreboard board = manager.getNewScoreboard();
      Objective objective = board.registerNewObjective("escapefromcraftov_sidebar", Criteria.DUMMY,
          Component.text(title, NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
      objective.setDisplaySlot(DisplaySlot.SIDEBAR);
      Map<Integer, Team> teams = new HashMap<>();
      for (int index = 0; index < ENTRIES.length; index++) {
        Team teamEntry = board.registerNewTeam("escapefromcraftov_line_" + index);
        teamEntry.addEntry(ENTRIES[index]);
        objective.getScore(ENTRIES[index]).setScore(ENTRIES.length - index);
        teams.put(index, teamEntry);
      }
      view = new View(previous, board, objective, teams, new BossBarState());
      views.put(player.getUniqueId(), view);
      player.setScoreboard(board);
    }
    if (!mode.equals(modes.put(player.getUniqueId(), mode))) {
      View active = views.get(player.getUniqueId());
      if (active != null) {
        active.objective().displayName(Component.text(title, NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD));
      }
        player.showTitle(Title.title(Component.text(title), subtitle,
          Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1500),
              Duration.ofMillis(500))));
    }
  }

  private void update(Player player, List<Component> lines) {
    View view = views.get(player.getUniqueId());
    if (view == null) return;
    for (int index = 0; index < ENTRIES.length; index++) {
      view.teams().get(index).prefix(index < lines.size() ? lines.get(index) : Component.empty());
    }
  }

  private void updateBossBar(Player player, CraftovGame game) {
    View view = views.get(player.getUniqueId());
    if (view == null) return;
    ExtractionSession session = extractions.activeSession(player.getUniqueId()).orElse(null);
    if (session == null) {
      view.boss().bar().removeViewer(player);
      return;
    }
    if (session.state() == ExtractionState.SETTLING) {
      view.boss().bar().name(Component.text("正在安全送回本局物资", NamedTextColor.GOLD));
      view.boss().bar().progress(1f);
      view.boss().bar().color(BossBar.Color.YELLOW);
      view.boss().bar().overlay(BossBar.Overlay.PROGRESS);
      view.boss().bar().addViewer(player);
      return;
    }
    var point = game.extractionPoints().stream().filter(value -> value.id().equals(session.pointId()))
        .findFirst().orElse(null);
    if (point == null) return;
    double total = Math.max(1, point.duration().toMillis());
    double left = Math.max(0, total - Duration.between(session.startedAt(), Instant.now()).toMillis());
    view.boss().bar().name(Component.text("撤离中 · " + point.displayName(), NamedTextColor.AQUA));
    view.boss().bar().progress((float) Math.max(0, Math.min(1, 1 - left / total)));
    view.boss().bar().color(BossBar.Color.BLUE);
    view.boss().bar().overlay(BossBar.Overlay.PROGRESS);
    view.boss().bar().addViewer(player);
  }

  private static long extractionSeconds(CraftovGame game, ExtractionSession session) {
    var point = game.extractionPoints().stream().filter(value -> value.id().equals(session.pointId()))
        .findFirst().orElse(null);
    if (point == null) return 0;
    return Math.max(0, point.duration().toSeconds()
        - Duration.between(session.startedAt(), Instant.now()).toSeconds());
  }

  private static String combatStateName(PlayerCombatState state) {
    return switch (state) {
      case ALIVE -> "存活";
      case DOWNED -> "倒地";
      case DEAD -> "已阵亡";
    };
  }

  private void closeView(UUID id) {
    View view = views.remove(id);
    modes.remove(id);
    Player player = Bukkit.getPlayer(id);
    if (view != null) {
      if (player != null) player.setScoreboard(view.previous());
      if (player != null) view.boss().bar().removeViewer(player);
    }
  }

  private static int editorCompletion(EditorSession session) {
    int complete = 0;
    if (session.draft().bounds() != null) complete++;
    if (!session.draft().spawns().isEmpty()) complete++;
    if (!session.draft().lootPoints().isEmpty()) complete++;
    if (!session.draft().aiZones().isEmpty()) complete++;
    if (!session.draft().extractions().isEmpty()) complete++;
    if (!session.draft().pois().isEmpty()) complete++;
    if (session.draft().enabled()) complete++;
    return Math.round(complete / 7f * 100);
  }

  private static String phase(CraftovGameState state) {
    return switch (state) {
      case CREATED -> "创建中";
      case WAITING -> "等待玩家";
      case PREPARING, STARTING -> "准备开始";
      case RUNNING -> "行动中";
      case ENDING -> "结算中";
      case FINISHED, DESTROYED -> "已结束";
    };
  }

  private static String formatTime(long seconds) {
    return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
  }

  private static String bar(int percent) {
    int filled = Math.round(percent / 10f);
    return "[" + "#".repeat(filled) + "-".repeat(10 - filled) + "]";
  }

  private static Component text(String value, NamedTextColor color) {
    return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
  }

  private record View(Scoreboard previous, Scoreboard board, Objective objective,
                      Map<Integer, Team> teams, BossBarState boss) {}

  private static final class BossBarState {
    private final BossBar bar = BossBar.bossBar(Component.text("行动"), 0,
        BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
    private BossBar bar() { return bar; }
  }
}
