package com.birditch.raidcore.lobby;

import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import net.kyori.adventure.text.Component;

/// Player-facing display name of a map: the `map.<id>` messages entry, falling
/// back to the raw id so unknown maps stay debuggable instead of invisible.
public final class MapNames {

  private MapNames() {}

  public static Component display(Messages messages, RaidMapId mapId) {
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(mapId, "mapId");
    String key = "map." + mapId.value();
    return messages.contains(key) ? messages.raw(key) : Component.text(mapId.value());
  }
}
