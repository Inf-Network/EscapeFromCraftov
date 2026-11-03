package net.infnetwork.snowball.craftov.match;

import net.infnetwork.snowball.craftov.raid.RaidMode;

import java.util.List;
import java.util.UUID;

/** A party (or solo player) waiting for a raid. */
public record QueueEntry(List<UUID> members, String map, String tier, RaidMode mode, boolean ranked, boolean fill,
                         long since, int rating) {
    public String key() {
        return map + "|" + tier + "|" + mode + "|" + ranked;
    }
}
