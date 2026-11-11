package net.infnetwork.snowball.craftov.weapon;

import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * A gun attachment other than a sight (sights are {@link OpticSpec}). Multipliers apply to the gun's
 * own values; 1 leaves a value unchanged.
 *
 * @param slot       muzzle, grip, tactical or mag
 * @param mounts     gun classes it fits: rifle, pistol, shotgun (see {@link Attachments#gunClass})
 * @param suppressed quieter report, no muzzle flash, AI hear it from much closer
 * @param light      tactical light mode set: "light" (steady only) or "strobe" (steady and strobe), or null
 */
public record AttachSpec(String slot, Set<String> mounts, double recoil, double recoilH, double hipSpread, double adsSpread,
                         double reload, double moveSpeed, double magazine, boolean suppressed, @Nullable String light) {
}
