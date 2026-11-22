package net.infnetwork.snowball.craftov.match;

import net.infnetwork.snowball.craftov.raid.RaidMode;

import java.util.List;
import java.util.UUID;

/** A party (or solo player) waiting for a raid. */
public record QueueEntry(List<UUID> members, String map, String tier, RaidMode mode, boolean ranked, boolean fill,
                         long since) {
    /**
     * One pool per map and difficulty. Ranked is left out on purpose: it only decides whether the
     * entry's players win or lose rank points, so ranked and casual players still meet.
     */
    public String key() {
        return map + "|" + tier;
    }

    /** May be merged with other parties into a full squad. Solo players always play alone. */
    public boolean fillable() {
        return fill && mode != RaidMode.SOLO && members.size() < 3;
    }
}
