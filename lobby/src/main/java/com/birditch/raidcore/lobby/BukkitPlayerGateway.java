package com.birditch.raidcore.lobby;

import com.birditch.raidcore.bukkit.BungeeConnector;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.lobby.matchmaking.PlayerGateway;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/// Production [PlayerGateway] backed by the Bukkit server: resolves message
/// keys through [Messages] and moves players across the proxy via
/// [BungeeConnector]. Every method silently no-ops for offline players.
public final class BukkitPlayerGateway implements PlayerGateway {

  private final Plugin plugin;
  private final BungeeConnector bungee;
  private final Messages messages;

  public BukkitPlayerGateway(Plugin plugin, BungeeConnector bungee, Messages messages) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.bungee = Preconditions.checkNotNull(bungee, "bungee");
    this.messages = Preconditions.checkNotNull(messages, "messages");
  }

  @Override
  public boolean isOnline(UUID playerId) {
    return player(playerId) != null;
  }

  @Override
  public void sendMessage(UUID playerId, String messageKey, Map<String, String> placeholders) {
    Preconditions.checkNotNull(messageKey, "messageKey");
    Preconditions.checkNotNull(placeholders, "placeholders");
    Player player = player(playerId);
    if (player == null) {
      return;
    }
    TagResolver[] resolvers = placeholders.entrySet().stream()
        .map(entry -> Placeholder.unparsed(entry.getKey(), entry.getValue()))
        .toArray(TagResolver[]::new);
    player.sendMessage(messages.raw(messageKey, resolvers));
  }

  @Override
  public void connectToServer(UUID playerId, String proxyName) {
    Preconditions.checkNotNull(proxyName, "proxyName");
    Player player = player(playerId);
    if (player == null) {
      return;
    }
    bungee.send(player, proxyName);
  }

  private @Nullable Player player(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return plugin.getServer().getPlayer(playerId);
  }
}
