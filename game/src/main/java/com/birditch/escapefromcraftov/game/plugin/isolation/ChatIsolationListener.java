package com.birditch.escapefromcraftov.game.plugin.isolation;

import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/// Restricts chat to the sender's isolation group so craftov instances and the
/// lobby world never see each other's messages.
///
/// [AsyncChatEvent] fires OFF the main thread: that handler only performs the
/// thread-safe map read [InstanceWorldIndex#groupOf(java.util.UUID)]. The
/// command handler runs on the main thread and prevents standard private or
/// broadcast chat commands from bypassing the same room boundary. Non-player
/// viewers (console) are always kept.
public final class ChatIsolationListener implements Listener {

  private static final Set<String> TARGETED_CHAT_COMMANDS = Set.of(
      "msg", "message", "tell", "w", "whisper", "pm", "dm");
  private static final Set<String> UNKNOWN_TARGET_COMMANDS = Set.of("r", "reply");
  private static final Set<String> GLOBAL_CHAT_COMMANDS = Set.of("say", "teammsg", "tm");

  private final InstanceWorldIndex index;
  private final Messages messages;

  public ChatIsolationListener(InstanceWorldIndex index, Messages messages) {
    this.index = Preconditions.checkNotNull(index, "index");
    this.messages = Preconditions.checkNotNull(messages, "messages");
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
    if (group.startsWith("game:")) {
      event.renderer((source, displayName, message, viewer) ->
          Component.text("[本局] ", NamedTextColor.DARK_GRAY)
              .append(displayName.color(NamedTextColor.WHITE))
              .append(Component.text(" » ", NamedTextColor.GRAY))
              .append(message));
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onChatCommand(PlayerCommandPreprocessEvent event) {
    Player sender = event.getPlayer();
    String group = index.groupOf(sender.getUniqueId());
    if (!group.startsWith("game:")) return;

    String body = event.getMessage().substring(1).trim();
    if (body.isEmpty()) return;
    String[] parts = body.split("\\s+", 3);
    String command = baseCommand(parts[0]);
    if (TARGETED_CHAT_COMMANDS.contains(command)) {
      if (parts.length < 2) return;
      Player target = Bukkit.getPlayerExact(parts[1]);
      if (target == null && !parts[1].startsWith("@")) return;
      if (target == null || !index.groupOf(target.getUniqueId()).equals(group)) {
        event.setCancelled(true);
        sender.sendMessage(messages.raw("chat.cross-room-blocked"));
      }
      return;
    }
    if (command.equals("me")) {
      if (parts.length < 2) return;
      event.setCancelled(true);
      Component action = messages.raw("chat.room-action",
          Placeholder.component("player", sender.displayName()),
          Placeholder.unparsed("message", body.substring(body.indexOf(' ') + 1).trim()));
      for (Player online : Bukkit.getOnlinePlayers()) {
        if (index.groupOf(online.getUniqueId()).equals(group)) online.sendMessage(action);
      }
      return;
    }
    if (UNKNOWN_TARGET_COMMANDS.contains(command) || GLOBAL_CHAT_COMMANDS.contains(command)) {
      event.setCancelled(true);
      sender.sendMessage(messages.raw("chat.command-blocked"));
    }
  }

  private static String baseCommand(String command) {
    String normalized = command.toLowerCase(Locale.ROOT);
    int namespace = normalized.lastIndexOf(':');
    return namespace < 0 ? normalized : normalized.substring(namespace + 1);
  }
}
