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
        BoundingBox box = target.getBoundingBox();
        double height = Math.max(0.1, box.getHeight());
        double rel = (point.getY() - box.getMinY()) / height;
        double headLine = Math.max(0.0, (target.getEyeHeight() - 0.32) / height);
        if (rel >= headLine) {
            return HEAD;
        }
        if (rel < 0.40) {
            return LEGS;
        }
        return TORSO;
    }
}
