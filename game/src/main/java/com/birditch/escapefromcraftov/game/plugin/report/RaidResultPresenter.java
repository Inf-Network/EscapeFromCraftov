package com.birditch.escapefromcraftov.game.plugin.report;

import com.birditch.escapefromcraftov.api.report.RaidOutcome;
import com.birditch.escapefromcraftov.api.report.RaidResult;
import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

/** Chinese in-game title and compact chat summary for a persisted report. */
public final class RaidResultPresenter {

  private RaidResultPresenter() {}

  public static void show(Player player, RaidResult result) {
    boolean success = result.outcome() == RaidOutcome.EXTRACTED;
    player.showTitle(Title.title(
        Component.text(success ? "撤离成功" : outcomeName(result.outcome()),
            success ? NamedTextColor.GREEN : NamedTextColor.RED),
        Component.text(result.mapId().value() + " · " + modeName(result),
            NamedTextColor.GRAY)));
    long seconds = Duration.between(result.startedAt(), result.endedAt()).toSeconds();
    player.sendMessage(Component.text("行动战报  ", NamedTextColor.GOLD)
        .append(Component.text(outcomeName(result.outcome()),
            success ? NamedTextColor.GREEN : NamedTextColor.RED)));
    player.sendMessage(Component.text("击杀 " + result.kills() + "  ·  带出 "
        + result.extractedItemCount() + " 件  ·  物资价值 " + result.extractedValue(),
        NamedTextColor.YELLOW));
    player.sendMessage(Component.text("经验 +" + result.experience() + "  ·  收益 "
        + result.earnings() + "  ·  用时 " + seconds + " 秒", NamedTextColor.AQUA));
  }

  static String outcomeName(RaidOutcome outcome) {
    return switch (outcome) {
      case EXTRACTED -> "撤离成功";
      case KILLED -> "行动阵亡";
      case QUIT -> "中途退出";
      case TIMED_OUT -> "行动超时";
      case FAILED -> "行动失败";
      case ABORTED -> "行动中止";
    };
  }

  private static String modeName(RaidResult result) {
    String mode = switch (result.mode()) {
      case NORMAL -> "普通行动";
      case CONFIDENTIAL -> "机密行动";
    };
    String difficulty = switch (result.difficulty()) {
      case EASY -> "简单";
      case NORMAL -> "普通";
      case HARD -> "困难";
    };
    return mode + " / " + difficulty;
  }
}
