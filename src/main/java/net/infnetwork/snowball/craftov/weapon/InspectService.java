package net.infnetwork.snowball.craftov.weapon;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.util.Notify;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
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
 * <p>The rule (#104, #116): with a knife in hand, F inspects, unless the player is downed, revives
 * or spectates, and then the action bar says why. A sprint is no reason: F inspects on the run, the
 * sprint goes on through the inspect, and starting to sprint does not end one. F during an inspect
 * starts it over. F during a swing waits for the swing to finish and starts the inspect then.
 *
 * <p>Swinging (a cut, Q, a click on a block), switching slot, being downed or reviving ends it at
 * once by clearing the cooldown. It never touches the knife's swing interval, so a cut during an
 * inspect lands as usual.
 */
public final class InspectService implements Listener {

    /** Ticks the arm swings on the client after a cut, Q or a click (vanilla, no haste or fatigue). */
    private static final int SWING_TICKS = 6;

    private final CraftovPlugin plugin;
    /** Players inspecting a knife: the tick it ends, the hotbar slot and the knife it started with. */
    private final Map<UUID, Inspect> active = new HashMap<>();
    /** F pressed during a swing: the tick, the hotbar slot and the knife, until the swing is over. */
    private final Map<UUID, Inspect> waiting = new HashMap<>();
    /** The tick of each player's last swing of the main hand. */
    private final Map<UUID, Long> swung = new HashMap<>();

    /** An inspect, or a press waiting for a swing: {@code at} is the end, or the tick F was read. */
    private record Inspect(long at, int slot, String item) {
    }

    public InspectService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Whether the player is inspecting a knife right now. */
    public boolean inspecting(Player player) {
        Inspect inspect = active.get(player.getUniqueId());
        return inspect != null && Bukkit.getCurrentTick() < inspect.at;
    }

    private boolean swinging(Player player) {
        return Bukkit.getCurrentTick() - swung.getOrDefault(player.getUniqueId(), -1000L) < SWING_TICKS;
    }

    /** F with this knife in hand and without Shift, read by SwapKeyService once the client's tick is over. */
    public void press(Player player, ItemDef knife) {
        waiting.remove(player.getUniqueId());
        if (swinging(player) && refusal(player) == null) {
            // Started now, the inspect would play over the swing (or, F and a click in one client
            // tick, the click would not end it): it starts as the swing ends instead (tick).
            waiting.put(player.getUniqueId(), new Inspect(Bukkit.getCurrentTick(),
                    player.getInventory().getHeldItemSlot(), knife.id()));
            debug(player, "F ->", "waiting for the swing");
            return;
        }
        start(player, knife, "");
    }

    /**
     * Inspects the knife in hand at once, as F would but without waiting for a swing to end: the
     * skin preview of the 外观 menu (SkinService). Null when it started, otherwise why not (the
     * action bar says so too).
     */
    public @Nullable String inspectNow(Player player) {
        ItemDef def = knife(player);
        if (def == null) {
            return "手上没有刀";
        }
        waiting.remove(player.getUniqueId());
        String why = refusal(player);
        start(player, def, " (skin preview)");
        return why;
    }

    /**
     * Starts an inspect of the knife in hand, or starts a running one over: the last moments of an
     * inspect look like the knife at rest, and a press there used to do nothing. A downed,
     * reviving or spectating player cannot inspect, and the action bar says so: a press that does
     * nothing without a word looks like a key that only works some of the time.
     */
    private void start(Player player, ItemDef def, String note) {
        String why = refusal(player);
        if (why != null) {
            refuse(player, why);
            return;
        }
        debug(player, "F ->", (inspecting(player) ? "restarted" : "started") + note);
        int ticks = Math.max(1, plugin.getConfig().getInt("combat.melee.inspect-ticks", 50));
        active.put(player.getUniqueId(), new Inspect(Bukkit.getCurrentTick() + ticks,
                player.getInventory().getHeldItemSlot(), def.id()));
        player.setCooldown(ItemFactory.INSPECT_GROUP, ticks);
    }

    /**
     * Says why F did not inspect. The bleed-out bar of a downed player and the revive bar of a
     * reviver hold the action bar and would swallow the hint, so the reason goes on that bar too.
     */
    private void refuse(Player player, String why) {
        debug(player, "F ->", "refused: " + why);
        Notify.hint(player, why);
        if (plugin.downed().isDowned(player)) {
            plugin.downed().refused(player, why);
        } else if (plugin.downed().isReviving(player)) {
            plugin.downed().refusedWhileReviving(player, why);
        }
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

    /**
     * Why the player cannot inspect now (the action bar line), or null when nothing stops it. A
     * sprint is not a reason (#116): the inspect is only a pose of the knife in first person.
     */
    private @Nullable String refusal(Player player) {
        if (plugin.downed().isDowned(player)) {
            return "倒地时无法检视";
        }
        if (plugin.downed().isReviving(player)) {
            return "救援中无法检视";
        }
        return player.getGameMode() == GameMode.SPECTATOR ? "旁观时无法检视" : null;
    }

    /**
     * Starts the presses whose swing is over, then catches what has no event of its own: the knife
     * moved out of the hand in the inventory, the player downed, reviving a squadmate, or put into
     * spectator.
     */
    private void tick() {
        long now = Bukkit.getCurrentTick();
        // A press waits this long at most for the swings to stop (a player cutting on and on).
        int swingWait = Math.max(SWING_TICKS, plugin.getConfig().getInt("combat.melee.inspect-swing-wait-ticks", 12));
        for (Iterator<Map.Entry<UUID, Inspect>> it = waiting.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Inspect> entry = it.next();
            Inspect press = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            ItemDef def = player == null ? null : knife(player);
            if (player == null || def == null || !def.id().equals(press.item)
                    || player.getInventory().getHeldItemSlot() != press.slot) {
                it.remove();
            } else if (!swinging(player)) {
                it.remove();
                start(player, def, " after the swing");
            } else if (now - press.at >= swingWait) {
                it.remove();
                refuse(player, "出刀中无法检视");
            }
        }
        for (Iterator<Map.Entry<UUID, Inspect>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Inspect> entry = it.next();
            Inspect inspect = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || now >= inspect.at) {
                it.remove();
                continue;
            }
            ItemDef def = knife(player);
            if (def == null || !def.id().equals(inspect.item) || player.getInventory().getHeldItemSlot() != inspect.slot
                    || refusal(player) != null) {
                it.remove();
                debug(player, "stop", def == null || !def.id().equals(inspect.item) ? "knife gone" : "downed, reviving or spectating");
                player.setCooldown(ItemFactory.INSPECT_GROUP, 0);
            }
        }
    }

    /** A cut, or any other swing of the knife hand. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerArmSwingEvent event) {
        if (event.getHand() == EquipmentSlot.HAND) {
            swung.put(event.getPlayer().getUniqueId(), (long) Bukkit.getCurrentTick());
            if (concerns(event.getPlayer(), null)) {
                debug(event.getPlayer(), "swing", "");
            }
            stop(event.getPlayer(), "swing");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (concerns(player, player.getInventory().getItem(event.getNewSlot()))) {
            debug(player, "slot", event.getPreviousSlot() + " -> " + event.getNewSlot());
        }
        stop(event.getPlayer(), "slot switch");
        waiting.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Whether a swing or slot switch is worth a debug line: with debug on, and only for a knife in
     * hand (or in {@code switchedTo}, the slot switched to), an inspect running or a press waiting.
     * The gun trigger swings the arm every tick it is held, and a line each would bury the rest.
     */
    private boolean concerns(Player player, @Nullable ItemStack switchedTo) {
        if (!plugin.getConfig().getBoolean("debug", false)) {
            return false;
        }
        UUID id = player.getUniqueId();
        if (active.containsKey(id) || waiting.containsKey(id) || knife(player) != null) {
            return true;
        }
        ItemDef to = switchedTo == null ? null : plugin.items().defOf(switchedTo);
        return to != null && to.isMelee();
    }

    /**
     * With {@code debug: true}, one line for each F with a knife and what came of it, and for each
     * event that can end an inspect of a knife: what tools/shots/scenes/inspectkeys.py reads to tell
     * why a press did nothing.
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
        UUID id = event.getPlayer().getUniqueId();
        active.remove(id);
        waiting.remove(id);
        swung.remove(id);
    }
}
