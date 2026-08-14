package com.birditch.escapefromcraftov.api.combat;

import java.util.UUID;

/// Heals a target. Contract only in v1 — no bundled implementation.
public interface MedicalService {

    void heal(UUID targetId, double amount);
}
