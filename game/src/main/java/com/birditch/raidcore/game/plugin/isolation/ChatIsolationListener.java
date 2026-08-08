package com.birditch.raidcore.game.plugin.isolation;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.game.plugin.instance.InstanceWorldIndex;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/// Restricts chat to the sender's isolation group so raid instances and the
/// lobby world never see each other's messages.
///
/// [AsyncChatEvent] fires OFF the main thread: this listener only performs the
/// thread-safe map read [InstanceWorldIndex#groupOf(java.util.UUID)] and never
/// touches game services or other main-thread-confined state. Non-player
/// viewers (console) are always kept.
public final class ChatIsolationListener implements Listener {

  private final InstanceWorldIndex index;

  public ChatIsolationListener(InstanceWorldIndex index) {
    this.index = Preconditions.checkNotNull(index, "index");
  }

  @EventHandler
  public void onChat(AsyncChatEvent event) {
    String group = index.groupOf(event.getPlayer().getUniqueId());
    event
        .viewers()
        .removeIf(
            viewer ->
                viewer instanceof Player player
                    && !index.groupOf(player.getUniqueId()).equals(group));
  }
}
