package com.birditch.escapefromcraftov.game.plugin.start;

import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.time.Duration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/// Paper presentation for automatic starts; each message is room-scoped.
public final class BukkitCraftovStartNotifier implements CraftovStartNotifier {

  private static final Title.Times COUNTDOWN_TIMES = Title.Times.times(
      Duration.ofMillis(100), Duration.ofMillis(900), Duration.ofMillis(100));
  private final Messages messages;

  public BukkitCraftovStartNotifier(Messages messages) {
    this.messages = Preconditions.checkNotNull(messages, "messages");
  }

  @Override
  public void countdown(CraftovGame game, int remainingSeconds, int minimumPlayers) {
    forEachOnline(game, player -> {
      player.sendActionBar(messages.raw("game.start-countdown-actionbar",
          Placeholder.unparsed("seconds", Integer.toString(remainingSeconds)),
          Placeholder.unparsed("players", Integer.toString(game.playerCount())),
          Placeholder.unparsed("minimum", Integer.toString(minimumPlayers))));
      if (remainingSeconds <= 5 || remainingSeconds % 5 == 0) {
        player.showTitle(Title.title(
            messages.raw("game.start-countdown-title",
                Placeholder.unparsed("seconds", Integer.toString(remainingSeconds))),
            messages.raw("game.start-countdown-subtitle"), COUNTDOWN_TIMES));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.2f);
      }
    });
  }

  @Override
  public void cancelled(CraftovGame game, int minimumPlayers) {
    forEachOnline(game, player -> {
      player.showTitle(Title.title(messages.raw("game.start-cancelled-title"),
          messages.raw("game.start-cancelled-subtitle",
              Placeholder.unparsed("minimum", Integer.toString(minimumPlayers))),
          COUNTDOWN_TIMES));
      player.sendActionBar(messages.raw("game.start-cancelled-actionbar",
          Placeholder.unparsed("players", Integer.toString(game.playerCount())),
          Placeholder.unparsed("minimum", Integer.toString(minimumPlayers))));
    });
  }

  @Override
  public void started(CraftovGame game) {
    forEachOnline(game, player -> {
      player.showTitle(Title.title(messages.raw("game.start-title"),
          messages.raw("game.start-subtitle"), Title.Times.times(
              Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
      player.sendActionBar(messages.raw("game.start-actionbar"));
      player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1f);
    });
  }

  private static void forEachOnline(CraftovGame game,
      java.util.function.Consumer<Player> action) {
    for (CraftovPlayer participant : game.players()) {
      Player player = Bukkit.getPlayer(participant.playerId());
      if (player != null && player.isOnline()) action.accept(player);
    }
  }
}
