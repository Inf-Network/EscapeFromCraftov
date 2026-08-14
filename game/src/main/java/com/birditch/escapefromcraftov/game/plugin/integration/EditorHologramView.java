package com.birditch.escapefromcraftov.game.plugin.integration;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.map.MapObject;
import com.birditch.escapefromcraftov.map.MapValidationIssue;
import java.util.List;
import org.bukkit.Location;

/** Immutable description reconciled by the active Hologram provider. */
public record EditorHologramView(
    String key,
    MapObject object,
    Location location,
    List<MapValidationIssue> validationIssues,
    Style style) {

  public EditorHologramView {
    key = Preconditions.checkNotNull(key, "key");
    Preconditions.checkArgument(key.matches("[a-z0-9_]+"), "invalid hologram key");
    object = Preconditions.checkNotNull(object, "object");
    location = Preconditions.checkNotNull(location, "location").clone();
    validationIssues = List.copyOf(
        Preconditions.checkNotNull(validationIssues, "validationIssues"));
    style = Preconditions.checkNotNull(style, "style");
  }

  @Override
  public Location location() {
    return location.clone();
  }

  public boolean selected() {
    return style == Style.SELECTED;
  }

  public enum Style {
    SELECTED,
    ERROR,
    NEARBY
  }
}
