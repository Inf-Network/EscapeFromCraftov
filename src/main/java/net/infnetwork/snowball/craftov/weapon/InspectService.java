package net.infnetwork.snowball.craftov.weapon;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Knife inspect: F with a knife in hand (sneak+F stays the operator skill; SwapKeyService reads the
 * key) turns the knife over in first person for a couple of seconds, each knife its own way. It is
 * only for show. The server runs the knife's {@code craftov:inspect} cooldown group for
 * {@code combat.melee.inspect-ticks}, and the client steps through the inspect frames of the item
 * model while that counts down (tools/art/knife_inspect.py), as it does for the gun reload; nobody
 * else sees it.
 *
 * <p>Swinging (a cut, Q, a click on a block), switching slot, sprinting, being downed or reviving
 * ends it at once by clearing the cooldown. It never touches the knife's swing interval, so a cut
 * during an inspect lands as usual.
 */
public final class InspectService implements Listener {

    private final CraftovPlugin plugin;
    /** Players inspecting a knife: the tick it ends, the hotbar slot and the knife it started with. */
    private final Map<UUID, Inspect> active = new HashMap<>();

    private record Inspect(long end, int slot, String item) {
    }

    public InspectService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private boolean inspecting(Player player) {
        Inspect inspect = active.get(player.getUniqueId());
        return inspect != null && Bukkit.getCurrentTick() < inspect.end;
    }

    /**
     * F with this knife in hand and without Shift, read by SwapKeyService once the client's tick is
     * over; {@code sprinting} is whether the player still sprinted at the end of that tick.
     */
    public void press(Player player, ItemDef knife, boolean sprinting) {
        start(player, knife, sprinting);
    }

    /**
     * Starts an inspect of the knife in hand, or starts a running one over: the last moments of an
     * inspect look like the knife at rest, and a press there used to do nothing. A sprinting,
     * downed, reviving or spectating player cannot inspect.
     */
    private void start(Player player, ItemDef def, boolean sprinting) {
        if (!free(player, sprinting)) {
            debug(player, "F ->", sprinting ? "refused: sprinting" : "refused: downed, reviving or spectating");
            return;
        }
        debug(player, "F ->", inspecting(player) ? "restarted" : "started");
        int ticks = Math.max(1, plugin.getConfig().getInt("combat.melee.inspect-ticks", 50));
        active.put(player.getUniqueId(), new Inspect(Bukkit.getCurrentTick() + ticks,
                player.getInventory().getHeldItemSlot(), def.id()));
        player.setCooldown(ItemFactory.INSPECT_GROUP, ticks);
    }

    /** Ends a running inspect at once: the client snaps back to the knife's normal pose. */
    private void stop(Player player, String why) {
        if (active.remove(player.getUniqueId()) != null) {
            debug(player, "stop", why);
            player.setCooldown(ItemFactory.INSPECT_GROUP, 0);
        }
    }

    private @Nullable ItemDef knife(Player player) {
        ItemDef def = plugin.items().defOf(player.getInventory().getItemInMainHand());
        return def != null && def.isMelee() ? def : null;
    }

    private boolean free(Player player, boolean sprinting) {
        return !sprinting && player.getGameMode() != GameMode.SPECTATOR
                && !plugin.downed().isDowned(player) && !plugin.downed().isReviving(player);
    }

    /**
     * Catches what has no event of its own: the knife moved out of the hand in the inventory, the
     * player downed, reviving a squadmate, or put into spectator.
     */
    private void tick() {
        long now = Bukkit.getCurrentTick();
        for (Iterator<Map.Entry<UUID, Inspect>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Inspect> entry = it.next();
            Inspect inspect = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || now >= inspect.end) {
                it.remove();
                continue;
            }
            ItemDef def = knife(player);
            if (def == null || !def.id().equals(inspect.item) || player.getInventory().getHeldItemSlot() != inspect.slot
                    || !free(player, player.isSprinting())) {
                it.remove();
                debug(player, "stop", def == null || !def.id().equals(inspect.item) ? "knife gone" : "not free");
                player.setCooldown(ItemFactory.INSPECT_GROUP, 0);
            }
        }
    }

    /** A cut, or any other swing of the knife hand. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerArmSwingEvent event) {
        if (event.getHand() == EquipmentSlot.HAND) {
            debug(event.getPlayer(), "swing", "");
            stop(event.getPlayer(), "swing");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        debug(event.getPlayer(), "slot", event.getPreviousSlot() + " -> " + event.getNewSlot());
        stop(event.getPlayer(), "slot switch");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent event) {
        debug(event.getPlayer(), "sprint", String.valueOf(event.isSprinting()));
        if (event.isSprinting()) {
            stop(event.getPlayer(), "sprint");
        }
    }

    /**
     * With {@code debug: true}, one line for each F with a knife and what came of it, and for each
     * event that can end an inspect: what tools/shots/scenes/inspectkeys.py reads to tell why a
     * press did nothing.
     */
    private void debug(Player player, String what, String detail) {
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info(String.format("[debug] inspect %s %s %s tick=%d slot=%d sprint=%b sneak=%b",
                    player.getName(), what, detail, Bukkit.getCurrentTick(), player.getInventory().getHeldItemSlot(),
                    player.isSprinting(), player.isSneaking()));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
    }
}
