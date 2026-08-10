package com.birditch.raidcore.game.plugin.isolation;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.game.RaidGameService;
import com.birditch.raidcore.api.game.RaidPlayer;
import com.birditch.raidcore.bukkit.MapNames;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.game.plugin.instance.InstanceWorldIndex;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/// Keeps entity and tab-list visibility aligned with isolation groups: a
/// player only sees members of their own group (`"lobby"` or `"game:<id>"`).
///
/// MAIN-THREAD only — hide/show are Bukkit API calls. Callers (connection
/// listener, instance-world bridge) invoke [#refresh(Player)] after every
/// group transition.
public final class VisibilityService {

  private final Plugin plugin;
  private final InstanceWorldIndex index;
  private final RaidGameService games;
  private final @Nullable Messages messages;
  private @Nullable BukkitTask tabRefreshTask;

  public VisibilityService(Plugin plugin, InstanceWorldIndex index) {
    this(plugin, index, null);
  }

  public VisibilityService(Plugin plugin, InstanceWorldIndex index,
      RaidGameService games) {
    this(plugin, index, games, null);
  }

  public VisibilityService(Plugin plugin, InstanceWorldIndex index,
      RaidGameService games, @Nullable Messages messages) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.index = Preconditions.checkNotNull(index, "index");
    this.games = games;
    this.messages = messages;
  }

  /// Re-evaluates visibility between `player` and every other online player,
  /// applying show/hide in both directions so the pair always agrees.
  public void refresh(Player player) {
    Preconditions.checkNotNull(player, "player");
    refreshVisibility(player);
    updatePlayerListNames();
  }

  private void refreshVisibility(Player player) {
    String group = index.groupOf(player.getUniqueId());
    for (Player other : Bukkit.getOnlinePlayers()) {
      if (other.getUniqueId().equals(player.getUniqueId())) {
        continue;
      }
      if (index.groupOf(other.getUniqueId()).equals(group)) {
        player.showPlayer(plugin, other);
        other.showPlayer(plugin, player);
      } else {
        player.hidePlayer(plugin, other);
        other.hidePlayer(plugin, player);
      }
    }
  }

  /// Refreshes every online player, then renders names/header/footer once.
  /// This path is used after an offline room leave, when there is no departing
  /// Player object to pass to [#refresh(Player)].
  public void refreshAll() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      refreshVisibility(player);
    }
    updatePlayerListNames();
  }

  /** Starts a lightweight Tab refresh so room counts remain current even when
   * no join/leave event occurred (for example during a state transition). */
  public void start() {
    if (tabRefreshTask != null) return;
    tabRefreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshTabList,
        20L, 20L);
  }

  public void close() {
    if (tabRefreshTask != null) {
      tabRefreshTask.cancel();
      tabRefreshTask = null;
    }
  }

  private void refreshTabList() {
    if (plugin.isEnabled()) updatePlayerListNames();
  }

  /// Keeps the visible Tab name useful when several independent raid worlds
  /// share one Paper process. Hidden players are already filtered by the
  /// pairwise visibility pass, while members of a game see its room/team.
  private void updatePlayerListNames() {
    for (Player online : Bukkit.getOnlinePlayers()) {
      String group = index.groupOf(online.getUniqueId());
      if (group.startsWith("editor:")) {
        online.playerListName(Component.text("[编辑] ", NamedTextColor.GOLD)
            .append(Component.text(online.getName(), NamedTextColor.WHITE)));
        updateTab(online, group, null);
        continue;
      }
      if (!group.startsWith("game:") || games == null) {
        online.playerListName(Component.text(online.getName(), NamedTextColor.WHITE));
        updateTab(online, group, null);
        continue;
      }
      String gameId = group.substring("game:".length());
      RaidGame game = games.all().stream()
          .filter(value -> value.id().value().equals(gameId))
          .findFirst().orElse(null);
      if (game == null) {
        online.playerListName(Component.text(online.getName(), NamedTextColor.WHITE));
        updateTab(online, group, null);
        continue;
      }
      RaidPlayer participant = game.players().stream()
          .filter(value -> value.playerId().equals(online.getUniqueId()))
          .findFirst().orElse(null);
      String team = participant == null || participant.teamId().isEmpty()
          ? "" : "队 " + participant.teamId().getAsInt() + " · ";
      Component mapName = messages == null ? Component.text(game.mapId().value())
          : MapNames.display(messages, game.mapId());
      online.playerListName(Component.text("[", NamedTextColor.DARK_GRAY)
          .append(mapName.color(NamedTextColor.AQUA))
          .append(Component.text("] " + team + online.getName(), NamedTextColor.WHITE)));
      updateTab(online, group, game);
    }
  }

  private void updateTab(Player player, String group, RaidGame game) {
    if (group.startsWith("editor:")) {
      player.sendPlayerListHeaderAndFooter(
          Component.text("ESCAPE FROM CRAFTOV", NamedTextColor.GOLD)
              .append(Component.newline())
              .append(Component.text("地图编辑模式", NamedTextColor.AQUA)),
          Component.text("/raid editor 进入编辑器", NamedTextColor.GRAY));
      return;
    }
    if (group.startsWith("game:") && game != null) {
      Component mapName = messages == null ? Component.text(game.mapId().value())
          : MapNames.display(messages, game.mapId());
      player.sendPlayerListHeaderAndFooter(
          Component.text("ESCAPE FROM CRAFTOV", NamedTextColor.GOLD)
              .append(Component.newline())
              .append(Component.text("地图 ", NamedTextColor.GRAY)).append(mapName),
          Component.text("本局玩家 " + game.playerCount(), NamedTextColor.GRAY)
              .append(Component.text(" · 只显示本房间玩家", NamedTextColor.DARK_GRAY)));
      return;
    }
    player.sendPlayerListHeaderAndFooter(
        Component.text("ESCAPE FROM CRAFTOV", NamedTextColor.GOLD)
            .append(Component.newline())
            .append(Component.text("逃离块科夫", NamedTextColor.GRAY)),
        Component.text("大厅", NamedTextColor.GRAY));
  }
}
