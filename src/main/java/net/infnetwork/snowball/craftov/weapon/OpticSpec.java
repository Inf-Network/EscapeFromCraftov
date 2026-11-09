package net.infnetwork.snowball.craftov.weapon;

import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * A sight that mounts on a gun's rail.
 *
 * @param fov     field-of-view multiplier while aiming (0.25 = 4x)
 * @param overlay camera overlay texture under craftov:misc/ for magnified scopes, or null for 1x sights
 * @param mounts  rail types it fits: "rifle", "pistol"
 */
public record OpticSpec(double fov, @Nullable String overlay, Set<String> mounts) {
    public boolean magnified() {
        return overlay != null;
    }

    public String magnification() {
        double x = 1.0 / fov;
        return x < 1.6 ? (x < 1.25 ? "1" : "1.5") : String.valueOf(Math.round(x));
    }
}
