package net.infnetwork.snowball.craftov.economy;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * 点券, the server's premium currency (#106): what knife skins are priced in. It is the server's
 * own, not Craftov's, and is never bought with 块币: nothing in Craftov turns one into the other.
 * <p>
 * Craftov reads and changes a balance only through this interface ({@code CraftovPlugin.points()}),
 * so the server's global currency can take over without touching anything else: its plugin
 * registers an implementation with Bukkit's services manager,
 * {@code Bukkit.getServicesManager().register(PointsBank.class, bank, plugin, ServicePriority.Normal)},
 * and Craftov uses it from the next call on. Until then the balance is a placeholder kept in the
 * Craftov profile ({@link ProfilePoints}). Every method is called on the main thread and must
 * answer at once; an implementation backed by a remote store keeps a local copy of the balances
 * of the players online.
 */
public interface PointsBank {

    /** The player's balance, or -1 when it cannot be read right now. */
    long balance(UUID player);

    /** Sets the balance (the admin command). Null when done, otherwise why not, in Chinese. */
    @Nullable String set(UUID player, long amount);

    /**
     * Takes {@code amount} when the balance covers it; {@code reason} says what it paid for (a
     * skin id), for the bank's own records. Null when taken, otherwise why not, in Chinese, shown
     * to the player.
     */
    @Nullable String take(UUID player, long amount, String reason);
}
