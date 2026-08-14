package com.birditch.escapefromcraftov.game;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import java.util.Optional;

/// Supplies immutable map gameplay data when a craftov instance is created.
///
/// The Paper plugin adapts administrator-authored map definitions to this domain-facing
/// contract. Core tests and embedders may use [#EMPTY], which preserves the
/// built-in development extraction point.
@FunctionalInterface
public interface CraftovMapProvider {

    CraftovMapProvider EMPTY = _ -> Optional.of(CraftovMapRuntime.development());

    Optional<CraftovMapRuntime> runtime(CraftovMapId mapId);
}
