package com.birditch.escapefromcraftov.game.plugin.listener;

import com.birditch.escapefromcraftov.api.extraction.ExtractionService;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovExtractionArea;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/// Starts and cancels extraction sessions as players cross generated region
/// boundaries. Work is constant per move apart from the small extraction list.
public final class ExtractionRegionListener implements Listener {

  private final CraftovGameService games;
  private final ExtractionService extractions;
  private final InstanceWorldIndex worlds;
  private final Messages messages;
  private final Map<UUID, String> regionByPlayer = new HashMap<>();

  public ExtractionRegionListener(CraftovGameService games, ExtractionService extractions,
      InstanceWorldIndex worlds, Messages messages) {
    this.games = Preconditions.checkNotNull(games, "games");
    this.extractions = Preconditions.checkNotNull(extractions, "extractions");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
    this.messages = Preconditions.checkNotNull(messages, "messages");
  }

  @EventHandler(ignoreCancelled = true)
  public void onMove(PlayerMoveEvent event) {
    Location from = event.getFrom();
    Location to = event.getTo();
    if (to == null || sameBlock(from, to)) return;
    Player player = event.getPlayer();
    Optional<CraftovGameId> gameId = worlds.gameByWorld(to.getWorld().getName());
    if (gameId.isEmpty()) {
      leaveRegion(player);
      return;
    }
    CraftovGame game = games.find(gameId.get()).orElse(null);
    if (game == null || game.state() != CraftovGameState.RUNNING) {
      leaveRegion(player);
      return;
    }
    CraftovExtractionArea region = game.extractionAreas().stream()
        .filter(candidate -> candidate.contains(to.getX(), to.getY(), to.getZ()))
        .findFirst()
        .orElse(null);
    String previous = regionByPlayer.get(player.getUniqueId());
    String current = region == null ? null : region.pointId().value();
    if (java.util.Objects.equals(previous, current)) return;
    if (previous != null) {
      if (extractions.cancel(player.getUniqueId())) {
        player.sendMessage(messages.raw("extraction.cancelled"));
      }
    }
    if (region == null) {
      regionByPlayer.remove(player.getUniqueId());
      return;
    }
    regionByPlayer.put(player.getUniqueId(), region.pointId().value());
    Result<?> result = extractions.begin(player.getUniqueId(), game.id(),
        region.pointId());
    if (result.isSuccess()) {
      player.sendMessage(messages.raw("extraction.started",
          Placeholder.unparsed("name", region.displayName())));
    } else {
      // A blocked condition is not an active extraction. Do not keep the
      // region edge latched, otherwise a timed/item/paid extraction could
      // never be retried while the player remains inside the authored area.
      regionByPlayer.remove(player.getUniqueId());
      player.sendMessage(messages.raw("extraction.blocked",
          Placeholder.unparsed("reason", result.error().orElse("未知原因"))));
    }
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    regionByPlayer.remove(event.getPlayer().getUniqueId());
  }

  private void leaveRegion(Player player) {
    if (regionByPlayer.remove(player.getUniqueId()) != null
        && extractions.cancel(player.getUniqueId())) {
      player.sendMessage(messages.raw("extraction.cancelled"));
    }
  }

  private static boolean sameBlock(Location first, Location second) {
    return first.getWorld().equals(second.getWorld())
        && first.getBlockX() == second.getBlockX()
        && first.getBlockY() == second.getBlockY()
        && first.getBlockZ() == second.getBlockZ();
  }

}
