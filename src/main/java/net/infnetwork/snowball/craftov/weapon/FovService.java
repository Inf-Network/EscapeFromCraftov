package net.infnetwork.snowball.craftov.weapon;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Nms;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>The client then scales that effect by its "FOV Effects" accessibility slider (100% by default,
 * 0% turns every zoom off), and a sight's true power also depends on the field of view the player
 * plays at. The server can read neither, so players declare them with /cv fov, and nothing checks
 * the declaration. So no declaration may make a sight stronger than it is at vanilla's defaults:
 * the zoom is worked out for the declared field of view, but never one below 70 (a narrower one
 * would ask for more zoom), and it is divided by the declared slider only where the server owner
 * turned that on (weapons.ads.fov-effects-compensation), since anyone could claim a low slider they
 * do not use and get an iron sight at 10x. Players are told to keep FOV Effects at 100% instead.
 */
public final class FovService implements Listener {

    /** FOV Effects below this cannot be made up for: the zoom would need a multiplier the client clamps. */
    public static final double MIN_EFFECTS = 0.05;
    /**
     * The narrowest field of view the zoom is worked out for. A sight's multiplier grows with the field
     * of view (see {@link OpticSpec#fovMultiplier}), so from here no declared value zooms in further
     * than vanilla's default of 70; below it a sight shows a little under its power.
     */
    public static final int MIN_ZOOM_FOV = 70;

    private final CraftovPlugin plugin;
    private final Map<UUID, Nms.SelfView> views = new HashMap<>();
    /** Players told this session that FOV Effects at 0 means no zoom. */
    private final Set<UUID> warned = new HashSet<>();

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
        view.effects = effects(player);
        Nms.installSelf(player, view);
        Nms.sendView(player);
    }

    /**
     * Shows the player a field of view of {@code multiplier} times their normal one (1 = the hip view)
     * and sends it now. Sent even when unchanged: a client that missed one gets it again.
     */
    public void show(Player player, double multiplier) {
        show(player, multiplier, Bukkit.getCurrentTick());
    }

    /** {@link #show(Player, double)}, counted as sent in {@code tick} (see {@link #steer(Player, double, double, long)}). */
    private void show(Player player, double multiplier, long tick) {
        Nms.SelfView view = view(player);
        view.fov = Math.max(0.1, Math.min(1.0, multiplier));
        view.effects = effects(player);
        Nms.sendView(player);
        eased(player).sent(view.fov, tick);
    }

    /**
     * Where the vanilla client's view is. The client does not jump to the field of view the server asks
     * for: each tick it goes half the way there from where it was (GameRenderer.tickFov), so a zoom the
     * server sends over a few ticks showed a tick late and then crept in over four or five more, about
     * a quarter of a second that every sight had, quick or slow (issue #117). This follows the client a
     * step a tick, one client tick for each server tick, from what was sent.
     */
    private static final class Eased {
        /** The client's view once it has taken what was sent in {@code tick}. */
        double view = 1.0;
        /** Its view before that, which a second send in the same tick starts from again. */
        double before = 1.0;
        /** What was sent last. */
        double sent = 1.0;
        long tick = Long.MIN_VALUE;

        /** The client's view before it takes anything sent in {@code now}. */
        double before(long now) {
            if (tick == now) {
                return before;
            }
            if (tick == Long.MIN_VALUE) {
                return view;
            }
            // Each tick since, it went half the way to what was sent last.
            return sent + (view - sent) * Math.pow(0.5, Math.max(0, now - 1 - tick));
        }

        void sent(double value, long now) {
            double from = before(now);
            before = from;
            view = from + (value - from) * 0.5;
            sent = value;
            tick = now;
        }
    }

    private final Map<UUID, Eased> eased = new HashMap<>();

    private Eased eased(Player player) {
        return eased.computeIfAbsent(player.getUniqueId(), id -> new Eased());
    }

    /**
     * How far past a sight's zoom the view is ever asked to go (a share of its multiplier, about as much
     * more magnification), see {@link #steer}.
     */
    private static final double PAST = 0.06;

    /**
     * Has the client's view at {@code at} once it takes this tick's packet, on the way to {@code target}
     * (the sight's own zoom): it asks for twice as far as {@code at} is from where the view is now, so
     * that half the way lands on it. Asking past the target is how the last of the way is covered in a
     * tick instead of five, but a client that takes one packet again and again (the next ones came
     * late: a server or network hitch) goes on toward what it asked for. So the ask never goes further
     * past the target than {@link #PAST} of it, nor further than lands on it in one go: whatever the
     * hitch, the view goes at most about that much past the sight's power (an 8x shows 8.5x for the
     * moment) before the next packets bring it back. (Bounded instead by how far a client that took
     * the packet just once more would go, a hitch of 100 ms could zoom an 8x past 9x, issue #117.)
     */
    public void steer(Player player, double at, double target) {
        steer(player, at, target, Bukkit.getCurrentTick());
    }

    /**
     * {@link #steer(Player, double, double)} sent now but counted as sent in {@code tick}, the next: the
     * client takes it, or the one that tick sends, which steering again in that tick works out the same.
     */
    public void steer(Player player, double at, double target, long tick) {
        double from = eased(player).before(tick);
        double left = Math.abs(from - target);
        double past = Math.min(left, PAST * target);
        double ask = 2 * at - from;
        if (target < from) {
            ask = Math.min(from, Math.max(ask, target - past));
        } else if (target > from) {
            ask = Math.max(from, Math.min(ask, target + past));
        } else {
            ask = target;
        }
        show(player, ask, tick);
    }

    /** The client's view has reached {@code target} and nothing else is on its way (see {@link #steer}). */
    public boolean settled(Player player, double target) {
        Eased e = eased.get(player.getUniqueId());
        return e != null && Math.abs(e.sent - target) < 1e-4 && Math.abs(e.before(Bukkit.getCurrentTick()) - target) < 1e-3;
    }

    /** The server owner lets the zoom make up for a FOV Effects slider the player declared below 100%. */
    public boolean compensates() {
        return plugin.getConfig().getBoolean("weapons.ads.fov-effects-compensation", false);
    }

    /** The slider the zoom is divided by: the declared one where that is turned on, else 100%. */
    private double effects(Player player) {
        return compensates() ? plugin.profiles().get(player).fovEffects : 1.0;
    }

    /** The field-of-view multiplier last shown to the player (1 at the hip). */
    public double shown(Player player) {
        Nms.SelfView view = views.get(player.getUniqueId());
        return view == null ? 1.0 : view.fov;
    }

    /**
     * The multiplier that shows {@code power} times magnification at the field of view the player
     * declared, or at {@link #MIN_ZOOM_FOV} for a narrower one.
     */
    public double multiplier(Player player, double power) {
        return OpticSpec.fovMultiplier(power, Math.max(MIN_ZOOM_FOV, plugin.profiles().get(player).fov));
    }

    /** The self view the network handler reads, for the scope helmet (see HudService). */
    public Nms.@Nullable SelfView selfView(Player player) {
        return views.get(player.getUniqueId());
    }

    /**
     * The player aims for the first time this session: once ever, the tip about FOV Effects; and every
     * session in which they declared it at a value the zoom cannot make up for (below
     * {@link #MIN_EFFECTS}, or below 100% where the server does not compensate), the warning that their
     * sights zoom less or not at all.
     */
    public void aimed(Player player) {
        Profile profile = plugin.profiles().get(player);
        String warning = effectsWarning(profile.fovEffects);
        if (warning != null && warned.add(player.getUniqueId())) {
            Text.send(player, warning + "<muted>按 Esc → 选项 → 辅助功能，把 <white>FOV 特效</white> 调回 100%，"
                    + "再输入 <white>/cv fov " + profile.fov + " 100</white>。");
        }
        if (!profile.fovTip) {
            profile.fovTip = true;
            plugin.profiles().markDirty(player.getUniqueId());
            Text.send(player, tip());
        }
    }

    /** What a FOV Effects slider at {@code effects} (0..1) does to the sights here, or null if they zoom fully. */
    public @Nullable String effectsWarning(double effects) {
        if (effects < MIN_EFFECTS) {
            return "<danger>FOV 特效为 0，瞄准镜无法放大。";
        }
        if (effects < 0.995 && !compensates()) {
            return "<danger>FOV 特效 " + Math.round(effects * 100) + "%：瞄准镜倍率会变小（本服不按 FOV 特效补偿）。";
        }
        return null;
    }

    /** What FOV Effects does to the sights and how to tell the server, with a button to confirm 100%. */
    public String tip() {
        return "<accent>开镜提示：<text>瞄准镜放大靠原版的 <white>选项 → 辅助功能 → FOV 特效</white>，请保持 <white>100%</white>（默认值）。"
                + "调低了倍率会变小，0% 时完全不放大。"
                + (compensates()
                ? "改过视野或 FOV 特效的话，用 <white>/cv fov <视野> <FOV特效%></white> 告诉服务器，倍率会自动补回来。 "
                : "改过视野的话，用 <white>/cv fov <视野></white> 告诉服务器，倍率按视野换算。 ")
                + "<click:run_command:'/cv fov ok'><hover:show_text:'<muted>确认 FOV 特效是 100%'><ok>[已是 100%]</hover></click>";
    }

    /**
     * Records the field of view and FOV Effects (0..100%) the player plays at and applies them to the
     * view at once. Returns null, or why the values were refused.
     */
    public @Nullable String declare(Player player, int fov, int effectsPercent) {
        if (fov < 30 || fov > 110) {
            return "视野要在 30 到 110 之间（原版 选项 → 视野）";
        }
        if (effectsPercent < 0 || effectsPercent > 100) {
            return "FOV 特效要在 0 到 100 之间（原版 选项 → 辅助功能 → FOV 特效）";
        }
        plugin.profiles().edit(player.getUniqueId(), () -> "fov " + fov + " " + effectsPercent, profile -> {
            profile.fov = fov;
            profile.fovEffects = effectsPercent / 100.0;
            profile.fovTip = true;
        });
        // Nothing can check these: the log lets an admin see who claims what (a low slider where the
        // zoom makes up for it is the one that would pay off).
        plugin.getLogger().info(player.getName() + " declared FOV " + fov + ", FOV Effects " + effectsPercent + "%"
                + (compensates() && effectsPercent < 100 ? " (zoom compensated)" : ""));
        warned.remove(player.getUniqueId());
        plugin.weapons().refreshZoom(player);
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        attach(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        views.remove(event.getPlayer().getUniqueId());
        eased.remove(event.getPlayer().getUniqueId());
        warned.remove(event.getPlayer().getUniqueId());
    }
}
