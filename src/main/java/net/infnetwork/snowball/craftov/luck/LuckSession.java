package net.infnetwork.snowball.craftov.luck;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One player's luck bookkeeping for the raid they are in ({@code RaidPlayer.luck}). */
public final class LuckSession {

    /**
     * An item handed out by pity or the quota pool, put hidden into the container being searched.
     *
     * @param uid    instance id on the item, which tells whether it was carried out
     * @param source what paid for it: {@code jackpot}, {@code pity-GOLD}, {@code pity-RED} or {@code quota}
     * @param value  the item's value
     */
    public record Payout(String uid, String item, String source, long value) {
    }

    /** Containers this player has opened themselves: each counts once. */
    public final Set<String> searched = new HashSet<>();
    /** Searches that counted: containers that still had something hidden when this player opened them. */
    public int searches;
    public final List<Payout> payouts = new ArrayList<>();
    /** Jackpots found in this raid: their raid count starts again after it, not with it. */
    public final Set<String> jackpotsFound = new HashSet<>();
}
