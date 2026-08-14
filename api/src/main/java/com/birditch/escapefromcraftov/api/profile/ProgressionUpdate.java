package com.birditch.escapefromcraftov.api.profile;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.time.Instant;
import java.util.UUID;

/// Immutable result of one committed XP award.
public record ProgressionUpdate(
        UUID transactionId,
        UUID playerId,
        long awardedExperience,
        long experienceAfter,
        int levelBefore,
        int levelAfter,
        String reason,
        Instant timestamp) {

    public ProgressionUpdate {
        Preconditions.checkNotNull(transactionId, "transactionId");
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkArgument(awardedExperience > 0, "awardedExperience must be positive");
        Preconditions.checkArgument(experienceAfter >= 0, "experienceAfter must not be negative");
        Preconditions.checkArgument(levelBefore >= 1, "levelBefore must be positive");
        Preconditions.checkArgument(levelAfter >= levelBefore, "levelAfter must not decrease");
        Preconditions.checkNotNull(reason, "reason");
        Preconditions.checkNotNull(timestamp, "timestamp");
    }

    public boolean levelledUp() {
        return levelAfter > levelBefore;
    }
}
