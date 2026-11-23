package net.infnetwork.snowball.craftov.combat;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.util.Text;
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
 * stamina faster and slows the player down; above the overload limit sprinting is impossible (the
 * client is held at food level 6, as for a broken leg, see HealthService.updateFood) and every
 * further kilogram slows them much more. Numbers are in config.yml under stamina.
 */
public final class StaminaService implements Listener {

    public static final double MAX = 100.0;

    private static final class State {
        double stamina = MAX;
        boolean exhausted;
        long lastSpend;
        double weight;
        long weightTick = -100;
        boolean overloaded;
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

    /**
     * Sets the player's stamina (an admin's test hook): at 0 they are out of breath until it is back
     * at stamina.recover-at, as if they had run it down.
     */
    public void set(Player player, double value) {
        State s = state(player);
        s.stamina = Math.max(0, Math.min(MAX, value));
        s.lastSpend = Bukkit.getCurrentTick();
        boolean out = s.stamina <= 0 || s.exhausted && s.stamina < recoverAt();
        if (out != s.exhausted) {
            s.exhausted = out;
            plugin.health().updateFood(player);
        }
    }

    /**
     * Spends {@code perTick} stamina this tick, times the load factor, as a sprint does: a slide costs
     * its own (SlideService), since the vanilla client stops sprinting on a seat. Regeneration waits
     * stamina.regen-delay-ticks from here, and at 0 the player is out of breath. Nothing outside a
     * raid or under adrenaline, as for a sprint.
     */
    public void spend(Player player, double perTick) {
        if (perTick <= 0 || !plugin.raids().isInRaid(player) || plugin.health().adrenaline(player)) {
            return;
        }
        State s = state(player);
        s.stamina = Math.max(0, s.stamina - perTick * drainFactor(player));
        s.lastSpend = Bukkit.getCurrentTick();
    }

    /** Stamina a sprint spends a tick before the load factor (stamina.sprint-drain). */
    public double sprintDrain() {
        return setting("sprint-drain", 0.3);
    }

    /** Stamina at which a player out of breath may sprint again. */
    private double recoverAt() {
        return Math.max(0, Math.min(MAX, setting("recover-at", 30)));
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

    private double setting(String key, double fallback) {
        return plugin.getConfig().getDouble("stamina." + key, fallback);
    }

    /** Load (kg) above which weight starts to slow the player and drain stamina faster. */
    public double comfortKg() {
        return setting("comfort-kg", 25);
    }

    /** Load (kg) above which the player cannot sprint at all. */
    public double overloadKg() {
        return setting("overload-kg", 40);
    }

    /** True while the player carries more than {@link #overloadKg()}: no sprinting. */
    public boolean overloaded(Player player) {
        return weight(player) > overloadKg();
    }

    /**
     * Speed lost to the load: penalty-per-kg (1%) for each kilogram over the comfortable load up to
     * the overload limit, overload-penalty-per-kg (2.5%) for each one beyond it, at most max-penalty
     * (50%). It used to stop at 25%, so a pack of gold bars and statues at 57 kg was barely slower.
     */
    public double overweightPenalty(Player player) {
        double weight = weight(player);
        double comfort = comfortKg();
        double overload = Math.max(comfort, overloadKg());
        double penalty = Math.max(0, Math.min(weight, overload) - comfort) * setting("penalty-per-kg", 0.01)
                + Math.max(0, weight - overload) * setting("overload-penalty-per-kg", 0.025);
        return Math.min(setting("max-penalty", 0.5), penalty);
    }

    private double drainFactor(Player player) {
        return 1.0 + Math.max(0, weight(player) - comfortKg()) / 30.0;
    }

    /** Tells the player when the load crosses the overload limit, and stops or allows sprinting. */
    private void checkOverload(Player player, State s, boolean inRaid) {
        boolean now = inRaid && overloaded(player);
        if (now == s.overloaded) {
            return;
        }
        s.overloaded = now;
        plugin.health().updateFood(player);
        if (now) {
            Notify.info(player, "<danger>负重 " + Math.round(s.weight) + "kg，超过 " + Math.round(overloadKg())
                    + "kg：无法冲刺，移动很慢");
            player.playSound(player.getLocation(), "craftov:status.heartbeat", SoundCategory.PLAYERS, 0.5f, 0.8f);
        } else if (inRaid) {
            Notify.ok(player, "负重降到 " + Math.round(overloadKg()) + "kg 以下，可以冲刺了");
        }
    }

    private void tick() {
        long now = Bukkit.getCurrentTick();
        for (Player player : Bukkit.getOnlinePlayers()) {
            State s = state(player);
            boolean inRaid = plugin.raids().isInRaid(player);
            checkOverload(player, s, inRaid);
            if (!inRaid || plugin.health().adrenaline(player)) {
                if (s.stamina < MAX || s.exhausted) {
                    refill(player);
                }
                continue;
            }
            if (player.isSprinting() && !plugin.slides().isSliding(player)) {
                s.stamina = Math.max(0, s.stamina - sprintDrain() * drainFactor(player));
                s.lastSpend = now;
            } else if (now - s.lastSpend > setting("regen-delay-ticks", 15)) {
                s.stamina = Math.min(MAX, s.stamina + (player.isSneaking() ? setting("regen-sneaking", 0.5) : setting("regen", 0.4)));
            }
            if (!s.exhausted && s.stamina <= 0) {
                s.exhausted = true;
                plugin.health().updateFood(player);
                player.playSound(player.getLocation(), "craftov:status.heartbeat", SoundCategory.PLAYERS, 0.6f, 1.3f);
            } else if (s.exhausted && s.stamina >= recoverAt()) {
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
        s.stamina = Math.max(0, s.stamina - setting("jump-cost", 5) * drainFactor(player));
        s.lastSpend = Bukkit.getCurrentTick();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }
}
