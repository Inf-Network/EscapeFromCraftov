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
 * gunsmith, deploy, and extract. Steps count in any order (one bit each in
 * {@link Profile#tutorialMask}): the guide always points at the first one not done yet, and
 * finishing all five pays the starter bonus. Hints say what to do where the player is: on the range
 * the gunsmith is behind the weapon rack, and in a raid only the extraction can be done, so that is
 * all a raid ever reminds of. Players who extracted before the guide existed are veterans and never
 * see it. A new player is first shown the briefing ({@link Briefing}), which starts the guide or
 * skips it; whoever skips still has the steps counted and the bonus paid, but gets no objective
 * line, waypoints or reminders, until the terminal's guide button turns them back on.
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

    private static final int ALL = (1 << Step.values().length) - 1;

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
     * When the guide was released (75f7382b, 2026-11-11T13:17:51Z). A player whose first raid came
     * later began with the guide.
     */
    private static final long GUIDE_RELEASED = 1_794_403_071_000L;

    /**
     * Turns the old step counter of a saved profile into the set of finished steps: a counter at
     * {@code n} had the first {@code n} steps done. The old build also lost the guide for a new player
     * who extracted before reaching the extract step: their next step took them for a veteran and
     * closed it without the reward, and until then it stayed open but silent. Such a guide is opened
     * again with the deploy and the extract that broke it counted. Two kinds of profile show it:
     * <ul>
     *   <li>a counter at 1 to 4 and an extract: the old counter only moved while the player had no
     *       extract, so the extract came after the guide had begun;</li>
     *   <li>no counter, an extract, and every raid on record after the guide's release: a new player
     *       who deployed from the lobby station or {@code /cv deploy}, which skip the terminal step.
     *       The old builds kept a player's last 20 raid reports, so with fewer the first raid is among
     *       them, and a veteran's first raid came before the guide.</li>
     * </ul>
     * Called on load. A reopened guide has steps on record, so it never matches twice.
     */
    public static void migrate(Profile profile) {
        int steps = Math.clamp(profile.tutorialStep, 0, Step.values().length);
        profile.tutorialMask |= (1 << steps) - 1;
        profile.tutorialStep = 0;
        if (steps < Step.values().length && profile.stat("extracts") > 0
                && (steps > 0 || profile.tutorialMask == 0 && beganWithGuide(profile))) {
            profile.tutorialDone = false;
            profile.tutorialMask |= Step.DEPLOY.bit() | Step.EXTRACT.bit();
        }
    }

    private static boolean beganWithGuide(Profile profile) {
        if (profile.reports.isEmpty() || profile.reports.size() >= 20) {
            return false;
        }
        for (Profile.RaidReport report : profile.reports) {
            if (report == null || report.time < GUIDE_RELEASED) {
                return false;
            }
        }
        return true;
    }

    public static boolean isDone(Profile profile, Step step) {
        return (profile.tutorialMask & step.bit()) != 0;
    }

    /**
     * Someone who has extracted without a single step on record extracted before the guide existed
     * (every extract counts as a step before it counts as an extract): nothing to teach them. Pure,
     * so the menu and the HUD can ask; {@link #show} closes the guide for them on their way into the
     * lobby, once and without a reward.
     */
    private static boolean veteran(Profile profile) {
        return profile.tutorialMask == 0 && profile.stat("extracts") > 0;
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
        int mask = profile.tutorialMask;
        for (Step step : Step.values()) {
            if ((mask & step.bit()) == 0) {
                return step;
            }
        }
        return null;
    }

    public static int doneCount(Profile profile) {
        return Integer.bitCount(profile.tutorialMask & ALL);
    }

    /** Marks a step done, whatever order it comes in, then pays out or points out the next one. */
    public void done(Player player, Step step) {
        Profile profile = plugin.profiles().get(player);
        if (!active(profile) || isDone(profile, step)) {
            return;
        }
        profile.tutorialMask |= step.bit();
        plugin.profiles().markDirty(player.getUniqueId());
        if ((profile.tutorialMask & ALL) == ALL) {
            finish(player, profile);
            return;
        }
        player.playSound(player.getLocation(), "craftov:ui.queue", org.bukkit.SoundCategory.MASTER, 0.7f, 1.3f);
        if (profile.guideMuted || step == Step.EXTRACT) {
            // After an extract the lobby, a moment later, points out what is left (LobbyService#enterLobby).
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

    /** The player turned the guide's pointers off (the briefing's skip) or back on (its start button). */
    public void mute(Player player, boolean muted) {
        Profile profile = plugin.profiles().get(player);
        profile.guideMuted = muted;
        profile.briefed = true;
        plugin.profiles().markDirty(player.getUniqueId());
    }

    // ------------------------------------------------------------------ briefing

    /** The briefing from its first page (the terminal's guide button, /cv guide). */
    public void openBriefing(Player player) {
        openBriefing(player, 0);
    }

    /** The briefing at a page: 0 the goal, 1 the rules, 2 the controls. */
    public void openBriefing(Player player, int page) {
        Briefing.open(plugin, player, page);
    }

    /**
     * Joining: a new player who has not answered the briefing yet gets it once they stand in the
     * lobby (a moment after joining, so it comes after the resource pack and the welcome line).
     */
    public void briefOnJoin(Player player) {
        Profile profile = plugin.profiles().get(player);
        if (!active(profile) || profile.briefed || !plugin.getConfig().getBoolean("tutorial.briefing", true)) {
            return;
        }
        java.util.UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && plugin.lobby().isLobby(p.getWorld()) && plugin.raids().raidOf(p) == null
                    && !plugin.profiles().get(p).briefed) {
                openBriefing(p);
            }
        }, 60L);
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
     * Every 45 seconds, the step on the action bar, in the lobby and on the range only. In a raid it
     * would cover the short messages that matter there (a kill, an injury, a med used), and the
     * objective line already points at the nearest extract; someone dead and watching a squadmate
     * is still in the raid's world. The matchmaking queue keeps the action bar for its countdown.
     */
    private void remind() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Profile profile = plugin.profiles().get(player);
            if (!guided(profile) || plugin.raids().raidOf(player) != null || plugin.raids().raidIn(player.getWorld()) != null
                    || plugin.match().queued(player.getUniqueId()) != null
                    || !plugin.lobby().isLobby(player.getWorld()) && !plugin.range().isIn(player)) {
                continue;
            }
            Step step = current(profile);
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
        Raid raid = plugin.raids().raidOf(player);
        if (raid != null && raid.training()) {
            // The training raid has its own lessons, guide or no guide (TrainingService).
            return plugin.training().objective(player);
        }
        Profile profile = plugin.profiles().get(player);
        if (!guided(profile)) {
            return null;
        }
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
