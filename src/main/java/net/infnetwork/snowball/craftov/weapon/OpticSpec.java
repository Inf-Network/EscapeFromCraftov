package net.infnetwork.snowball.craftov.weapon;

import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * A sight that mounts on a gun's rail.
 *
 * @param magnification true power while aiming: things look this many times as big as at the hip,
 *                      whatever field of view the player plays at (see {@link #fovMultiplier})
 * @param overlay       camera overlay texture under craftov:misc/ for magnified scopes, or null for 1x sights
 * @param mounts        rail types it fits: "rifle", "pistol"
 */
public record OpticSpec(double magnification, @Nullable String overlay, Set<String> mounts) {

    /** How long sights take to come up and how much they slow the player, by how strong the optic is. */
    public enum Tier {
        /** Iron sights: no optic at all. */
        IRON,
        /** Red dot and holographic sights, about 1.5x. */
        REFLEX,
        /** 3x and 4x scopes. */
        LOW,
        /** 6x and stronger. */
        HIGH;

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public boolean magnified() {
        return overlay != null;
    }

    public Tier tier() {
        return magnification >= 5.5 ? Tier.HIGH : magnification >= 2.5 ? Tier.LOW : Tier.REFLEX;
    }

    /** The power for the lore: "1.5", "4", "8". */
    public String magnificationLabel() {
        return label(magnification);
    }

    public static String label(double power) {
        return java.math.BigDecimal.valueOf(Math.round(power * 100) / 100.0).stripTrailingZeros().toPlainString();
    }

    /**
     * The client's field-of-view multiplier that shows {@code power} times magnification at a vertical
     * field of view of {@code fov} degrees: the view is a perspective projection, so a thing at the
     * centre looks tan(F/2) / tan(F*m/2) times as big, and m = 2 atan(tan(F/2) / N) / F. At the
     * default 70 degrees 1.5x is 0.715, 4x 0.284 and 8x 0.143.
     */
    public static double fovMultiplier(double power, double fov) {
        if (power <= 1.0) {
            return 1.0;
        }
        double half = Math.toRadians(Math.max(1, Math.min(179, fov)) / 2);
        return 2 * Math.atan(Math.tan(half) / power) / (2 * half);
    }

    /**
     * The power an items.yml from before optics had {@code magnification} meant with its {@code fov}
     * (the client's field-of-view multiplier, at the default 70 degrees), so a server that kept its
     * old items.yml still zooms as before.
     */
    public static double legacyPower(double fov) {
        double f = Math.max(0.05, Math.min(1, fov));
        double half = Math.toRadians(35);
        return Math.tan(half) / Math.tan(half * f);
    }
}
