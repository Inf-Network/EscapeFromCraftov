package com.birditch.raidcore.game;

import com.birditch.raidcore.api.game.RaidMapId;
import java.util.Optional;

/// Supplies immutable map gameplay data when a raid instance is created.
///
/// The Paper plugin adapts administrator-authored map definitions to this domain-facing
/// contract. Core tests and embedders may use [#EMPTY], which preserves the
/// built-in development extraction point.
@FunctionalInterface
public interface RaidMapProvider {

    RaidMapProvider EMPTY = _ -> Optional.of(RaidMapRuntime.development());

    Optional<RaidMapRuntime> runtime(RaidMapId mapId);
}
