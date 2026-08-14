package com.birditch.escapefromcraftov.api.game;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Authoritative admission boundary for an action-tier raid.
 *
 * <p>The supplied profile is the immutable map snapshot frozen into the game
 * instance. Business rejections complete normally as {@link
 * RaidAdmissionResult.Rejected}; exceptional completion is reserved for an
 * infrastructure failure that a caller may retry.</p>
 */
public interface RaidAdmissionService {

  CompletableFuture<RaidAdmissionResult> admit(
      UUID playerId, CraftovGameId gameId, RaidActionProfile actionProfile);
}
