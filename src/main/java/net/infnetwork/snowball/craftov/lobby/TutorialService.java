package net.infnetwork.snowball.craftov.lobby;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.gui.UiSound;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.raid.ExtractPoint;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * First steps for a new player: open the terminal, try a gun on the range, fit an attachment at the
 * gunsmith, deploy, and extract. Steps count in any order: the guide always points at the first one
 * not done yet, and the first extraction ends it with the starter bonus whatever was skipped on the
 * way (an extraction is what the guide is there to teach). Hints say what to do where the player
 * is: on the range the gunsmith is behind the weapon rack, and in a raid only the extraction can be
 * done, so that is all a raid ever reminds of. Players who extracted before the guide existed are
 * veterans and never see it; whoever turns the guide's pointers off keeps the reward for the first
 * extraction but gets no objective line, waypoints or reminders.
 * <p>
 * The goal is always in view: a line under the HUD's top-left panel says what to do next and how
 * far it is, the compass points at the lobby station it is done at (in a raid it already points at
 * the extracts), a floating arrow only this player sees hangs over that station, and the
 * terminal's guide button lists the steps and makes the button for the next one shine.
 */
public final class TutorialService {

    public enum Step {
        TERMINAL("打开战术终端", "右键第 9 格的战术终端",
                "选中快捷栏第 9 格的<white>战术终端</white>（按 <white><key:key.hotbar.9></white>），右键打开大厅菜单"),
        RANGE("进入靶场", "去靶场武器架试枪",
                "到大厅东侧的<white>靶场武器架</white>，右键进入个人靶场试枪（罗盘和悬浮箭头指向它）"),
        GUNSMITH("装一个配件", "靶场武器架领瞄准镜试装",
                "进入靶场，在武器架领一把枪和一个瞄准镜，再点武器架里的<white>改枪台</white>装上"),
        DEPLOY("出发", "去「开始行动」选图出发",
                "右键大厅的<white>开始行动</white>（或终端里的大按钮），选一张地图出发"),
        EXTRACT("活着撤离", "开始行动，活着撤离",
                "开始行动，搜刮物资后找到<white>撤离点</white>，活着撤离");

        /** Short name: the menu checklist and "next step" lines. */
        public final String title;
        /** The HUD objective line in the lobby: plain text, at most about 11 Chinese characters. */
        public final String objective;
        /** The full hint in chat and on the action bar (MiniMessage). */
        public final String hint;

        Step(String title, String objective, String hint) {
            this.title = title;
            this.objective = objective;
            this.hint = hint;
        }

        int bit() {
            return 1 << ordinal();
        }
    }

    /** What the HUD shows as the current goal: a line of plain text and where it is, if anywhere. */
    public record Objective(String text, @Nullable Location target) {
    }

    /** Lobby stations that can carry the guide's floating arrow. */
    private static final List<String> MARKED = List.of("range_rack", "deploy");
    private static final org.bukkit.NamespacedKey MARKER = net.infnetwork.snowball.craftov.item.Keys.key("guide_marker");
    /** How far above a station's floor its arrow floats: clear of the station's label. */
    private static final double MARKER_HEIGHT = 3.5;

    private final CraftovPlugin plugin;
    /** One arrow per station, hidden from everybody but the players whose next step is there. */
    private final Map<String, ItemDisplay> markers = new HashMap<>();
    /** The station whose arrow each player sees now. */
    private final Map<UUID, String> shown = new HashMap<>();
    private boolean bobUp;

    public TutorialService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::remind, 20L * 30, 20L * 45);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickMarkers, 40L, 10L);
    }

    // ------------------------------------------------------------------ state

    private boolean enabled() {
        return plugin.getConfig().getBoolean("tutorial.enabled", true);
    }

    /**
     * The steps done, as bits. Profiles saved by the in-order guide only have its counter, which
     * stands for that many steps from the first; read here without writing, so a getter on the HUD
     * thread never changes the profile.
     */
    public static int steps(Profile profile) {
        if (profile.tutorialSteps != 0 || profile.tutorialStep <= 0) {
            return profile.tutorialSteps;
        }
        return (1 << Math.min(profile.tutorialStep, Step.values().length)) - 1;
    }

    public static boolean isDone(Profile profile, Step step) {
        return (steps(profile) & step.bit()) != 0;
    }

    /** A player who extracted before the guide began counting anything has nothing to learn from it. */
    private static boolean veteran(Profile profile) {
        return steps(profile) == 0 && profile.stat("extracts") > 0;
    }

    /** The guide is running for this player (whether or not they muted its pointers). */
    public boolean active(Profile profile) {
        return enabled() && !profile.tutorialDone && !veteran(profile);
    }

    /** The guide is running and pointing: objective line, waypoints, reminders. */
    public boolean guided(Profile profile) {
        return active(profile) && !profile.guideMuted;
    }

    /** The first step not done yet, or null when all are. */
    public static @Nullable Step current(Profile profile) {
        int mask = steps(profile);
        for (Step step : Step.values()) {
            if ((mask & step.bit()) == 0) {
                return step;
            }
        }
        return null;
    }

    public static int doneCount(Profile profile) {
        return Integer.bitCount(steps(profile) & ((1 << Step.values().length) - 1));
    }

    /** Marks a step done, whatever came before it; the extraction finishes the guide. */
    public void done(Player player, Step step) {
        Profile profile = plugin.profiles().get(player);
        if (!active(profile) || isDone(profile, step)) {
            return;
        }
        profile.tutorialSteps = steps(profile) | step.bit();
        plugin.profiles().markDirty(player.getUniqueId());
        if (step == Step.EXTRACT) {
            finish(player, profile);
            return;
        }
        player.playSound(player.getLocation(), "craftov:ui.queue", org.bukkit.SoundCategory.MASTER, 0.7f, 1.3f);
        if (profile.guideMuted) {
            return;
        }
        // Deploying is done on the way into a raid, where the extraction is the one step left to do.
        Step next = plugin.raids().raidOf(player) != null ? Step.EXTRACT : current(profile);
        // The action bar, not chat: on the range the entry line is in chat already, and a step done
        // with a click in a menu (the gunsmith) shows on that button.
        Notify.ok(player, "新手指引 " + doneCount(profile) + "/" + Step.values().length + " · " + step.title
                + (next == null ? "" : " <muted>· 下一步：<text>" + next.title));
    }

    /** The guide is over: the starter bonus, once. */
    public void finish(Player player, Profile profile) {
        if (profile.tutorialDone) {
            return;
        }
        profile.tutorialDone = true;
        long bonus = plugin.getConfig().getLong("tutorial.reward", 20_000);
        profile.money += bonus;
        plugin.profiles().markDirty(player.getUniqueId());
        UiSound.REWARD.play(player);
        Text.send(player, "<ok><b>新手指引完成！</b> <accent>奖励 ◈" + Text.money(bonus)
                + " <muted>· 之后可以试试每日任务、藏身处和排位赛。");
    }

    /** The player turned the guide's pointers off or back on. */
    public void mute(Player player, boolean muted) {
        Profile profile = plugin.profiles().get(player);
        profile.guideMuted = muted;
        profile.briefed = true;
        plugin.profiles().markDirty(player.getUniqueId());
    }

    // ------------------------------------------------------------------ hints

    /** The hint for a step where the player is now. */
    public String hint(Player player, Step step) {
        if (step == Step.GUNSMITH && plugin.range().isIn(player)) {
            return "在<white>武器架</white>领一把枪和一个瞄准镜，再点武器架里的<white>改枪台</white>装上";
        }
        if (step == Step.EXTRACT && plugin.raids().raidOf(player) != null) {
            return "找到<white>撤离点</white>（罗盘上的绿色 ◆，左上角有方向和距离），站进去等倒计时结束";
        }
        return step.hint;
    }

    private String line(Profile profile, String hint) {
        return "<accent>新手指引 " + (doneCount(profile) + 1) + "/" + Step.values().length + "：<text>" + hint;
    }

    /** Back in the lobby: the step to do next, in chat. Players who extracted before are let go here. */
    public void show(Player player, Profile profile) {
        if (!profile.tutorialDone && veteran(profile)) {
            profile.tutorialDone = true;
            plugin.profiles().markDirty(player.getUniqueId());
            return;
        }
        Step step = current(profile);
        if (!guided(profile) || step == null) {
            return;
        }
        Text.send(player, line(profile, hint(player, step)));
    }

    /**
     * Every 45 seconds, the step on the action bar: in the lobby and on the range the next step, in a
     * raid only the extraction (nothing else can be done there), and nothing while queueing (the
     * queue timer has the bar) or watching a squadmate.
     */
    private void remind() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Profile profile = plugin.profiles().get(player);
            if (!guided(profile) || plugin.match().queued(player.getUniqueId()) != null) {
                continue;
            }
            Step step;
            if (plugin.raids().raidOf(player) != null) {
                if (!plugin.raids().isInRaid(player) || isDone(profile, Step.EXTRACT)) {
                    continue;
                }
                step = Step.EXTRACT;
            } else if (plugin.lobby().isLobby(player.getWorld()) || plugin.range().isIn(player)) {
                step = current(profile);
            } else {
                continue;
            }
            if (step != null) {
                Notify.hint(player, line(profile, hint(player, step)));
            }
        }
    }

    // ------------------------------------------------------------------ objective

    /**
     * The goal for the HUD's objective line and the compass, or null when there is none to show.
     * Main thread (HudService builds the snapshot from it).
     */
    public @Nullable Objective objective(Player player) {
        Profile profile = plugin.profiles().get(player);
        if (!guided(profile)) {
            return null;
        }
        Raid raid = plugin.raids().raidOf(player);
        if (raid != null) {
            if (!plugin.raids().isInRaid(player) || isDone(profile, Step.EXTRACT)) {
                return null;
            }
            return new Objective("前往撤离点", nearestExtract(raid, player));
        }
        Step step = current(profile);
        if (step == null) {
            return null;
        }
        if (plugin.range().isIn(player)) {
            // Entering the range did the range step: what is left is the gunsmith, or back to the lobby.
            return step == Step.GUNSMITH ? new Objective("领枪和瞄准镜，改枪台装上", plugin.range().rack(player))
                    : new Objective("右键第 9 格离开靶场", null);
        }
        if (!plugin.lobby().isLobby(player.getWorld())) {
            return null;
        }
        return new Objective(step.objective, station(step));
    }

    /** The lobby station a step is done at, or null when it is done in the terminal. */
    public @Nullable String stationId(Step step) {
        return switch (step) {
            case RANGE, GUNSMITH -> "range_rack";
            case DEPLOY, EXTRACT -> "deploy";
            default -> null;
        };
    }

    private @Nullable Location station(Step step) {
        String id = stationId(step);
        return id == null ? null : plugin.lobby().station(id);
    }

    private @Nullable Location nearestExtract(Raid raid, Player player) {
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (ExtractPoint point : raid.extracts) {
            if (!point.available || point.center.getWorld() != player.getWorld() || !plugin.raids().extractOpenByTime(raid, point)) {
                continue;
            }
            double d = point.center.distanceSquared(player.getLocation());
            if (d < bestD) {
                bestD = d;
                best = point.center;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ floating arrows

    /**
     * Keeps one amber arrow over each station a step is done at, and shows each guided player in the
     * lobby only the arrow over their next step (the rest are hidden from them, and all of them from
     * everybody else). The arrows bob and glow: the glow outline shows through walls, so the arrow
     * can be found from anywhere in the hangar.
     */
    private void tickMarkers() {
        World lobby = plugin.lobby().world();
        if (lobby == null) {
            return;
        }
        if (markers.isEmpty()) {
            // Arrows a plugin reload left behind: their visibility belonged to the old instance.
            for (ItemDisplay old : lobby.getEntitiesByClass(ItemDisplay.class)) {
                if (old.getPersistentDataContainer().has(MARKER)) {
                    old.remove();
                }
            }
        }
        bobUp = !bobUp;
        for (String id : MARKED) {
            ItemDisplay marker = markers.get(id);
            if (marker == null || !marker.isValid()) {
                Location at = plugin.lobby().station(id);
                if (at == null) {
                    continue;
                }
                marker = spawnMarker(at.add(0, MARKER_HEIGHT, 0));
                markers.put(id, marker);
                shown.values().removeIf(id::equals); // a new entity: nobody has been shown it yet
            }
            marker.setInterpolationDelay(0);
            marker.setInterpolationDuration(10);
            marker.setTransformation(new Transformation(new Vector3f(0, bobUp ? 0.3f : 0f, 0), new AxisAngle4f(),
                    new Vector3f(2.2f, 2.2f, 2.2f), new AxisAngle4f()));
        }
        for (Player player : lobby.getPlayers()) {
            Profile profile = plugin.profiles().get(player);
            Step step = guided(profile) && plugin.raids().raidOf(player) == null ? current(profile) : null;
            String want = step == null ? null : stationId(step);
            String had = shown.get(player.getUniqueId());
            if (java.util.Objects.equals(want, had)) {
                continue;
            }
            if (had != null && markers.containsKey(had)) {
                player.hideEntity(plugin, markers.get(had));
            }
            if (want != null && markers.containsKey(want)) {
                player.showEntity(plugin, markers.get(want));
                shown.put(player.getUniqueId(), want);
            } else {
                shown.remove(player.getUniqueId());
            }
        }
        // What a player was shown stays shown while they are away in a raid (the server keeps it per
        // player), so only the ones who logged off are forgotten: they come back as a new player.
        shown.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    private ItemDisplay spawnMarker(Location at) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setVisibleByDefault(false);
            d.setPersistent(false);
            d.setItemStack(net.infnetwork.snowball.craftov.gui.UiItems.button("craftov:ui/waypoint", "", List.of(), "guide_marker"));
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setGlowing(true);
            d.setGlowColorOverride(Color.fromRGB(0xF2B544));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(4f);
            d.getPersistentDataContainer().set(MARKER, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        });
    }
}
