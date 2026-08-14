package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;

/** Completed business outcome of one action-tier raid admission attempt. */
public sealed interface RaidAdmissionResult
    permits RaidAdmissionResult.Admitted, RaidAdmissionResult.Rejected {

  /**
   * Durable result committed in PostgreSQL. A replay returns the same claimed
   * rows, original loadout value, and original post-fee balance.
   */
  record Admitted(
      List<StoredItem> claimedItems,
      Money loadoutValue,
      Money balanceAfter,
      boolean replayed) implements RaidAdmissionResult {

    public Admitted {
      claimedItems = List.copyOf(
          Preconditions.checkNotNull(claimedItems, "claimedItems"));
      Preconditions.checkNotNull(loadoutValue, "loadoutValue");
      Preconditions.checkNotNull(balanceAfter, "balanceAfter");
    }
  }

  /** A deterministic business/configuration rejection; no money or item changed. */
  record Rejected(RaidAdmissionRejection reason, String detail)
      implements RaidAdmissionResult {

    public Rejected {
      Preconditions.checkNotNull(reason, "reason");
      Preconditions.checkNotNull(detail, "detail");
      Preconditions.checkArgument(!detail.isBlank(), "detail must not be blank");
    }
  }
}
