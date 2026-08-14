package com.birditch.escapefromcraftov.game.plugin.extraction;

import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.extraction.SwitchActivatedExtractionCondition;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import com.birditch.escapefromcraftov.map.BlockPosition;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Activates every authored exit bound to a lever in the same raid world. */
public final class ExtractionSwitchListener implements Listener {

  private final CraftovGameService games;
  private final InstanceWorldIndex worlds;

  public ExtractionSwitchListener(CraftovGameService games, InstanceWorldIndex worlds) {
    this.games = Preconditions.checkNotNull(games, "games");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
  }

  @EventHandler(ignoreCancelled = true)
  public void onLever(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
      return;
    }
    Block block = event.getClickedBlock();
    if (block == null || block.getType() != Material.LEVER) return;
    Optional<CraftovGameId> gameId = worlds.gameByWorld(block.getWorld().getName());
    if (gameId.isEmpty()) return;
    CraftovGame game = games.find(gameId.get()).orElse(null);
    if (game == null || game.state() != CraftovGameState.RUNNING) return;
    boolean activeMember = game.players().stream().anyMatch(raidPlayer ->
        raidPlayer.playerId().equals(event.getPlayer().getUniqueId()) && !raidPlayer.extracted());
    if (!activeMember) return;
    Set<String> opened = activateAt(game,
        new BlockPosition(block.getX(), block.getY(), block.getZ()));
    if (opened.isEmpty()) return;
    Component notice = Component.text("撤离闸门已开启：" + String.join("、", opened),
        NamedTextColor.GREEN);
    for (var raidPlayer : game.players()) {
      Player online = Bukkit.getPlayer(raidPlayer.playerId());
      if (online != null && online.isOnline()) online.sendMessage(notice);
    }
  }

  /** Pure main-thread activation path, exposed for focused runtime tests. */
  public static Set<String> activateAt(CraftovGame game, BlockPosition position) {
    Preconditions.checkNotNull(game, "game");
    Preconditions.checkNotNull(position, "position");
    Set<String> opened = new LinkedHashSet<>();
    for (ExtractionPoint point : game.extractionPoints()) {
      for (var condition : point.conditions()) {
        if (condition instanceof SwitchActivatedExtractionCondition lever
            && lever.position().equals(position) && lever.activate()) {
          opened.add(point.displayName());
        }
      }
    }
    return Set.copyOf(opened);
  }
}
