package com.birditch.escapefromcraftov.map;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public record ExtractionDefinition(
    String id,
    String displayName,
    CuboidBounds bounds,
    ManualExtractionType extractionType,
    int durationSeconds,
    @Nullable String conditionValue,
    boolean enabled,
    List<ExtractionRuleDefinition> conditions,
    @Nullable ExtractionRouteScope routeScope) implements MapObject {

  public ExtractionDefinition {
    MapIds.requireValid(id, "extraction id");
    Preconditions.checkNotNull(displayName, "displayName");
    Preconditions.checkArgument(!displayName.isBlank(), "displayName must not be blank");
    Preconditions.checkNotNull(bounds, "bounds");
    Preconditions.checkNotNull(extractionType, "extractionType");
    Preconditions.checkArgument(durationSeconds > 0, "durationSeconds must be positive");
    conditions = List.copyOf(Preconditions.checkNotNull(conditions, "conditions"));
    if (extractionType != ManualExtractionType.NORMAL) {
      Preconditions.checkArgument(conditionValue != null && !conditionValue.isBlank(),
          "conditional extraction requires conditionValue");
    }
  }

  /** Source-compatible constructor for schema-v1 single-condition exits. */
  public ExtractionDefinition(String id, String displayName, CuboidBounds bounds,
      ManualExtractionType extractionType, int durationSeconds, @Nullable String conditionValue,
      boolean enabled) {
    this(id, displayName, bounds, extractionType, durationSeconds, conditionValue, enabled,
        List.of(), null);
  }

  /** Source-compatible constructor for exits created before route scopes. */
  public ExtractionDefinition(String id, String displayName, CuboidBounds bounds,
      ManualExtractionType extractionType, int durationSeconds, @Nullable String conditionValue,
      boolean enabled, List<ExtractionRuleDefinition> conditions) {
    this(id, displayName, bounds, extractionType, durationSeconds, conditionValue, enabled,
        conditions, null);
  }

  @Override public MapObjectType type() { return MapObjectType.EXTRACTION; }
  @Override public BlockPosition anchor() { return bounds.center(); }
  @Override public Optional<CuboidBounds> region() { return Optional.of(bounds); }
}
