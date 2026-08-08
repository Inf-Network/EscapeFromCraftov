package com.birditch.raidcore.lobby;

import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.matchmaking.MatchmakingService;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

/// The one shared "queue for a raid" flow used by both the /play command and
/// the main menu, so feedback wording never diverges.
public final class PlayAction {

  private PlayAction() {}

  public static void queue(MatchmakingService matchmaking, Messages messages, Player player,
      RaidMapId mapId) {
    Preconditions.checkNotNull(matchmaking, "matchmaking");
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(mapId, "mapId");
    boolean queued = matchmaking.queue(player.getUniqueId(), mapId).isSuccess();
    if (queued) {
      player.sendMessage(messages.raw("queue.joined",
          Placeholder.component("map", MapNames.display(messages, mapId))));
    } else {
      player.sendMessage(messages.raw("queue.already-queued"));
    }
  }
}
