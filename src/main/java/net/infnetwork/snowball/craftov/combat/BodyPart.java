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

    /** Share of the torso's width on each side that is arm, seen from the front or back. */
    private static final double ARM_BAND = 0.38;

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
     * Classifies a hit point: height picks head / torso / legs, the side of the target's facing picks
     * the arm or leg, and the upper or lower torso picks chest or stomach.
     *
     * <p>Arms are the outer edges of the torso as the shooter sees it ({@code shot} is the round's
     * direction), and only while the target shows its front or back: side-on, the edges are its chest
     * and back. A player's server-side body yaw lags the head by up to 50 degrees and only follows
     * when it walks or swings, so facing comes from the head yaw (the view the client sends); reading
     * arms off the body yaw turned centre-mass hits on a turned player into arm hits that skip the
     * vest.
     */
    public static BodyPart of(LivingEntity target, Vector point, Vector shot) {
        HitZone zone = HitZone.of(target, point);
        if (zone == HitZone.HEAD) {
            return HEAD;
        }
        BoundingBox box = target.getBoundingBox();
        double dx = point.getX() - box.getCenterX();
        double dz = point.getZ() - box.getCenterZ();
        double yaw = Math.toRadians(target.getLocation().getYaw());
        // Facing (-sin, cos); the holder's right-hand side is (-cos, -sin).
        double faceX = -Math.sin(yaw);
        double faceZ = Math.cos(yaw);
        double side = dx * -Math.cos(yaw) + dz * -Math.sin(yaw);
        if (zone == HitZone.LEGS) {
            return side >= 0 ? RIGHT_LEG : LEFT_LEG;
        }
        double length = Math.hypot(shot.getX(), shot.getZ());
        if (length > 1e-6) {
            double sx = shot.getX() / length;
            double sz = shot.getZ() / length;
            // Offset across the line of fire, and half the width of the box as seen along it.
            double across = dx * -sz + dz * sx;
            double halfWidth = box.getWidthX() / 2.0 * Math.abs(sz) + box.getWidthZ() / 2.0 * Math.abs(sx);
            // 1 when the target faces the shooter or turns its back, 0 side-on.
            double front = Math.abs(sx * faceX + sz * faceZ);
            if (Math.abs(across) > halfWidth * (1.0 - ARM_BAND * front)) {
                // Which edge is the right arm: the holder's right-hand side measured across the
                // line of fire (the hit point's depth on the box would skew a turned target).
                double rightAcross = -Math.cos(yaw) * -sz + -Math.sin(yaw) * sx;
                return across * rightAcross > 0 ? RIGHT_ARM : LEFT_ARM;
            }
        }
        double height = Math.max(0.1, box.getHeight());
        double rel = (point.getY() - box.getMinY()) / height;
        return rel >= 0.55 ? CHEST : STOMACH;
    }
}
