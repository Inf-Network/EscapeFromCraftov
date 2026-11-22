package net.infnetwork.snowball.craftov.luck;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Luck bookkeeping of one raid ({@code Raid.luck}): hand-outs their player did not carry out are
 * refunded into the quota once the raid closes, unless someone else carried them out meanwhile
 * (handing one to a squadmate and dying would otherwise pay it twice), and each player's net
 * result is settled then, when it is known what of their gear went home with a squadmate or comes
 * back through insurance.
 */
public final class RaidLuck {

    /** A hand-out its player did not carry out. */
    public record Pending(UUID player, String name, LuckSession.Payout payout) {
    }

    /** Instance ids of everything carried out of the raid so far (extracted, or kept in a secure container). */
    public final Set<String> kept = new HashSet<>();
    public final List<Pending> pending = new ArrayList<>();
    /** Player -> value of the gear they brought in that a squadmate carried out (or kept dying). */
    public final Map<UUID, Long> credit = new HashMap<>();
    /** Players whose raid is over (extracted, dead, missing), by name: their result waits for the close. */
    public final Map<UUID, String> settled = new LinkedHashMap<>();
}
