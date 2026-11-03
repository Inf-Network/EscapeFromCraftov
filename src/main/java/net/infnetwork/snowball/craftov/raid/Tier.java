package net.infnetwork.snowball.craftov.raid;

import org.bukkit.configuration.ConfigurationSection;

/** Raid difficulty: loadout requirement, loot quality and AI strength. */
public record Tier(String id, String name, String color, long minValue, int lootShift, double aiCount,
                   double aiAccuracy, int aiGear, boolean boss, double xpMultiplier, long entryFee) {

    public String tag() {
        return "<" + color + ">" + name + "</" + color + ">";
    }

    public static Tier read(String id, ConfigurationSection s) {
        return new Tier(id, s.getString("name", id), s.getString("color", "#FFFFFF"), s.getLong("min-value", 0),
                s.getInt("loot-shift", 0), s.getDouble("ai-count", 1.0), s.getDouble("ai-accuracy", 0.7),
                s.getInt("ai-gear", 0), s.getBoolean("boss", false), s.getDouble("xp", 1.0), s.getLong("entry-fee", 0));
    }
}
