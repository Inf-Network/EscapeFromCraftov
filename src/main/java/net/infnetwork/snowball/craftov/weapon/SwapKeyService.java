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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * F, the swap-hands key, does one of three things: with Shift held it fires the operator skill
 * (OperatorService), with a gun in hand it switches the fire mode (WeaponService), with a knife it
 * inspects the knife (InspectService). The off hand itself is never used (CarryListener cancels
 * every swap).
 *
 * <p>A client sends F from the middle of its tick, before what else that tick did: Shift going up or
 * down and W let go (and so a sprint ending) are only sent at the end of the same tick, and the slot
 * change of a hotbar key pressed in the same tick only at the start of the next one. Read as it came
 * in, F pressed as Shift went up fired the skill, Shift and F pressed together inspected the knife
 * instead of firing it, F as W went up after a sprint was refused as sprinting, and 3 then F in one
 * tick switched the gun's fire mode instead of inspecting the knife (#104). So F waits for the
 * client's ticks to end (the client sends {@link ClientTickEndEvent} as the last packet of every
 * tick): Shift and the sprint are read at the end of the tick F was pressed in, the hand at the end
 * of the next one, after its slot change. That is 50 to 100 ms, less than a frame of the inspect or
 * the fire-mode sound. A client that sends no tick ends (a bot) has its F read
 * {@link #FALLBACK_TICKS} server ticks after it came in.
 */
public final class SwapKeyService implements Listener {

    /** Server ticks an F waits for the client's tick ends before it is read anyway. */
    private static final int FALLBACK_TICKS = 3;

    private final CraftovPlugin plugin;
    /** F presses not read yet, by player. */
    private final Map<UUID, Press> pending = new HashMap<>();

    private static final class Press {
        final long arrived;
        /** Client tick ends seen since F: the first fixes Shift and the sprint, the second reads F. */
        int tickEnds;
        boolean sneaking;
        boolean sprinting;

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
        // A second F before the first is read is part of the same press: no one taps F twice in 0.1 s.
        pending.putIfAbsent(event.getPlayer().getUniqueId(), new Press(Bukkit.getCurrentTick()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ClientTickEndEvent event) {
        Player player = event.getPlayer();
        Press press = pending.get(player.getUniqueId());
        if (press == null) {
            return;
        }
        if (++press.tickEnds == 1) {
            press.sneaking = player.isSneaking();
            press.sprinting = player.isSprinting();
        } else {
            pending.remove(player.getUniqueId());
            read(player, press);
        }
    }

    private void tick() {
        long now = Bukkit.getCurrentTick();
        for (Iterator<Map.Entry<UUID, Press>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Press> entry = it.next();
            Press press = entry.getValue();
            if (now - press.arrived < FALLBACK_TICKS) {
                continue;
            }
            it.remove();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            if (press.tickEnds == 0) {
                press.sneaking = player.isSneaking();
                press.sprinting = player.isSprinting();
            }
            read(player, press);
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
                plugin.inspect().press(player, def, press.sprinting);
            } else {
                what = "nothing";
            }
        }
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info(String.format("[debug] F %s -> %s (sneak=%b sprint=%b slot=%d, read after %d client tick ends, %d ticks)",
                    player.getName(), what, press.sneaking, press.sprinting, player.getInventory().getHeldItemSlot(),
                    press.tickEnds, Bukkit.getCurrentTick() - press.arrived));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
