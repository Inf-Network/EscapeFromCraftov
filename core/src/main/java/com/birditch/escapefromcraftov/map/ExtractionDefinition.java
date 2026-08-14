package com.birditch.escapefromcraftov.map;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public record ExtractionDefinition(
    String id,
    String displayName,
    CuboidBounds bounds,
    ManualExtractionType extractionType,
    int durationSeconds,
    @Nullable String conditionValue,
    boolean enabled) implements MapObject {

  public ExtractionDefinition {
    MapIds.requireValid(id, "extraction id");
    Preconditions.checkNotNull(displayName, "displayName");
    Preconditions.checkArgument(!displayName.isBlank(), "displayName must not be blank");
    Preconditions.checkNotNull(bounds, "bounds");
    Preconditions.checkNotNull(extractionType, "extractionType");
    Preconditions.checkArgument(durationSeconds > 0, "durationSeconds must be positive");
    if (extractionType != ManualExtractionType.NORMAL) {
      Preconditions.checkArgument(conditionValue != null && !conditionValue.isBlank(),
          "conditional extraction requires conditionValue");
    }
  }

  @Override public MapObjectType type() { return MapObjectType.EXTRACTION; }
  @Override public BlockPosition anchor() { return bounds.center(); }
  @Override public Optional<CuboidBounds> region() { return Optional.of(bounds); }
}
