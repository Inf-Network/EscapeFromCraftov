package net.infnetwork.snowball.craftov.match;

import net.infnetwork.snowball.craftov.raid.RaidMode;

import java.util.List;
import java.util.UUID;

/** A party (or solo player) waiting for a raid. */
public record QueueEntry(List<UUID> members, String map, String tier, RaidMode mode, boolean ranked, boolean fill,
                         long since, int rating) {
    /** Solo players and squads of any size share one pool per map, difficulty and ranking. */
    public String key() {
        return map + "|" + tier + "|" + ranked;
    }

    /** May be merged with other parties into a full squad. Solo players always play alone. */
    public boolean fillable() {
        return fill && mode != RaidMode.SOLO && members.size() < 3;
    }
}
