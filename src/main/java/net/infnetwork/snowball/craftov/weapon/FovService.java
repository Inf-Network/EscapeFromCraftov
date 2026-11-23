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
 * plays at. The server can read neither, so players declare them with /cv fov: the zoom is divided
 * by the slider and worked out for their field of view, and a sight shows its power either way.
 */
public final class FovService implements Listener {

    /** FOV Effects below this cannot be made up for: the zoom would need a multiplier the client clamps. */
    public static final double MIN_EFFECTS = 0.05;

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
        view.effects = plugin.profiles().get(player).fovEffects;
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
        view.effects = plugin.profiles().get(player).fovEffects;
        Nms.sendView(player);
    }

    /** The field-of-view multiplier last shown to the player (1 at the hip). */
    public double shown(Player player) {
        Nms.SelfView view = views.get(player.getUniqueId());
        return view == null ? 1.0 : view.fov;
    }

    /** The multiplier that shows {@code power} times magnification at the field of view the player declared. */
    public double multiplier(Player player, double power) {
        return OpticSpec.fovMultiplier(power, plugin.profiles().get(player).fov);
    }

    /** The self view the network handler reads, for the scope helmet (see HudService). */
    public Nms.@Nullable SelfView selfView(Player player) {
        return views.get(player.getUniqueId());
    }

    /**
     * The player aims for the first time this session: once ever, the tip about FOV Effects; and every
     * session in which they declared it below {@link #MIN_EFFECTS}, the warning that sights cannot zoom.
     */
    public void aimed(Player player) {
        Profile profile = plugin.profiles().get(player);
        if (profile.fovEffects < MIN_EFFECTS && warned.add(player.getUniqueId())) {
            Text.send(player, "<danger>FOV 特效为 0，瞄准镜无法放大。<muted>按 Esc → 选项 → 辅助功能，把 <white>FOV 特效</white> 调回 100%，"
                    + "再输入 <white>/cv fov " + profile.fov + " 100</white>。");
        }
        if (!profile.fovTip) {
            profile.fovTip = true;
            plugin.profiles().markDirty(player.getUniqueId());
            Text.send(player, tip());
        }
    }

    /** What FOV Effects does to the sights and how to tell the server, with a button to confirm 100%. */
    public static String tip() {
        return "<accent>开镜提示：<text>瞄准镜放大靠原版的 <white>选项 → 辅助功能 → FOV 特效</white>，请保持 <white>100%</white>（默认值）。"
                + "调低了倍率会变小，0% 时完全不放大。改过视野或 FOV 特效的话，用 <white>/cv fov <视野> <FOV特效%></white> 告诉服务器，倍率会自动补回来。 "
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
        warned.remove(event.getPlayer().getUniqueId());
    }
}
