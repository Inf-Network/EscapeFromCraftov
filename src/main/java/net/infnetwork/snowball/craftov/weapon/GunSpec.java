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
 * @param recoil      vertical camera kick per shot in degrees
 * @param recoilH     max horizontal kick per shot in degrees
 * @param recoilReturn share (0..1) of a spray's camera kick that drifts back once the gun stops firing
 * @param reloadTicks reload duration
 * @param zoom        ADS zoom strength 0..3
 * @param soundVolume how loud the report is (3.5 pistol .. 7 sniper): how far it carries and how loud
 *                    the shooter hears it (weapons.audio.shooter-volume)
 * @param audio       sound set id under craftov:gun.<audio>.* (see tools/art/gunsamples.py)
 * @param action      mechanical action family for reload, dry-fire and casing sounds
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
        double recoil,
        double recoilH,
        double recoilReturn,
        int reloadTicks,
        int zoom,
        int pellets,
        double headMultiplier,
        float soundVolume,
        double moveSpeed,
        String mount,
        @org.jspecify.annotations.Nullable String scope,
        String audio,
        String action
) {
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

    public double millisBetweenShots() {
        return 60_000.0 / rpm;
    }

    /** The same gun with {@code damage} per pellet, for a round that lost some of its punch on the way. */
    public GunSpec withDamage(double damage) {
        return new GunSpec(caliber, slot, magazine, rpm, modes, damage, range, maxRange, hipSpread, adsSpread, recoil, recoilH,
                recoilReturn, reloadTicks, zoom, pellets, headMultiplier, soundVolume, moveSpeed, mount, scope, audio, action);
    }
}
