package net.infnetwork.snowball.craftov.weapon;

import io.papermc.paper.event.packet.ClientTickEndEvent;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * F, the swap-hands key, does one of three things: with Shift held it fires the operator skill
 * (OperatorService), with a gun in hand it switches the fire mode (WeaponService), with a knife it
 * inspects the knife (InspectService). The off hand itself is never used (CarryListener cancels
 * every swap).
 *
 * <p>A client sends F from the middle of its tick, before what else that tick did: Shift going up or
 * down is only sent at the end of the same tick, and the slot change of a hotbar key pressed in the
 * same tick only at the start of the next one. Read as it came in, F pressed as Shift went up fired
 * the skill, Shift and F pressed together inspected the knife instead of firing it, and 3 then F in
 * one tick switched the gun's fire mode instead of inspecting the knife (#104). So F waits for the
 * client's ticks to end (the client sends {@link ClientTickEndEvent} as the last packet of every
 * tick): Shift is read at the end of the tick F was pressed in, the hand at the end of the next one,
 * after its slot change. That is 50 to 100 ms, less than a frame of the inspect or the fire-mode
 * sound. A client that sends no tick ends (a bot) has its F read {@link #FALLBACK_TICKS} server
 * ticks after it came in. A client that sent the first tick end does send them, and its second one
 * is only late (a few frames a second put the next tick in the next frame, or the connection
 * jitters): reading the hand before it would bring the 3-then-F race back, so that F waits up to
 * {@link #LATE_TICKS}.
 *
 * <p>Each F is read on its own and in order, with the Shift and hand of its own tick: two quick taps
 * on a gun step the fire mode twice, as they did when F was read as it came in.
 */
public final class SwapKeyService implements Listener {

    /** Server ticks an F waits for the client's tick ends before it is read anyway. */
    private static final int FALLBACK_TICKS = 3;
    /** Server ticks an F waits for its second tick end once the first came in. */
    private static final int LATE_TICKS = 10;
    /** F presses kept per player until read; more within 0.1 s is a macro, and the rest is dropped. */
    private static final int MAX_PENDING = 4;

    private final CraftovPlugin plugin;
    /** F presses not read yet, by player, oldest first. */
    private final Map<UUID, ArrayDeque<Press>> pending = new HashMap<>();

    private static final class Press {
        final long arrived;
        /** Client tick ends seen since F: the first fixes Shift, the second reads F. */
        int tickEnds;
        boolean sneaking;

        Press(long arrived) {
            this.arrived = arrived;
        }
    }

    public SwapKeyService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onKey(PlayerSwapHandItemsEvent event) {
        event.setCancelled(true);
        ArrayDeque<Press> presses = pending.computeIfAbsent(event.getPlayer().getUniqueId(), id -> new ArrayDeque<>());
        if (presses.size() < MAX_PENDING) {
            presses.addLast(new Press(Bukkit.getCurrentTick()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ClientTickEndEvent event) {
        Player player = event.getPlayer();
        ArrayDeque<Press> presses = pending.get(player.getUniqueId());
        if (presses == null) {
            return;
        }
        for (Press press : presses) {
            if (++press.tickEnds == 1) {
                press.sneaking = player.isSneaking();
            }
        }
        // Presses came in order, so the ones whose second tick end this was are at the front.
        while (!presses.isEmpty() && presses.peekFirst().tickEnds >= 2) {
            read(player, presses.pollFirst());
        }
        if (presses.isEmpty()) {
            pending.remove(player.getUniqueId());
        }
    }

    private void tick() {
        long now = Bukkit.getCurrentTick();
        // A copy of the keys: what a press does (a skill, a sound) must not be able to upset the loop.
        for (UUID id : new ArrayList<>(pending.keySet())) {
            ArrayDeque<Press> presses = pending.get(id);
            Player player = Bukkit.getPlayer(id);
            if (presses == null || player == null) {
                pending.remove(id);
                continue;
            }
            while (!presses.isEmpty()
                    && now - presses.peekFirst().arrived >= (presses.peekFirst().tickEnds == 0 ? FALLBACK_TICKS : LATE_TICKS)) {
                Press press = presses.pollFirst();
                if (press.tickEnds == 0) {
                    press.sneaking = player.isSneaking();
                }
                read(player, press);
            }
            if (presses.isEmpty()) {
                pending.remove(id);
            }
        }
    }

    private void read(Player player, Press press) {
        String what;
        if (press.sneaking) {
            what = "skill";
            plugin.operators().activate(player);
        } else {
            ItemDef def = plugin.items().defOf(player.getInventory().getItemInMainHand());
            if (def != null && def.isGun()) {
                what = "fire mode";
                plugin.weapons().nextFireMode(player);
            } else if (def != null && def.isMelee()) {
                what = "knife";
                plugin.inspect().press(player, def);
            } else {
                what = "nothing";
            }
        }
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info(String.format("[debug] F %s -> %s (sneak=%b sprint=%b slot=%d, read after %d client tick ends, %d ticks)",
                    player.getName(), what, press.sneaking, player.isSprinting(), player.getInventory().getHeldItemSlot(),
                    press.tickEnds, Bukkit.getCurrentTick() - press.arrived));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
