package net.infnetwork.snowball.craftov.combat;

import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public enum HitZone {
    HEAD("头部"),
    TORSO("躯干"),
    LEGS("腿部");

    private final String displayName;

    HitZone(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Classifies a hit by its height within the target's bounding box. */
    public static HitZone of(LivingEntity target, Vector point) {
        return of(target.getBoundingBox(), target.getEyeHeight(), point);
    }

    /**
     * Classifies a hit by its height within {@code box}, a body whose eyes are {@code eyeHeight} above
     * its feet: the target's box as the shooter saw it, which lag compensation rewinds
     * ({@link HitboxHistory}). The head line moves with the eyes, so a hit is read on the pose it was
     * aimed at, not on the one the target has taken since (crouching moves it from 72% to 63% up).
     */
    public static HitZone of(BoundingBox box, double eyeHeight, Vector point) {
        double height = Math.max(0.1, box.getHeight());
        double rel = (point.getY() - box.getMinY()) / height;
        double headLine = Math.max(0.0, (eyeHeight - 0.32) / height);
        if (rel >= headLine) {
            return HEAD;
        }
        if (rel < 0.40) {
            return LEGS;
        }
        return TORSO;
    }
}
