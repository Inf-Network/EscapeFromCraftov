package com.birditch.escapefromcraftov.api.combat;

import java.util.UUID;

/// Applies damage to a target. Contract only in v1 — no bundled implementation.
public interface DamageService {

    void damage(UUID targetId, double amount, DamageType type);
}
