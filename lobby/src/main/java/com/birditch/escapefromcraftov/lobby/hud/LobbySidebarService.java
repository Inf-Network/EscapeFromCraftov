package com.birditch.escapefromcraftov.lobby.hud;

import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventoryService;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventorySnapshot;
import com.birditch.escapefromcraftov.api.matchmaking.MatchTicket;
import com.birditch.escapefromcraftov.api.profile.PlayerProfile;
import com.birditch.escapefromcraftov.api.profile.ProfileService;
import com.birditch.escapefromcraftov.api.profile.ProgressionCurve;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.bukkit.MapNames;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.lobby.LobbyConfig;
import com.birditch.escapefromcraftov.lobby.LobbyDataGate;
import com.birditch.escapefromcraftov.lobby.matchmaking.QueueService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jspecify.annotations.Nullable;

/// Stable, dynamic Lobby Sidebar backed by already loaded domain state.
public final class LobbySidebarService implements AutoCloseable {

  private static final String[] ENTRIES = {
      "\u00a70", "\u00a71", "\u00a72", "\u00a73", "\u00a74",
      "\u00a75", "\u00a76", "\u00a77", "\u00a78", "\u00a79",
      "\u00a7a", "\u00a7b", "\u00a7c", "\u00a7d", "\u00a7e"};

  private final Plugin plugin;
  private final ProfileService profiles;
  private final PlayerInventoryService inventories;
  private final QueueService queue;
  private final LobbyDataGate dataGate;
  private final CraftovScheduler scheduler;
  private final Messages messages;
  private final LobbyConfig config;
  private final Logger logger;
  private final Map<UUID, View> views = new HashMap<>();
  private final Map<UUID, InventoryCache> inventoryCache = new HashMap<>();
  private final Set<UUID> inventoryLoads = new HashSet<>();
  private @Nullable BukkitTask task;
  private boolean closed;

  public LobbySidebarService(Plugin plugin, ProfileService profiles,
      PlayerInventoryService inventories, QueueService queue, LobbyDataGate dataGate,
      CraftovScheduler scheduler, Messages messages, LobbyConfig config, Logger logger) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.profiles = Preconditions.checkNotNull(profiles, "profiles");
    this.inventories = Preconditions.checkNotNull(inventories, "inventories");
    this.queue = Preconditions.checkNotNull(queue, "queue");
    this.dataGate = Preconditions.checkNotNull(dataGate, "dataGate");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.messages = Preconditions.checkNotNull(messages, "messages");
    this.config = Preconditions.checkNotNull(config, "config");
    this.logger = Preconditions.checkNotNull(logger, "logger");
  }

  public void start() {
    if (!config.scoreboardEnabled() || task != null) return;
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll,
        0L, config.scoreboardRefreshTicks());
  }

  public void refreshAll() {
    if (closed || !config.scoreboardEnabled()) return;
    Set<UUID> online = new HashSet<>();
    for (Player player : Bukkit.getOnlinePlayers()) {
      UUID playerId = player.getUniqueId();
      online.add(playerId);
      if (!dataGate.isReady(playerId)) {
        closeView(playerId);
        continue;
      }
      PlayerProfile profile = profiles.find(playerId).orElse(null);
      if (profile == null) {
        closeView(playerId);
        continue;
      }
      refreshInventory(playerId);
      render(player, profile, inventoryCache.get(playerId));
    }
    for (UUID playerId : List.copyOf(views.keySet())) {
      if (!online.contains(playerId)) closeView(playerId);
    }
    inventoryCache.keySet().removeIf(playerId -> !online.contains(playerId));
    inventoryLoads.removeIf(playerId -> !online.contains(playerId));
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    if (task != null) task.cancel();
    task = null;
    for (UUID playerId : List.copyOf(views.keySet())) closeView(playerId);
    inventoryCache.clear();
    inventoryLoads.clear();
  }

  private void refreshInventory(UUID playerId) {
    InventoryCache cached = inventoryCache.get(playerId);
    long maxAge = Duration.ofSeconds(config.inventoryRefreshSeconds()).toMillis();
    if (cached != null && System.currentTimeMillis() - cached.loadedAtMillis() < maxAge) return;
    if (!inventoryLoads.add(playerId)) return;
    inventories.load(playerId).whenComplete((snapshot, failure) -> scheduler.runSync(() -> {
      inventoryLoads.remove(playerId);
      if (closed || !dataGate.isReady(playerId)) return;
      if (failure != null || snapshot == null) {
        logger.log(Level.FINE, "大厅 Sidebar 仓库统计刷新失败:" + playerId, failure);
        return;
      }
      inventoryCache.put(playerId, new InventoryCache(snapshot, System.currentTimeMillis()));
    }));
  }

  private void render(Player player, PlayerProfile profile, @Nullable InventoryCache cached) {
    View view = views.computeIfAbsent(player.getUniqueId(), ignored -> createView(player));
    MatchTicket ticket = queue.ticket(player.getUniqueId()).orElse(null);
    long levelStart = ProgressionCurve.experienceForLevel(profile.level());
    long next = profile.level() == ProgressionCurve.MAX_LEVEL ? levelStart
        : ProgressionCurve.experienceForLevel(profile.level() + 1);
    long levelProgress = profile.experience() - levelStart;
    long levelSpan = Math.max(0, next - levelStart);
    int warehouse = cached == null ? -1
        : cached.snapshot().at(InventoryLocation.WAREHOUSE).size();
    int loadout = cached == null ? -1
        : cached.snapshot().at(InventoryLocation.LOADOUT).size();
    String queueState = ticket == null ? "未匹配" : "正在寻找行动";
    Component queueMap = ticket == null ? Component.text("选择地图", NamedTextColor.GRAY)
        : MapNames.display(messages, ticket.mapId());
    long waiting = ticket == null ? 0
        : Math.max(0, Duration.between(ticket.queuedAt(), Instant.now()).toSeconds());
    List<Component> lines = List.of(
        text("玩家  " + player.getName(), NamedTextColor.WHITE),
        text("等级  " + profile.level(), NamedTextColor.YELLOW),
        text("历练  " + (profile.level() == ProgressionCurve.MAX_LEVEL
            ? "已满级" : levelProgress + " / " + levelSpan), NamedTextColor.AQUA),
        text("金币  " + profile.balance().format(), NamedTextColor.GOLD),
        text("物资仓库  " + (warehouse < 0 ? "读取中" : warehouse + " 组"),
            NamedTextColor.GREEN),
        text("出战栏  " + (loadout < 0 ? "读取中" : loadout + " / 9"),
            NamedTextColor.GREEN),
        Component.empty(),
        text("匹配  " + queueState, ticket == null ? NamedTextColor.GRAY : NamedTextColor.YELLOW),
        text("地图  ", NamedTextColor.GRAY).append(queueMap),
        text(ticket == null ? "使用 /play 开始匹配" : "已等待 " + waiting + " 秒",
            NamedTextColor.GRAY),
        Component.empty(),
        text("大厅在线  " + Bukkit.getOnlinePlayers().size(), NamedTextColor.AQUA),
        text("队列人数  " + queue.queueSize(), NamedTextColor.LIGHT_PURPLE),
        text("右键指南针打开菜单", NamedTextColor.DARK_GRAY));
    for (int index = 0; index < ENTRIES.length; index++) {
      view.teams().get(index).prefix(index < lines.size() ? lines.get(index) : Component.empty());
    }
    player.sendPlayerListHeaderAndFooter(messages.raw("tab.header"),
        Component.text("大厅在线 " + Bukkit.getOnlinePlayers().size()
            + "  |  匹配队列 " + queue.queueSize(), NamedTextColor.GRAY));
  }

  private View createView(Player player) {
    Scoreboard previous = player.getScoreboard();
    Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
    Objective objective = board.registerNewObjective("craftov_lobby", Criteria.DUMMY,
        Component.text("逃离块科夫", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
    objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    Map<Integer, Team> teams = new HashMap<>();
    for (int index = 0; index < ENTRIES.length; index++) {
      Team team = board.registerNewTeam("craftov_lobby_line_" + index);
      team.addEntry(ENTRIES[index]);
      objective.getScore(ENTRIES[index]).setScore(ENTRIES.length - index);
      teams.put(index, team);
    }
    player.setScoreboard(board);
    return new View(previous, teams);
  }

  private void closeView(UUID playerId) {
    View removed = views.remove(playerId);
    Player player = Bukkit.getPlayer(playerId);
    if (removed != null && player != null) player.setScoreboard(removed.previous());
  }

  private static Component text(String value, NamedTextColor color) {
    return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
  }

  private record View(Scoreboard previous, Map<Integer, Team> teams) {}

  private record InventoryCache(PlayerInventorySnapshot snapshot, long loadedAtMillis) {}
}
