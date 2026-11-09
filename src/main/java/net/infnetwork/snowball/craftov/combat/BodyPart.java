package net.infnetwork.snowball.craftov.combat;

import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Where a wound sits. Injuries are tracked per part and shown on the HUD body figure. */
public enum BodyPart {
    HEAD("头部", "head", HitZone.HEAD),
    CHEST("胸部", "chest", HitZone.TORSO),
    STOMACH("腹部", "stomach", HitZone.TORSO),
    LEFT_ARM("左臂", "left_arm", HitZone.TORSO),
    RIGHT_ARM("右臂", "right_arm", HitZone.TORSO),
    LEFT_LEG("左腿", "left_leg", HitZone.LEGS),
    RIGHT_LEG("右腿", "right_leg", HitZone.LEGS);

    private final String displayName;
    private final String key;
    private final HitZone zone;

    BodyPart(String displayName, String key, HitZone zone) {
        this.displayName = displayName;
        this.key = key;
        this.zone = zone;
    }

    public String displayName() {
        return displayName;
    }

    /** Short id used by HUD placeholders and art files. */
    public String key() {
        return key;
    }

    public HitZone zone() {
        return zone;
    }

    public boolean isArm() {
        return this == LEFT_ARM || this == RIGHT_ARM;
    }

    public boolean isLeg() {
        return this == LEFT_LEG || this == RIGHT_LEG;
    }

    /** Arms and legs can fracture; head and torso cannot. */
    public boolean canFracture() {
        return isArm() || isLeg();
    }

    /** Covered by body armor (helmets cover the head separately). */
    public boolean armored() {
        return this == CHEST || this == STOMACH;
    }

    public static BodyPart defaultFor(HitZone zone) {
        return switch (zone) {
            case HEAD -> HEAD;
            case TORSO -> CHEST;
            case LEGS -> LEFT_LEG;
        };
    }

    /**
     * Classifies a hit point: height picks head / torso / legs, the offset sideways from the
     * target's facing picks the arm or leg, and the upper or lower torso picks chest or stomach.
     */
    public static BodyPart of(LivingEntity target, Vector point) {
        HitZone zone = HitZone.of(target, point);
        if (zone == HitZone.HEAD) {
            return HEAD;
        }
        BoundingBox box = target.getBoundingBox();
        double yaw = Math.toRadians(target.getBodyYaw());
        // Facing (-sin, cos); the holder's right-hand side is (-cos, -sin).
        double side = (point.getX() - box.getCenterX()) * -Math.cos(yaw) + (point.getZ() - box.getCenterZ()) * -Math.sin(yaw);
        if (zone == HitZone.LEGS) {
            return side >= 0 ? RIGHT_LEG : LEFT_LEG;
        }
        double halfWidth = box.getWidthX() / 2.0;
        if (Math.abs(side) > halfWidth * 0.62) {
            return side > 0 ? RIGHT_ARM : LEFT_ARM;
        }
        double height = Math.max(0.1, box.getHeight());
        double rel = (point.getY() - box.getMinY()) / height;
        return rel >= 0.55 ? CHEST : STOMACH;
    }
}
