package net.infnetwork.snowball.craftov.raid;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.combat.BodyPart;
import net.infnetwork.snowball.craftov.combat.Injury;
import net.infnetwork.snowball.craftov.gui.Style;
import net.infnetwork.snowball.craftov.gui.UiItems;
import net.infnetwork.snowball.craftov.gui.UiSound;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.lobby.TutorialService;
import net.infnetwork.snowball.craftov.map.MapDef;
import net.infnetwork.snowball.craftov.map.gen.TrainingGroundGenerator;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The training raid (训练行动): a short course on the training ground (TrainingGroundGenerator) that
 * takes a new player through a raid alone, with nothing at stake. It is a real raid on the real
 * HUD, entered from the deploy screen while the guide runs (or with /cv training), and each room's
 * gate opens once its lesson is done:
 * <ol>
 *   <li>search the supply crate and take what it holds: rounds, bandages and a splint;</li>
 *   <li>aim down the sight and hit each of the three targets once, then reload;</li>
 *   <li>a tripwire breaks a leg and opens a bleed: stop the bleed with a bandage, set the leg
 *       with the splint;</li>
 *   <li>pull the lever and wait in the extract until its countdown is over.</li>
 * </ol>
 * The course hands out its own kit in place of the player's gear, which waits in the deployment
 * record and comes back whichever way the raid ends (RaidManager#endTraining): nothing found or
 * used in it is kept, nothing counts in the stats, and finishing it counts as the guide's deploy
 * and extract steps.
 */
public final class TrainingService {

    /** The training raid's difficulty: no fee, no loot shift, no experience. */
    public static final Tier TIER = new Tier("TRAINING", "训练", Style.MUTED, 0, 0, 0, 0.5, 0, false, 0, 0);

    /** The course's lessons in order; the first one not done is the objective. */
    public enum Lesson {
        LOOT("搜索物资箱", "搜箱拿齐物资", "icon_stash"),
        AIM("开镜打靶", "开镜打靶", "icon_deploy"),
        RELOAD("换弹", "换一次弹匣", "icon_ammo"),
        HEAL("处理伤势", "止血并固定骨折", "tab_medical"),
        LEVER("拉下电闸", "拉下电闸", "icon_start"),
        EXTRACT("撤离", "站进撤离点", "waypoint");

        /** The settlement's checklist line. */
        public final String title;
        /** The settlement's icon for it (craftov:ui/...). */
        public final String icon;
        /**
         * The HUD objective line: plain text. In a raid it sits on the 132 px panel under the squad
         * rows, after "目标 " and before " · 99m" or " · 到了" (HudData#objective): with a target
         * at most 6 Chinese characters, or the 0/3 count's room after four. The kill feed reaches
         * in from the right on a 320 px wide GUI, so the panel cannot grow.
         */
        public final String objective;

        Lesson(String title, String objective, String icon) {
            this.title = title;
            this.objective = objective;
            this.icon = icon;
        }
    }

    private static final org.bukkit.NamespacedKey TARGET_INDEX = Keys.key("training_target");
    /** The kit's rifle and the rounds in its magazine: few enough that the reload lesson is never refused. */
    private static final String GUN = "k47";
    private static final String OPTIC = "optic_reddot";
    private static final String AMMO = "ammo_762_ps";
    private static final int LOADED = 8;
    /** Health is kept at least this high on the course (of 100). */
    private static final double MIN_HEALTH = 20;

    /** One player's way through the course. */
    private static final class Session {
        final Raid raid;
        final Set<Integer> hits = new HashSet<>();
        boolean looted;
        boolean reloaded;
        boolean trapped;
        boolean healed;
        /** The lever's countdown is running (or just ran out and is being dealt with). */
        boolean lever;
        /** The countdown ran out with the player outside the extract: the lever is to pull again. */
        boolean leverMissed;
        /** The lesson whose hint was shown last, and when it is due again. */
        @Nullable Lesson hinted;
        long hintAt;

        Session(Raid raid) {
            this.raid = raid;
        }

        @Nullable Lesson current() {
            if (!looted) {
                return Lesson.LOOT;
            }
            if (hits.size() < TrainingGroundGenerator.TARGETS.length) {
                return Lesson.AIM;
            }
            if (!reloaded) {
                return Lesson.RELOAD;
            }
            if (!trapped || !healed) {
                return Lesson.HEAL;
            }
            return lever ? Lesson.EXTRACT : Lesson.LEVER;
        }

        boolean done(Lesson lesson) {
            Lesson now = current();
            return now == null || lesson.ordinal() < now.ordinal();
        }
    }

    private final CraftovPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    /**
     * When each player last started a course (server tick). Every start copies and loads a world on
     * the main thread, and giving up ends the course at once: without a wait between starts one
     * player could keep the server loading worlds.
     */
    private final Map<UUID, Long> lastStart = new HashMap<>();

    public TrainingService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 5L);
    }

    // ------------------------------------------------------------------ entering

    /**
     * The training map, or null while there is none to play: not generated (or its file is broken),
     * or an editor draft that nobody has published.
     */
    public @Nullable MapDef map() {
        MapDef map = plugin.maps().get(TrainingGroundGenerator.ID);
        return map == null || !map.enabled() ? null : map;
    }

    /** Whether the deploy screen offers the training raid to this player: while their guide runs. */
    public boolean offered(Player player) {
        return map() != null && plugin.getConfig().getBoolean("training.enabled", true)
                && plugin.tutorial().active(plugin.profiles().get(player));
    }

    /** Starts a training raid for the player alone. Null on success, otherwise why not. */
    public @Nullable String start(Player player) {
        MapDef map = map();
        if (map == null || !plugin.getConfig().getBoolean("training.enabled", true)) {
            return "训练场暂时不可用";
        }
        if (plugin.editor().underMaintenance(map)) {
            // A raid copies the template folder, which must not be copied while it is being edited.
            return "训练场正在维护（地图编辑中），稍后再试";
        }
        if (plugin.raids().raidOf(player) != null) {
            return "你已经在行动中";
        }
        if (plugin.raids().launching(player.getUniqueId())) {
            // Matched (or a training course started) and waiting for the map: a second launch would
            // take the player from the first one without a word to their squad.
            return "已经匹配成功，正在部署行动";
        }
        if (plugin.range().isIn(player)) {
            return "请先离开靶场";
        }
        if (!plugin.lobby().isLobby(player.getWorld())) {
            return "请先回到大厅";
        }
        long now = Bukkit.getCurrentTick();
        Long last = lastStart.get(player.getUniqueId());
        long wait = last == null ? 0 : last + 20L * plugin.getConfig().getInt("training.cooldown-seconds", 30) - now;
        if (wait > 0) {
            return "训练场正在重置，" + (wait + 19) / 20 + " 秒后再开始";
        }
        lastStart.put(player.getUniqueId(), now);
        plugin.match().cancelQueue(player.getUniqueId(), "开始训练行动");
        player.closeInventory();
        plugin.raids().start(map, TIER, List.of(List.of(player)), Set.of());
        return null;
    }

    // ------------------------------------------------------------------ raid hooks

    /**
     * The raid's world is ready (RaidManager#deploy): the crate gets its fixed contents and the
     * targets go up. A session begins for its player.
     */
    void setUp(Raid raid) {
        for (LootContainer container : raid.containers.values()) {
            List<ItemStack> items = new ArrayList<>();
            addItem(items, AMMO, 60);
            addItem(items, "bandage", 2);
            addItem(items, "splint", 1);
            container.fill(items, false);
        }
        int[][] targets = TrainingGroundGenerator.TARGETS;
        for (int i = 0; i < targets.length; i++) {
            int index = i;
            int meters = (index + 1) * 10;
            Location at = new Location(raid.world, targets[i][0] + 0.5, TrainingGroundGenerator.G + 1, targets[i][1] + 0.5, 0, 0);
            // No random spawn data: a zombie spawned with it can come with random gear, a pumpkin
            // head or (1 in 400) riding a chicken that walks the target around.
            raid.world.spawn(at, Zombie.class, false, z -> {
                z.setAI(false);
                z.setAdult();
                z.setSilent(true);
                z.setPersistent(false);
                // A zombie is a monster the server despawns at random (1 in 800 a tick) once it has
                // been more than 32 blocks from every player for about 15 to 30 seconds. The 30 m
                // target is 33 blocks from the firing line and 47 from the crate, so it often
                // vanished before the player got to it, and the lesson could never be finished.
                z.setRemoveWhenFarAway(false);
                z.setShouldBurnInDay(false);
                z.getPersistentDataContainer().set(Keys.key("target"), PersistentDataType.BYTE, (byte) 1);
                z.getPersistentDataContainer().set(TARGET_INDEX, PersistentDataType.INTEGER, index);
                z.customName(Text.mm("<accent>靶子 " + meters + "m"));
                z.setCustomNameVisible(true);
            });
        }
        for (UUID uuid : raid.players.keySet()) {
            sessions.put(uuid, new Session(raid));
        }
    }

    private void addItem(List<ItemStack> into, String id, int amount) {
        ItemStack stack = plugin.items().create(id, amount);
        if (stack != null) {
            into.add(stack);
        }
    }

    /**
     * The course's kit in place of the player's gear (RaidManager#prepare, after the gear is on
     * record): a rifle with a red dot and a few rounds in it, a chest rig to carry the crate's
     * rounds, the knife and the raid terminal.
     */
    void equip(Player player) {
        player.getInventory().clear();
        player.setItemOnCursor(null);
        ItemRecord gun = new ItemRecord(GUN, 1);
        gun.uid = UUID.randomUUID().toString();
        gun.ammo = LOADED;
        gun.ammoType = AMMO;
        gun.optic = OPTIC;
        ItemStack rifle = plugin.items().fromRecord(gun);
        if (rifle != null) {
            player.getInventory().setItem(0, rifle);
        }
        ItemStack rig = plugin.items().create("rig_1", 1);
        if (rig != null) {
            player.getInventory().setItem(CarryService.LEGS, rig);
        }
        ItemStack knife = plugin.items().create("knife_starter", 1);
        if (knife != null) {
            player.getInventory().setItem(CarryService.MELEE_SLOT, knife);
        }
        player.getInventory().setItem(CarryService.TERMINAL_SLOT, UiItems.raidTerminal());
        player.getInventory().setHeldItemSlot(0);
        plugin.carry().refreshLocks(player);
    }

    /** On the ground: what the course is and what to do first. */
    void landed(Player player) {
        player.showTitle(Title.title(Text.mm("<accent><b>训练行动"), Text.mm("<muted>跟着左上角的目标一步步来"),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2200), Duration.ofMillis(500))));
        Text.send(player, "<accent><b>训练行动</b> <muted>· 单人 · 不计战绩。你的装备已替你保管，结束时原样归还；"
                + "这里的枪、弹药和药品都是借的，结束时收回。左上角的目标和罗盘上的琥珀色 ◆ 告诉你下一步去哪。");
    }

    /**
     * The raid is over for the player ({@code extracted}: through the extract), or they left: the
     * session ends. Returns the settlement's checklist: whether each lesson was done, in order.
     */
    List<Boolean> finish(Player player, boolean extracted) {
        Session session = sessions.remove(player.getUniqueId());
        List<Boolean> done = new ArrayList<>();
        for (Lesson lesson : Lesson.values()) {
            done.add(lesson == Lesson.EXTRACT ? extracted : session != null && (extracted || session.done(lesson)));
        }
        return done;
    }

    /**
     * The course was finished: it counts as the guide's deploy and extract steps. The extract goes
     * first, quietly, so the deploy's line names the step really left (or pays the bonus).
     */
    void completed(Player player) {
        plugin.tutorial().done(player, TutorialService.Step.EXTRACT);
        plugin.tutorial().done(player, TutorialService.Step.DEPLOY);
    }

    /** A target was hit (Ballistics). Counted while aiming down the sight, each target once. */
    public void onTargetHit(Player shooter, LivingEntity target) {
        Session session = sessions.get(shooter.getUniqueId());
        Integer index = target.getPersistentDataContainer().get(TARGET_INDEX, PersistentDataType.INTEGER);
        if (session == null || index == null || session.current() != Lesson.AIM) {
            return;
        }
        if (!plugin.weapons().isAiming(shooter)) {
            // The answer to a shot, so a result: a hint would wait behind (and be replaced by) the
            // lesson's own reminder.
            Notify.info(shooter, "<accent>没开镜，不算 <muted>· 要先按住 <white><key:key.use></white> 开镜，再打靶子");
            return;
        }
        if (!session.hits.add(index)) {
            return;
        }
        target.customName(Text.mm("<ok>✔ 命中"));
        shooter.playSound(shooter.getLocation(), "minecraft:entity.experience_orb.pickup", SoundCategory.MASTER, 0.8f, 1.4f);
        int total = TrainingGroundGenerator.TARGETS.length;
        if (session.hits.size() < total) {
            Notify.ok(shooter, "命中 " + session.hits.size() + "/" + total);
        } else {
            Notify.ok(shooter, "三个靶子都打中了" + (session.reloaded ? "" : " <muted>· 下一步：换弹"));
            session.hinted = null;
        }
    }

    /** A reload went through (WeaponService). Counts once the crate's rounds are in hand. */
    public void onReload(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.looted || session.reloaded) {
            return;
        }
        session.reloaded = true;
        session.hinted = null;
        Notify.ok(player, session.hits.size() < TrainingGroundGenerator.TARGETS.length ? "换弹完成 <muted>· 接着开镜打靶" : "换弹完成");
    }

    // ------------------------------------------------------------------ objective

    /** The HUD objective for a player in a training raid, or null outside one. */
    public TutorialService.@Nullable Objective objective(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !plugin.raids().isInRaid(player)) {
            return null;
        }
        Lesson lesson = session.current();
        if (lesson == null) {
            return null;
        }
        World world = session.raid.world;
        int g = TrainingGroundGenerator.G;
        Location target = switch (lesson) {
            case LOOT -> new Location(world, TrainingGroundGenerator.CRATE_X + 0.5, g + 1, TrainingGroundGenerator.CRATE_Z + 0.5);
            case AIM -> nextTarget(session, world);
            case RELOAD -> null;
            case HEAL -> session.trapped ? null : new Location(world, TrainingGroundGenerator.TRAP_X + 0.5, g + 1, 10.5);
            case LEVER -> new Location(world, TrainingGroundGenerator.LEVER_X + 0.5, g + 1, TrainingGroundGenerator.LEVER_Z + 0.5);
            case EXTRACT -> new Location(world, TrainingGroundGenerator.EXTRACT_X + 0.5, g + 1, TrainingGroundGenerator.EXTRACT_Z + 0.5);
        };
        String text = lesson == Lesson.AIM ? lesson.objective + " " + session.hits.size() + "/" + TrainingGroundGenerator.TARGETS.length
                : lesson == Lesson.HEAL && !session.trapped ? "穿过走廊"
                : lesson == Lesson.LEVER && session.leverMissed ? "再拉一次电闸" : lesson.objective;
        return new TutorialService.Objective(text, target);
    }

    private static @Nullable Location nextTarget(Session session, World world) {
        int[][] targets = TrainingGroundGenerator.TARGETS;
        for (int i = 0; i < targets.length; i++) {
            if (!session.hits.contains(i)) {
                return new Location(world, targets[i][0] + 0.5, TrainingGroundGenerator.G + 1, targets[i][1] + 0.5);
            }
        }
        return null;
    }

    /** The action-bar hint for a lesson, with the player's own keys. */
    private String hint(Player player, Session session, Lesson lesson) {
        return switch (lesson) {
            case LOOT -> "右键物资箱搜索，物品出现后点击拿走：弹药、绷带和夹板都要拿";
            case AIM -> "按住 <white><key:key.use></white> 开镜，<white><key:key.attack></white> 开火：三个靶子各打中一次";
            case RELOAD -> "按 <white><key:key.drop></white> 换弹（弹药要在身上）";
            case HEAL -> !session.trapped ? "穿过东边的走廊"
                    : plugin.health().hasInjury(player, Injury.LIGHT_BLEED) || plugin.health().hasInjury(player, Injury.HEAVY_BLEED)
                    ? "先止血：选中绷带，按住 <white><key:key.use></white> 直到用完"
                    : "再固定骨折：选中夹板，按住 <white><key:key.use></white> 直到用完";
            case LEVER -> session.leverMissed ? "再右键一次电闸，然后站在绿色撤离点里等倒计时结束" : "到撤离区右键电闸";
            case EXTRACT -> "站进绿色撤离点，倒计时结束就撤离";
        };
    }

    // ------------------------------------------------------------------ the course

    private void tick() {
        long now = Bukkit.getCurrentTick();
        for (Map.Entry<UUID, Session> entry : new ArrayList<>(sessions.entrySet())) {
            Session session = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (session.raid.state == Raid.State.CLOSED) {
                sessions.remove(entry.getKey());
                continue;
            }
            if (player == null || !plugin.raids().isInRaid(player) || player.getWorld() != session.raid.world) {
                continue;
            }
            if (player.getHealth() < MIN_HEALTH) {
                // Nobody dies in training: the bleed lesson must not end the course for a slow learner.
                player.setHealth(MIN_HEALTH);
            }
            if (!session.looted && carriesKit(player)) {
                session.looted = true;
                session.hinted = null;
                open(session.raid, TrainingGroundGenerator.GATE_LOOT);
                Notify.ok(player, "物资拿齐了 <muted>· 东边的门开了：去靶场");
            }
            if (session.looted && session.hits.size() >= TrainingGroundGenerator.TARGETS.length && session.reloaded
                    && !gateOpen(session.raid, TrainingGroundGenerator.GATE_RANGE)) {
                open(session.raid, TrainingGroundGenerator.GATE_RANGE);
                Notify.ok(player, "靶场这一课完成 <muted>· 东边的门开了：穿过走廊");
            }
            if (gateOpen(session.raid, TrainingGroundGenerator.GATE_RANGE) && !session.trapped
                    && player.getX() >= TrainingGroundGenerator.TRAP_X && player.getX() < TrainingGroundGenerator.GATE_HEAL) {
                trip(session, player);
            }
            if (session.trapped && !session.healed && !plugin.health().hasInjury(player, Injury.LIGHT_BLEED)
                    && !plugin.health().hasInjury(player, Injury.HEAVY_BLEED) && !plugin.health().hasInjury(player, Injury.FRACTURE)) {
                session.healed = true;
                session.hinted = null;
                open(session.raid, TrainingGroundGenerator.GATE_HEAL);
                Notify.ok(player, "伤势处理好了 <muted>· 门开了：去撤离区");
            }
            // Worked out again every time: a countdown that runs out with the player outside the
            // extract closes it again (RaidManager#extractBlocker), and the lesson goes back to the lever.
            boolean pulled = session.raid.switchActive(TrainingGroundGenerator.SWITCH)
                    || session.raid.departing.contains(TrainingGroundGenerator.SWITCH);
            if (pulled != session.lever) {
                session.leverMissed = session.lever;
                session.lever = pulled;
                session.hinted = null;
                if (session.leverMissed) {
                    // Said at once, as what became of the countdown: a hint would wait behind the
                    // extract's reminder, which comes due as the countdown ends. The lever's own hint
                    // follows on the usual beat.
                    Notify.info(player, "<accent>倒计时结束时你不在撤离点 <muted>· 撤离点又关了：再拉一次电闸");
                    session.hinted = Lesson.LEVER;
                    session.hintAt = now + 20L * 10;
                }
            }
            Lesson lesson = session.current();
            if (lesson != null && (lesson != session.hinted || now >= session.hintAt)) {
                session.hinted = lesson;
                session.hintAt = now + 20L * 10;
                Notify.hint(player, hint(player, session, lesson));
            }
        }
    }

    /** Rounds for the kit's rifle, a bandage and a splint in the player's own cells (the crate's contents). */
    private boolean carriesKit(Player player) {
        boolean ammo = false;
        boolean bandage = false;
        boolean splint = false;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            ItemDef def = plugin.items().defOf(stack);
            if (def == null) {
                continue;
            }
            ammo |= def.id().equals(AMMO);
            bandage |= def.id().equals("bandage");
            splint |= def.id().equals("splint");
        }
        return ammo && bandage && splint;
    }

    /** The tripwire: a blast at the player's feet, a broken leg and a bleed, and the heal lesson begins. */
    private void trip(Session session, Player player) {
        session.trapped = true;
        session.hinted = null;
        Location at = player.getLocation();
        player.getWorld().spawnParticle(Particle.EXPLOSION, at, 1);
        player.getWorld().playSound(at, "minecraft:entity.generic.explode", SoundCategory.MASTER, 1f, 1.3f);
        plugin.health().setHealthShown(player, Math.max(1, player.getHealth() - 18));
        plugin.health().addInjury(player, BodyPart.LEFT_LEG, Injury.FRACTURE);
        plugin.health().addInjury(player, BodyPart.LEFT_LEG, Injury.LIGHT_BLEED);
        UiSound.WARNING.play(player);
        player.showTitle(Title.title(Text.mm("<danger><b>⚠ 绊雷"), Text.mm("<text>左腿骨折、出血 <muted>· 用绷带止血，用夹板固定"),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2500), Duration.ofMillis(500))));
    }

    private static boolean gateOpen(Raid raid, int x) {
        return raid.world.getBlockAt(x, TrainingGroundGenerator.G + 2, TrainingGroundGenerator.GATE_Z0 + 1).getType() != Material.IRON_BARS;
    }

    /** Takes a gate's bars away, with a sound and some dust where they were. */
    private static void open(Raid raid, int x) {
        int g = TrainingGroundGenerator.G;
        for (int y = g + 1; y <= g + 3; y++) {
            for (int z = TrainingGroundGenerator.GATE_Z0; z <= TrainingGroundGenerator.GATE_Z1; z++) {
                raid.world.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
        }
        Location centre = new Location(raid.world, x + 0.5, g + 2, TrainingGroundGenerator.GATE_Z0 + 1.5);
        raid.world.playSound(centre, "minecraft:block.iron_door.open", SoundCategory.BLOCKS, 1.5f, 0.8f);
        raid.world.spawnParticle(Particle.CLOUD, centre, 12, 0.4, 0.8, 1.0, 0.02);
    }
}
