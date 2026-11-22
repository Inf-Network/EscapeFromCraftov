package net.infnetwork.snowball.craftov.raid;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Raid difficulty: loadout requirement, loot quality and AI strength.
 *
 * @param lootShift own loot shift, which every roll in the raid starts from
 * @param maxShift  highest loot shift any roll in the raid gets, container tier bonus included
 */
public record Tier(String id, String name, String color, long minValue, int lootShift, int maxShift, double aiCount,
                   double aiAccuracy, int aiGear, boolean boss, double xpMultiplier, long entryFee) {

    public String tag() {
        return "<" + color + ">" + name + "</" + color + ">";
    }

    /**
     * Loot shift of a roll in this tier, {@code bonus} above the tier's own: the container's tier on
     * the map, or 1 for the boss and supply drops. Without the cap a 常规 safe of tier 2 rolled at
     * shift 2, as good as 绝密.
     */
    public int shift(int bonus) {
        return Math.max(0, Math.min(maxShift, lootShift + bonus));
    }

    /**
     * Keys added after the first release (max-shift, entry-fee) fall back to the plugin's own
     * config.yml for a tier of the same id when the server's copy does not have them, so updating
     * the jar is enough; a tier the plugin does not ship gets one shift above its own (at most 2)
     * and no entry fee.
     */
    public static Tier read(String id, ConfigurationSection s) {
        int lootShift = s.getInt("loot-shift", 0);
        int maxShift = s.contains("max-shift") ? s.getInt("max-shift") : Math.min(2, lootShift + 1);
        return new Tier(id, s.getString("name", id), s.getString("color", "#FFFFFF"), s.getLong("min-value", 0),
                lootShift, Math.max(lootShift, maxShift), s.getDouble("ai-count", 1.0), s.getDouble("ai-accuracy", 0.7),
                s.getInt("ai-gear", 0), s.getBoolean("boss", false), s.getDouble("xp", 1.0), s.getLong("entry-fee"));
    }
}
