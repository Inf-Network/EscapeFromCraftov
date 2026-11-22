package net.infnetwork.snowball.craftov.luck;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Luck bookkeeping of one raid ({@code Raid.luck}): hand-outs their player did not carry out are
 * refunded into the quota once the raid closes, unless someone else carried them out meanwhile
 * (handing one to a squadmate and dying would otherwise pay it twice).
 */
public final class RaidLuck {

    /** A hand-out its player did not carry out. */
    public record Pending(UUID player, String name, LuckSession.Payout payout) {
    }

    /** Instance ids of everything carried out of the raid so far (extracted, or kept in a secure container). */
    public final Set<String> kept = new HashSet<>();
    public final List<Pending> pending = new ArrayList<>();
}
