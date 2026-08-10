package com.birditch.raidcore.game.plugin.editor;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.map.ManualMapValidationSettings;

public record MapEditorSettings(
    int retainedRevisions,
    int confirmationSeconds,
    int defaultVisibilityRadius,
    int visualizationRefreshTicks,
    int maximumObjectsPerFrame,
    int maximumHologramsPerPlayer,
    ManualMapValidationSettings validation) {

  public MapEditorSettings {
    Preconditions.checkArgument(retainedRevisions >= 1 && retainedRevisions <= 20,
        "retainedRevisions must be 1..20");
    Preconditions.checkArgument(confirmationSeconds >= 5 && confirmationSeconds <= 60,
        "confirmationSeconds must be 5..60");
    Preconditions.checkArgument(defaultVisibilityRadius >= 16 && defaultVisibilityRadius <= 512,
        "defaultVisibilityRadius must be 16..512");
    Preconditions.checkArgument(visualizationRefreshTicks >= 5
        && visualizationRefreshTicks <= 100, "visualizationRefreshTicks must be 5..100");
    Preconditions.checkArgument(maximumObjectsPerFrame >= 16 && maximumObjectsPerFrame <= 500,
        "maximumObjectsPerFrame must be 16..500");
    Preconditions.checkArgument(maximumHologramsPerPlayer >= 4
        && maximumHologramsPerPlayer <= 128,
        "maximumHologramsPerPlayer must be 4..128");
    Preconditions.checkNotNull(validation, "validation");
  }
}
