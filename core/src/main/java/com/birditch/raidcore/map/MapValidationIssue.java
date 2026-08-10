package com.birditch.raidcore.map;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public record MapValidationIssue(
    MapValidationSeverity severity,
    String code,
    @Nullable String objectId,
    String message) {

  public MapValidationIssue {
    Preconditions.checkNotNull(severity, "severity");
    MapIds.requireValid(code, "validation code");
    Preconditions.checkNotNull(message, "message");
    Preconditions.checkArgument(!message.isBlank(), "message must not be blank");
  }

  public Optional<String> affectedObjectId() {
    return Optional.ofNullable(objectId);
  }
}
