package net.infnetwork.snowball.craftov.combat;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pose;
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
    /** Share of a prone body's length that is head, from the front end. */
    private static final double PRONE_HEAD = 0.25;
    /** Share of a prone body's length that is legs, from the back end. */
    private static final double PRONE_LEGS = 0.35;

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
     *
     * <p>A prone player has no height to go by and is classified along its body ({@link #ofProne}).
     */
    public static BodyPart of(LivingEntity target, Vector point, Vector shot) {
        if (prone(target)) {
            return ofProne(target, point, shot);
        }
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

    /**
     * A player lying flat: crawling (downed, or squeezed into a one-block gap) or swimming. The box is
     * then a 0.6 cube with the eyes 0.4 up, so the height split of {@link HitZone#of} put the head line
     * 13% up the box and nearly every hit, and every hit on the top face, on the head.
     */
    public static boolean prone(LivingEntity target) {
        return target instanceof HumanEntity
                && (target.getPose() == Pose.SWIMMING || target.getBoundingBox().getHeight() < 1.0);
    }

    /**
     * Classifies a hit on a prone body by where it lands along the body: the front quarter (the
     * way the body faces) is the head, the back 35% the legs, and the middle the chest (front half)
     * or stomach, with the outer edges across the body as the arms. The client draws the crawl along
     * the body yaw, which only turns as far as it must to stay within reach of the head, so the
     * body yaw is used here, not the view.
     *
     * <p>Arms show only to a round that comes down on the body or along it: one fired at the flank
     * meets the side face, which is all arm or all chest by width alone, so the arm edges narrow to
     * nothing as the line of fire turns side-on, as they do on an upright target.
     */
    private static BodyPart ofProne(LivingEntity target, Vector point, Vector shot) {
        BoundingBox box = target.getBoundingBox();
        double dx = point.getX() - box.getCenterX();
        double dz = point.getZ() - box.getCenterZ();
        double yaw = Math.toRadians(target.getBodyYaw());
        double faceX = -Math.sin(yaw);
        double faceZ = Math.cos(yaw);
        double rightX = -Math.cos(yaw);
        double rightZ = -Math.sin(yaw);
        // How far the box reaches along the body and across it: 0.3 square-on, up to 0.42 diagonally.
        double halfLength = box.getWidthX() / 2.0 * Math.abs(faceX) + box.getWidthZ() / 2.0 * Math.abs(faceZ);
        double halfWidth = box.getWidthX() / 2.0 * Math.abs(rightX) + box.getWidthZ() / 2.0 * Math.abs(rightZ);
        // 0 at the feet end, 1 at the head end.
        double along = Math.max(0, Math.min(1, ((dx * faceX + dz * faceZ) / Math.max(1e-6, halfLength) + 1) / 2));
        double side = dx * rightX + dz * rightZ;
        if (along >= 1.0 - PRONE_HEAD) {
            return HEAD;
        }
        if (along < PRONE_LEGS) {
            return side >= 0 ? RIGHT_LEG : LEFT_LEG;
        }
        double length = shot.length();
        double flank = length < 1e-6 ? 0 : Math.abs(shot.getX() * rightX + shot.getZ() * rightZ) / length;
        double band = ARM_BAND * (1.0 - flank);
        if (band > 1e-3 && Math.abs(side) > halfWidth * (1.0 - band)) {
            return side >= 0 ? RIGHT_ARM : LEFT_ARM;
        }
        return along >= (PRONE_LEGS + 1.0 - PRONE_HEAD) / 2.0 ? CHEST : STOMACH;
    }
}
