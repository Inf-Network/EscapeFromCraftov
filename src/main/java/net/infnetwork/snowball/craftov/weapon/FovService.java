package net.infnetwork.snowball.craftov.weapon;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Nms;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The player's field of view, which is the zoom of every sight. The vanilla client scales its view
 * by (movement speed / walking speed + 1) / 2, the walking speed coming from the server's abilities
 * packet; this service owns that packet. At the hip the walking speed sent is the movement speed
 * itself, so the view stays exactly as wide while armour, a heavy pack, a broken leg or aiming slow
 * the player down (vanilla would narrow it), and only sprinting widens it as usual. While aiming it
 * is the value that shows the sight's magnification. Every update of the movement speed goes out in
 * one bundle with a matching walking speed, so the client never draws a tick with one and not the
 * other, and gamemode changes or respawns, which resend the abilities, keep the zoom (see
 * {@link Nms.SelfView}).
 */
public final class FovService implements Listener {

    private final CraftovPlugin plugin;
    private final Map<UUID, Nms.SelfView> views = new HashMap<>();

    public FovService(CraftovPlugin plugin) {
        this.plugin = plugin;
        for (Player player : Bukkit.getOnlinePlayers()) {
            attach(player);
        }
    }

    private Nms.SelfView view(Player player) {
        return views.computeIfAbsent(player.getUniqueId(), id -> new Nms.SelfView());
    }

    private void attach(Player player) {
        Nms.SelfView view = view(player);
        Nms.installSelf(player, view);
        Nms.sendView(player);
    }

    /**
     * Shows the player a field of view of {@code multiplier} times their normal one (1 = the hip view)
     * and sends it now. Sent even when unchanged: a client that missed one gets it again.
     */
    public void show(Player player, double multiplier) {
        Nms.SelfView view = view(player);
        view.fov = Math.max(0.1, Math.min(1.0, multiplier));
        Nms.sendView(player);
    }

    /** The field-of-view multiplier last shown to the player (1 at the hip). */
    public double shown(Player player) {
        Nms.SelfView view = views.get(player.getUniqueId());
        return view == null ? 1.0 : view.fov;
    }

    /** The self view the network handler reads, for the scope helmet (see HudService). */
    public Nms.@Nullable SelfView selfView(Player player) {
        return views.get(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        attach(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        views.remove(event.getPlayer().getUniqueId());
    }
}
