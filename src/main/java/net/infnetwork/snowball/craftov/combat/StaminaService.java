package net.infnetwork.snowball.craftov.combat;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stamina and carry weight. Sprinting and jumping cost stamina, it regenerates after a short pause,
 * and at zero the player cannot sprint until it recovers. Weight above the comfortable load drains
 * stamina faster and slows the player down.
 */
public final class StaminaService implements Listener {

    public static final double MAX = 100.0;
    private static final double COMFORT_KG = 25.0;
    private static final double RECOVER_AT = 30.0;

    private static final class State {
        double stamina = MAX;
        boolean exhausted;
        long lastSpend;
        double weight;
        long weightTick = -100;
    }

    private final CraftovPlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();

    public StaminaService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private State state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), k -> new State());
    }

    public double stamina(Player player) {
        return state(player).stamina;
    }

    public boolean exhausted(Player player) {
        return state(player).exhausted;
    }

    public void refill(Player player) {
        State s = state(player);
        s.stamina = MAX;
        if (s.exhausted) {
            s.exhausted = false;
            plugin.health().updateFood(player);
        }
    }

    /** Total carried weight in kilograms, cached for a second. */
    public double weight(Player player) {
        State s = state(player);
        long now = Bukkit.getCurrentTick();
        if (now - s.weightTick >= 20) {
            s.weightTick = now;
            double total = 0;
            for (ItemStack stack : player.getInventory().getContents()) {
                total += weightOf(stack);
            }
            s.weight = total;
        }
        return s.weight;
    }

    public double weightOf(ItemStack stack) {
        ItemDef def = plugin.items().defOf(stack);
        if (def == null) {
            return 0;
        }
        double total = def.weight() * stack.getAmount();
        if (ItemFactory.hasContents(stack)) {
            for (var record : plugin.items().contents(stack)) {
                ItemDef inner = plugin.registry().get(record.id);
                if (inner != null) {
                    total += inner.weight() * record.amount;
                }
            }
        }
        return total;
    }

    /** Speed lost to an overweight load: 1% per kilogram over the comfortable load, at most 25%. */
    public double overweightPenalty(Player player) {
        return Math.min(0.25, Math.max(0, weight(player) - COMFORT_KG) * 0.01);
    }

    private double drainFactor(Player player) {
        return 1.0 + Math.max(0, weight(player) - COMFORT_KG) / 25.0;
    }

    private void tick() {
        long now = Bukkit.getCurrentTick();
        for (Player player : Bukkit.getOnlinePlayers()) {
            State s = state(player);
            if (!plugin.raids().isInRaid(player) || plugin.health().adrenaline(player)) {
                if (s.stamina < MAX || s.exhausted) {
                    refill(player);
                }
                continue;
            }
            if (player.isSprinting()) {
                s.stamina = Math.max(0, s.stamina - 0.55 * drainFactor(player));
                s.lastSpend = now;
            } else if (now - s.lastSpend > 15) {
                s.stamina = Math.min(MAX, s.stamina + (player.isSneaking() ? 0.75 : 0.6));
            }
            if (!s.exhausted && s.stamina <= 0) {
                s.exhausted = true;
                plugin.health().updateFood(player);
                player.playSound(player.getLocation(), "craftov:status.heartbeat", SoundCategory.PLAYERS, 0.6f, 1.3f);
            } else if (s.exhausted && s.stamina >= RECOVER_AT) {
                s.exhausted = false;
                plugin.health().updateFood(player);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onJump(PlayerJumpEvent event) {
        Player player = event.getPlayer();
        if (!plugin.raids().isInRaid(player) || plugin.health().adrenaline(player)) {
            return;
        }
        State s = state(player);
        s.stamina = Math.max(0, s.stamina - 7 * drainFactor(player));
        s.lastSpend = Bukkit.getCurrentTick();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }
}
