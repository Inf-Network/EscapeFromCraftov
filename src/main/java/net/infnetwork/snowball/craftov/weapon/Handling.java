package net.infnetwork.snowball.craftov.weapon;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

/**
 * 操控速度: how quickly a gun is brought to bear, 0 (a bare bolt-action rifle would be about 15) to
 * 100. Each gun has its own in items.yml ({@code handling}); a fitted sight takes some off (a red dot
 * next to nothing, an 8x a lot) and grips, muzzle devices and magazines add or take a little
 * ({@code handling} on the part). From it come how long the sights take to come up, how long the gun
 * takes to come up after a switch, how long after a sprint the sights can come up, and how far the
 * aimed shots sway off the crosshair while the gun settles. Every time runs in a straight line from
 * its {@code slow} value at handling 0 to its {@code quick} value at 100 (weapons.handling in
 * config.yml), in whole server ticks.
 */
public final class Handling {

    /** Handling of a gun whose items.yml entry gives none and that the jar does not know either, by ads-class. */
    public static double fallback(String adsClass) {
        return switch (adsClass) {
            case "pistol" -> 88;
            case "smg" -> 85;
            case "shotgun" -> 60;
            case "dmr" -> 45;
            case "lmg" -> 38;
            case "bolt" -> 16;
            default -> 65; // rifle
        };
    }

    /** What a sight takes off handling when items.yml gives no number, by how strong it is. */
    public static double opticFallback(OpticSpec.Tier tier) {
        return switch (tier) {
            case IRON, REFLEX -> 0;
            case LOW -> -11;
            case HIGH -> -24;
        };
    }

    private Handling() {
    }

    public static double clamp(double handling) {
        return Math.max(0, Math.min(100, handling));
    }

    /** The value of weapons.handling.{@code key} at this handling: slow at 0, quick at 100. */
    private static double at(ConfigurationSection config, String key, double slow, double quick, double handling) {
        double from = config.getDouble("weapons.handling." + key + ".slow", slow);
        double to = config.getDouble("weapons.handling." + key + ".quick", quick);
        return from + (to - from) * clamp(handling) / 100.0;
    }

    private static int ticks(double seconds) {
        return (int) Math.round(Math.max(0, seconds) * 20);
    }

    /** Ticks the sights take to come up: 0.5 s at handling 0 to 0.1 s at 100 (at least one). */
    public static int adsTicks(ConfigurationSection config, double handling) {
        return Math.max(1, ticks(at(config, "ads", 0.5, 0.1, handling)));
    }

    /** Ticks the gun takes to come up once it is in the hand: 0.85 s at handling 0 to 0.25 s at 100. */
    public static int drawTicks(ConfigurationSection config, double handling) {
        return ticks(at(config, "draw", 0.85, 0.25, handling));
    }

    /** Ticks after a sprint ends before the sights can come up: 0.4 s at handling 0 to 0.1 s at 100. */
    public static int sprintOutTicks(ConfigurationSection config, double handling) {
        return ticks(at(config, "sprint-out", 0.4, 0.1, handling));
    }

    /** How far (degrees) aimed shots sway off the crosshair the moment the sights are up. */
    public static double sway(ConfigurationSection config, double handling) {
        return Math.max(0, at(config, "sway", 0.1, 0.0, handling));
    }

    /** The number shown for a gun's handling: a whole number. */
    public static String label(double handling) {
        return Long.toString(Math.round(clamp(handling)));
    }

    /** Seconds for the lore: "0.20s". */
    public static String seconds(int ticks) {
        return String.format(Locale.ROOT, "%.2fs", ticks / 20.0);
    }
}
