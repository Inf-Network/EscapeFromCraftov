package com.birditch.escapefromcraftov.bukkit;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import net.kyori.adventure.text.Component;

/// Player-facing display name of a map: the `map.<id>` messages entry, falling
/// back to a readable title derived from the import id. Machine ids remain
/// available to admin commands and PlaceholderAPI, but are not shown in the
/// normal player HUD when a map has no custom translation yet.
public final class MapNames {

  private MapNames() {}

  public static Component display(Messages messages, CraftovMapId mapId) {
    Preconditions.checkNotNull(messages, "messages");
    Preconditions.checkNotNull(mapId, "mapId");
    String key = "map." + mapId.value();
    return messages.contains(key)
        ? messages.raw(key)
        : Component.text(readableFallback(mapId.value()));
  }

  private static String readableFallback(String value) {
    StringBuilder result = new StringBuilder();
    for (String part : value.split("_+")) {
      if (part.isBlank()) continue;
      if (!result.isEmpty()) result.append(' ');
      result.append(Character.toUpperCase(part.charAt(0)));
      if (part.length() > 1) result.append(part.substring(1));
    }
    return result.isEmpty() ? "Craftov 地图" : result.toString();
  }
}
