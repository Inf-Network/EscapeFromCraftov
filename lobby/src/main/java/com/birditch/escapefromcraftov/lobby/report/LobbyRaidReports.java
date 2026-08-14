package com.birditch.escapefromcraftov.lobby.report;

import com.birditch.escapefromcraftov.api.report.RaidOutcome;
import com.birditch.escapefromcraftov.api.report.RaidReportService;
import com.birditch.escapefromcraftov.api.report.RaidResult;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/** Async-safe recent-ten report viewer ready for the /lobby reports command adapter. */
public final class LobbyRaidReports {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
      .withZone(ZoneId.systemDefault());
  private final RaidReportService reports;
  private final CraftovScheduler scheduler;

  public LobbyRaidReports(RaidReportService reports, CraftovScheduler scheduler) {
    this.reports = Preconditions.checkNotNull(reports, "reports");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
  }

  public void show(Player player) {
    Preconditions.checkNotNull(player, "player");
    reports.recent(player.getUniqueId(), 10).whenComplete((results, failure) ->
        scheduler.runSync(() -> {
          if (!player.isOnline()) return;
          if (failure != null) {
            player.sendMessage(Component.text("行动记录读取失败，请稍后重试。", NamedTextColor.RED));
            return;
          }
          render(player, results);
        }));
  }

  static void render(Player player, List<RaidResult> results) {
    player.sendMessage(Component.text("最近行动战报（最多 10 局）", NamedTextColor.GOLD));
    if (results.isEmpty()) {
      player.sendMessage(Component.text("暂无行动记录。", NamedTextColor.GRAY));
      return;
    }
    for (RaidResult result : results) {
      boolean success = result.outcome() == RaidOutcome.EXTRACTED;
      player.sendMessage(Component.text(TIME.format(result.endedAt()) + "  ",
              NamedTextColor.DARK_GRAY)
          .append(Component.text(result.mapId().value() + "  ", NamedTextColor.WHITE))
          .append(Component.text(outcome(result.outcome()) + "  ",
              success ? NamedTextColor.GREEN : NamedTextColor.RED))
          .append(Component.text("玩家击杀 " + result.pvpKills() + " / AI击杀 "
              + result.pveKills() + " / 带出价值 "
              + result.extractedValue() + " / 经验 +" + result.experience(),
              NamedTextColor.YELLOW)));
    }
  }

  private static String outcome(RaidOutcome outcome) {
    return switch (outcome) {
      case EXTRACTED -> "撤离";
      case KILLED -> "阵亡";
      case QUIT -> "退出";
      case TIMED_OUT -> "超时";
      case FAILED -> "失败";
      case ABORTED -> "中止";
    };
  }
}
