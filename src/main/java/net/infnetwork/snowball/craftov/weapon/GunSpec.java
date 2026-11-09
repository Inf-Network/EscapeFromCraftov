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
 * @param reloadTicks reload duration
 * @param zoom        ADS zoom strength 0..3
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
        int reloadTicks,
        int zoom,
        int pellets,
        double headMultiplier,
        String sound,
        float soundVolume,
        double moveSpeed,
        String mount,
        @org.jspecify.annotations.Nullable String scope
) {
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
}
