package com.birditch.raidcore.bukkit;

import com.birditch.raidcore.common.check.Preconditions;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/// Sends players across the proxy via BungeeCord plugin messaging. The outgoing
/// `BungeeCord` channel is registered on construction and must be released with
/// [#unregister] on shutdown.
public final class BungeeConnector {

  private static final String CHANNEL = "BungeeCord";

  private final Plugin plugin;

  public BungeeConnector(Plugin plugin) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
  }

  /// Asks the proxy to move `player` to `serverName` (BungeeCord `Connect`).
  public void send(Player player, String serverName) {
    Preconditions.checkNotNull(player, "player");
    Preconditions.checkNotNull(serverName, "serverName");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeUTF("Connect");
      out.writeUTF(serverName);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to encode BungeeCord Connect payload", e);
    }
    player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
  }

  public void unregister() {
    plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
  }
}
