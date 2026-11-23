package net.infnetwork.snowball.craftov.weapon;

import java.util.List;

/**
 * Ballistic stats for a firearm.
 *
 * @param damage      body damage per pellet at point blank
 * @param range       distance (blocks) before damage starts falling off
 * @param maxRange    hitscan distance
 * @param hipSpread   cone half-angle in degrees when hip firing
 * @param adsSpread   cone half-angle in degrees when aiming
 * @param moveSpread  degrees the cone widens by at full walking pace (half that while aiming)
 * @param recoil      vertical camera kick per shot in degrees
 * @param recoilH     max horizontal kick per shot in degrees
 * @param recoilReturn share (0..1) of a spray's camera kick that drifts back once the gun stops firing
 * @param reloadTicks reload duration
 * @param adsClass    how the gun handles aimed (see {@link #ADS_CLASSES}): how much aiming slows the
 *                    player and what its iron sights magnify
 * @param soundVolume how loud the report is (3.5 pistol .. 7 sniper): how far it carries and how loud
 *                    the shooter hears it (weapons.audio.shooter-volume)
 * @param audio       sound set id under craftov:gun.<audio>.* (see tools/art/gunsamples.py)
 * @param action      mechanical action family for reload, dry-fire and casing sounds
 * @param handling    操控速度, 0 to 100 (see {@link Handling}): how quickly the sights come up, the gun
 *                    comes up after a switch and the sights after a sprint, and how far aimed shots sway
 */
public record GunSpec(
        String caliber,
        WeaponSlot slot,
        int magazine,
        int rpm,
        List<FireMode> modes,
        double damage,
        double range,
        double maxRange,
        double hipSpread,
        double adsSpread,
        double moveSpread,
        double recoil,
        double recoilH,
        double recoilReturn,
        int reloadTicks,
        String adsClass,
        int pellets,
        double headMultiplier,
        float soundVolume,
        double moveSpeed,
        String mount,
        @org.jspecify.annotations.Nullable String scope,
        String audio,
        String action,
        double handling
) {
    /** The ads-class values items.yml may give; unknown ones count as "rifle". */
    public static final java.util.List<String> ADS_CLASSES = java.util.List.of("pistol", "smg", "rifle", "shotgun", "dmr", "lmg", "bolt");

    /** The ads-class of a gun whose items.yml entry gives none, by its action. */
    public static String defaultAdsClass(String action) {
        return switch (action) {
            case "pistol", "pistol_heavy" -> "pistol";
            case "smg", "pdw" -> "smg";
            case "pump" -> "shotgun";
            case "dmr" -> "dmr";
            case "lmg" -> "lmg";
            case "bolt" -> "bolt";
            default -> "rifle";
        };
    }

    /** Bullets that break the sound barrier crack past a listener; the rest whiz. */
    public boolean supersonic() {
        return switch (caliber) {
            case "9x19", ".45ACP", ".50AE", "12ga" -> false;
            default -> true;
        };
    }

    /** Rail type for attachments: "rifle", "pistol" or "none" (fixed built-in scope). */
    public boolean accepts(OpticSpec optic) {
        return optic.mounts().contains(mount);
    }

    public int ticksBetweenShots() {
        return Math.max(1, (int) Math.round(1200.0 / rpm));
    }

    /** Longest first-person kick of one shot, in ticks (tools/art/models.py KICK_MAX_TICKS). */
    public static final int KICK_MAX_TICKS = 6;

    /**
     * Ticks the first-person kick and flash of one shot last: the time between shots, so every round
     * of a spray kicks on its own, but at least 2 (a frame to snap back, one to settle) and at most
     * {@link #KICK_MAX_TICKS} so a bolt-action rifle does not hold its kick for its whole cycle. The
     * item model is built with the same rule (tools/art/models.py kick_ticks).
     */
    public int kickTicks() {
        return Math.max(2, Math.min(KICK_MAX_TICKS, ticksBetweenShots()));
    }

    public double millisBetweenShots() {
        return 60_000.0 / rpm;
    }

    /** The same gun with {@code damage} per pellet, for a round that lost some of its punch on the way. */
    public GunSpec withDamage(double damage) {
        return new GunSpec(caliber, slot, magazine, rpm, modes, damage, range, maxRange, hipSpread, adsSpread, moveSpread, recoil, recoilH,
                recoilReturn, reloadTicks, adsClass, pellets, headMultiplier, soundVolume, moveSpeed, mount, scope, audio, action, handling);
    }

    /** The same gun with {@code handling}, for a fitted part that changes it (see {@link Attachments}). */
    public GunSpec withHandling(double handling) {
        return new GunSpec(caliber, slot, magazine, rpm, modes, damage, range, maxRange, hipSpread, adsSpread, moveSpread, recoil, recoilH,
                recoilReturn, reloadTicks, adsClass, pellets, headMultiplier, soundVolume, moveSpeed, mount, scope, audio, action, handling);
    }
}
