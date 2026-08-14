package com.birditch.escapefromcraftov.lobby;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.matchmaking.MatchmakingService;
import com.birditch.escapefromcraftov.api.matchmaking.MatchmakingFailures;
import com.birditch.escapefromcraftov.bukkit.MapNames;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

/// The one shared "queue for a craftov" flow used by both the /play command and
/// the main menu, so feedback wording never diverges.
public final class PlayAction {

  private PlayAction() {}

  public static void queue(MatchmakingService matchmaking, Messages messages, Player player,
      CraftovMapId mapId) {
    Preconditions.checkNotNull(matchmaking, "matchmaking");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(mapId, "mapId");
    var result = matchmaking.queue(player.getUniqueId(), mapId);
    if (result.isSuccess()) {
      player.sendMessage(messages.raw("queue.joined",
          Placeholder.component("map", MapNames.display(messages, mapId))));
      return;
    }
    String messageKey = result.error().filter(MatchmakingFailures.PLAYER_DATA_NOT_READY::equals)
        .map(_ -> "queue.data-loading")
        .orElse("queue.already-queued");
    player.sendMessage(messages.raw(messageKey));
  }
}
