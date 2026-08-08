package com.birditch.raidcore.api.extraction;

/// Gate that must be satisfied before a player may begin extracting.
public interface ExtractionCondition {

    /// Human-readable description used in "Extraction blocked" failure messages.
    String describe();

    boolean isSatisfied(ExtractionContext context);
}
