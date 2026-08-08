package com.birditch.raidcore.lobby.matchmaking;

import java.util.Map;
import java.util.UUID;

/// Boundary through which the matchmaking queue observes and acts on players,
/// keeping [QueueService] free of Bukkit and Adventure types so it can be unit
/// tested with a fake.
///
/// Message delivery is key-based (a `messages.yml` key plus unparsed
/// placeholder values) instead of taking a rendered component: MiniMessage
/// resolution happens behind this seam, so tests never need Adventure on the
/// classpath. All methods are main-thread only; every operation targeting an
/// offline player is a no-op.
public interface PlayerGateway {

  boolean isOnline(UUID playerId);

  /// Sends the message under `messageKey` with `placeholders` resolved as
  /// unparsed values.
  void sendMessage(UUID playerId, String messageKey, Map<String, String> placeholders);

  /// Moves the player to the proxy backend named `proxyName`.
  void connectToServer(UUID playerId, String proxyName);
}
