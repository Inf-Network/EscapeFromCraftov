package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Accepted resulting snapshot, or the unchanged authoritative snapshot plus a reason. */
public record RaidCarryAdmission(
    boolean accepted,
    RaidCarrySnapshot snapshot,
    @Nullable RaidCarryFailure failure) {

  public RaidCarryAdmission {
    Preconditions.checkNotNull(snapshot, "snapshot");
    Preconditions.checkArgument(accepted == (failure == null),
        "accepted must be true exactly when failure is absent");
  }

  public Optional<RaidCarryFailure> rejection() {
    return Optional.ofNullable(failure);
  }
}
