package net.infnetwork.snowball.craftov.ai;

import com.destroystokyo.paper.entity.Pathfinder;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.combat.DamageService;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.item.Rarity;
import net.infnetwork.snowball.craftov.loot.ContainerType;
import net.infnetwork.snowball.craftov.map.MapDef;
import net.infnetwork.snowball.craftov.raid.LootContainer;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import net.infnetwork.snowball.craftov.weapon.AmmoSpec;
import net.infnetwork.snowball.craftov.weapon.Ballistics;
import net.infnetwork.snowball.craftov.weapon.GunSpec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Hostile soldiers: patrol, notice players, take aim and fire controlled bursts. */
public final class AiService implements Listener {

    private static final String[] NAMES = {"港区巡逻兵", "雇佣兵", "守备队员", "武装分子"};

    /** How long a soldier holds at the end of a path that cannot reach its goal before trying again. */
    private static final long HOLD_TICKS = 20L * 4;
    /** Blocks of smoke along a sight line that hide what is behind it. */
    private static final double SMOKE_OPAQUE = 1.5;

    private final CraftovPlugin plugin;
    private final Map<UUID, Bot> bots = new HashMap<>();
    private final AiDoors doors;
    /** Smoke clouds soldiers cannot see through, in every raid world. */
    private final List<Smoke> smokes = new ArrayList<>();

    private record Smoke(World world, Vector centre, double radius, double height, long from, long until) {
    }

    /**
     * What a soldier saw of a player on one look: how much of the sight lines to head, chest and
     * pelvis got through (see {@link Sight}), and along which of them a round would get there.
     */
    private record Look(double[] seen, boolean[] open) {
        double shown() {
            return (seen[0] + seen[1] + seen[2]) / 3;
        }
    }

    static final class Bot {
        final Zombie mob;
        final Raid raid;
        Location home;
        final double leash;
        final String kind;
        final ItemDef gun;
        final @Nullable ItemDef ammo;
        final String name;
        final boolean boss;
        final double accuracy;
        int ammoLeft;
        @Nullable UUID target;
        long firstSeenTick = -1;
        long lastSeenTick = -1000;
        /** When the current target was acquired; aim settles over the first seconds of an engagement. */
        long engagedSince = -1000;
        @Nullable Location lastSeen;
        int burstLeft;
        long nextShotTick;
        long reloadUntil;
        @Nullable Location alertPos;
        long alertUntil;
        long nextWanderTick;
        /** Blinded by a strobe or a flashbang: still fights, but shoots later and much wider until then. */
        long dazzledUntil;
        /** After a dazzle wears off the soldier has its eyes back for a while and a strobe cannot blind it again. */
        long dazzleImmuneUntil;
        /** Rival operators: what they have looted (dropped on death), the container they are on, squad leader. */
        final List<ItemStack> carried = new ArrayList<>();
        final java.util.Set<String> visited = new java.util.HashSet<>();
        @Nullable LootContainer looting;
        long lootUntil;
        @Nullable UUID leader;
        /** Its path could not reach the goal (a locked door, a wall): it waits where it got to until then. */
        long holdUntil;
        /** How close it is to having spotted each player it might have noticed, 0..1 (see acquire). */
        final Map<UUID, Double> awareness = new HashMap<>();
        /** The last player it fought (seen at lastSeenTick); unlike target it is not let go after 2 s. */
        @Nullable UUID lastFought;
        /** What it saw of the current target's head, chest and pelvis on the last look, or null. */
        @Nullable Look targetLook;
        /** A sidestep is under way until then: no new one, and it does not stop to shoot before it. */
        long nextMoveTick;
        /** Roughly where a shot from someone it had not spotted came from, who fired it, and until when it reacts. */
        @Nullable Location threatPos;
        @Nullable UUID threatFrom;
        long threatUntil;
        /** Cover from that shot it is heading for or sitting in; it moves on at coverUntil (0: not there yet). */
        @Nullable Location coverPos;
        long coverUntil;
        long nextCoverSearch;
        /** A boss or boss guard asleep at its post until its time (see spawnBoss), and the boss it belongs to. */
        boolean dormant;
        @Nullable String group;

        Bot(Zombie mob, Raid raid, Location home, double leash, String kind, ItemDef gun, @Nullable ItemDef ammo,
            String name, boolean boss, double accuracy) {
            this.mob = mob;
            this.raid = raid;
            this.home = home;
            this.leash = leash;
            this.kind = kind;
            this.gun = gun;
            this.ammo = ammo;
            this.name = name;
            this.boss = boss;
            this.accuracy = accuracy;
            this.ammoLeft = gun.gun().magazine();
        }
    }

    public AiService(CraftovPlugin plugin) {
        this.plugin = plugin;
        this.doors = new AiDoors(plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 7L, 2L);
    }

    public @Nullable String nameOf(Entity entity) {
        Bot bot = bots.get(entity.getUniqueId());
        return bot == null ? null : bot.name;
    }

    /** Moves a raid's boss and its guards to a new post; returns the boss's name, or null if none is alive. */
    public @Nullable String relocateBoss(Raid raid, Location to) {
        Bot boss = null;
        for (Bot bot : bots.values()) {
            if (bot.raid == raid && bot.boss && bot.mob.isValid() && !bot.dormant) {
                boss = bot;
            }
        }
        if (boss == null) {
            return null;
        }
        Location from = boss.home.clone();
        for (Bot bot : bots.values()) {
            if (bot.raid == raid && bot.mob.isValid() && (bot == boss || "guard".equals(bot.kind)
                    && bot.home.getWorld() == from.getWorld() && bot.home.distanceSquared(from) < 30 * 30)) {
                Location post = to.clone().add(bot.home.getX() - from.getX(), 0, bot.home.getZ() - from.getZ());
                bot.home = post;
                bot.alertPos = post;
                bot.alertUntil = Bukkit.getCurrentTick() + 20L * 90;
            }
        }
        return boss.name;
    }

    /** A patrol of {@code count} soldiers appears at {@code at} and heads for {@code toward}. */
    public int reinforce(Raid raid, Location at, Location toward, int count) {
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            Location spot = at.clone().add(ThreadLocalRandom.current().nextDouble(-2, 2), 0, ThreadLocalRandom.current().nextDouble(-2, 2));
            Bot bot = spawn(raid, spot, 14, "patrol", Math.min(3, raid.tier.aiGear() + 1), false, "增援士兵");
            if (bot != null) {
                bot.alertPos = toward.clone();
                bot.alertUntil = Bukkit.getCurrentTick() + 20L * 90;
                bot.home = toward.clone();
                spawned++;
            }
        }
        return spawned;
    }

    /** Two shield soldiers at {@code at}, heading for {@code toward} (testing and future events). */
    public int shieldTeam(Raid raid, Location at, @Nullable Location toward) {
        int spawned = 0;
        for (int i = 0; i < 2; i++) {
            Bot bot = spawn(raid, at.clone().add(i * 1.5, 0, 0), 10, "shield", Math.max(1, raid.tier.aiGear()), false, "持盾突击手");
            if (bot != null && toward != null) {
                bot.alertPos = toward.clone();
                bot.alertUntil = Bukkit.getCurrentTick() + 20L * 60;
            }
            spawned += bot == null ? 0 : 1;
        }
        return spawned;
    }

    /** Living soldiers of a raid, for the kill replay recorder. */
    public List<Zombie> mobsIn(Raid raid) {
        List<Zombie> out = new ArrayList<>();
        for (Bot bot : bots.values()) {
            if (bot.raid == raid && bot.mob.isValid()) {
                out.add(bot.mob);
            }
        }
        return out;
    }

    /** The gun an AI soldier carries, or null if the entity is not one. */
    public @Nullable ItemDef gunOf(@Nullable Entity entity) {
        Bot bot = entity == null ? null : bots.get(entity.getUniqueId());
        return bot == null ? null : bot.gun;
    }

    /** True if a shield soldier stops this round: it comes from the front and hits the head, torso or arms. */
    public boolean shieldBlocks(LivingEntity victim, @Nullable LivingEntity shooter, net.infnetwork.snowball.craftov.combat.BodyPart part) {
        Bot bot = bots.get(victim.getUniqueId());
        if (bot == null || !"shield".equals(bot.kind) || shooter == null || part == net.infnetwork.snowball.craftov.combat.BodyPart.LEFT_LEG
                || part == net.infnetwork.snowball.craftov.combat.BodyPart.RIGHT_LEG) {
            return false;
        }
        Vector facing = victim.getLocation().getDirection().setY(0);
        Vector toShooter = shooter.getLocation().toVector().subtract(victim.getLocation().toVector()).setY(0);
        if (facing.lengthSquared() < 1e-4 || toShooter.lengthSquared() < 1e-4) {
            return false;
        }
        return facing.normalize().dot(toShooter.normalize()) > 0.34;
    }

    public boolean isAi(@Nullable Entity entity) {
        return entity != null && bots.containsKey(entity.getUniqueId());
    }

    /**
     * One line per soldier of a raid for /cv ai: name, kind, position and facing, what it is doing,
     * whom it is fighting and how aware it is of each player it has half noticed, and its UUID.
     */
    public List<String> describe(Raid raid) {
        long now = Bukkit.getCurrentTick();
        List<String> out = new ArrayList<>();
        for (Bot bot : bots.values()) {
            if (bot.raid != raid || !bot.mob.isValid()) {
                continue;
            }
            Location at = bot.mob.getLocation();
            StringBuilder line = new StringBuilder(String.format(java.util.Locale.ROOT, "%s %s %.1f %.1f %.1f 朝向 %.0f %s",
                    bot.name, bot.kind, at.getX(), at.getY(), at.getZ(), at.getYaw(), state(bot, now)));
            Player target = bot.target == null ? null : Bukkit.getPlayer(bot.target);
            if (target != null && now - bot.lastSeenTick < 40) {
                line.append(" 目标 ").append(target.getName());
            }
            if (!bot.awareness.isEmpty()) {
                line.append(" 察觉");
                bot.awareness.forEach((id, value) -> {
                    Player p = Bukkit.getPlayer(id);
                    line.append(' ').append(p == null ? id.toString().substring(0, 8) : p.getName())
                            .append('=').append(String.format(java.util.Locale.ROOT, "%.2f", value));
                });
            }
            out.add(line.append(' ').append(bot.mob.getUniqueId()).toString());
        }
        return out;
    }

    /** What a soldier is busy with, as /cv ai shows it. */
    private static String state(Bot bot, long now) {
        if (bot.dormant) {
            return "休眠";
        }
        if (bot.target != null && now - bot.lastSeenTick < 40) {
            return "交战";
        }
        if (bot.threatPos != null && now < bot.threatUntil) {
            return bot.coverPos != null && bot.mob.getLocation().distanceSquared(bot.coverPos) < 1.2 * 1.2 ? "掩护" : "受击";
        }
        if (bot.lastSeen != null && now - bot.lastSeenTick < 20L * 10) {
            return "追击";
        }
        if (now < bot.holdUntil) {
            return "守候";
        }
        if (bot.alertPos != null && now < bot.alertUntil) {
            return "警戒";
        }
        if (bot.looting != null) {
            return "搜刮";
        }
        return "巡逻";
    }

    /**
     * Puts one soldier of {@code kind} (patrol, guard, sniper, shield or operator) at {@code at}, posted
     * there, for testing (/cv ai spawn). Returns its name, or null if none could be made.
     */
    public @Nullable String spawnSoldier(Raid raid, Location at, String kind) {
        String name = switch (kind) {
            case "shield" -> "持盾突击手";
            case "operator" -> "敌对干员 · 测试";
            default -> NAMES[ThreadLocalRandom.current().nextInt(NAMES.length)];
        };
        int gear = "operator".equals(kind) ? Math.min(3, raid.tier.aiGear() + 1) : raid.tier.aiGear();
        double leash = "guard".equals(kind) ? 4 : "patrol".equals(kind) ? 6 : 3;
        Bot bot = spawn(raid, at, leash, kind, gear, false, name);
        return bot == null ? null : bot.name;
    }

    public int count(Raid raid) {
        int n = 0;
        for (Bot bot : bots.values()) {
            if (bot.raid == raid) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ spawning

    public void populate(Raid raid) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double mult = raid.tier.aiCount() * plugin.getConfig().getDouble("ai.count-multiplier", 1.0);
        for (MapDef.AiDef def : raid.map.ai()) {
            double expected = def.count() * mult;
            int count = (int) Math.floor(expected) + (rng.nextDouble() < expected - Math.floor(expected) ? 1 : 0);
            for (int i = 0; i < count; i++) {
                Location at = def.pos().at(raid.world);
                if (i > 0) {
                    at.add(rng.nextDouble(-1.5, 1.5), 0, rng.nextDouble(-1.5, 1.5));
                    if (!at.getBlock().isPassable()) {
                        at = def.pos().at(raid.world);
                    }
                }
                // From 机密 up some guards carry a ballistic shield.
                String kind = "guard".equals(def.kind()) && raid.tier.aiGear() >= 1 && rng.nextDouble() < 0.3 ? "shield" : def.kind();
                spawn(raid, at, def.radius(), kind, raid.tier.aiGear(), false, "shield".equals(kind) ? "持盾突击手" : NAMES[rng.nextInt(NAMES.length)]);
            }
        }
        List<Double> chances = plugin.getConfig().getDoubleList("ai.operator-chance");
        double chance = chances.isEmpty() ? 0.3 : chances.get(Math.min(chances.size() - 1, raid.tier.aiGear()));
        if (!raid.map.spawns().isEmpty() && rng.nextDouble() < chance) {
            operatorsLater(raid, 20L * plugin.getConfig().getInt("ai.operator-delay-seconds", 60), 4);
        }
    }

    /**
     * A rival operator squad lands on a player spawn point in {@code delay} ticks: one with no player
     * within 50 blocks (it used to pick any, right on top of a squad that had just landed there).
     * With none clear it tries again 15 s later, {@code tries} times in all.
     */
    private void operatorsLater(Raid raid, long delay, int tries) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (raid.state != Raid.State.RUNNING) {
                return;
            }
            List<Location> clear = new ArrayList<>();
            for (MapDef.SpawnDef spawn : raid.map.spawns()) {
                Location at = spawn.pos().at(raid.world);
                if (raid.online().stream().noneMatch(p -> p.getLocation().distanceSquared(at) < 50 * 50)) {
                    clear.add(at);
                }
            }
            if (!clear.isEmpty()) {
                operatorSquad(raid, clear.get(ThreadLocalRandom.current().nextInt(clear.size())));
            } else if (tries > 1) {
                operatorsLater(raid, 20L * 15, tries - 1);
            }
        }, delay);
    }

    /** Idle rival operator: follow the squad leader, or walk to the next container and loot it. */
    private void operate(Bot bot, long now) {
        Zombie mob = bot.mob;
        if (bot.leader != null) {
            Bot leader = bots.get(bot.leader);
            if (leader != null && leader.mob.isValid()) {
                if (mob.getLocation().distanceSquared(leader.mob.getLocation()) > 9 && !mob.getPathfinder().hasPath()) {
                    mob.getPathfinder().moveTo(leader.mob.getLocation(), 1.0);
                }
                bot.home = leader.mob.getLocation();
                return;
            }
            bot.leader = null; // the leader fell: this one carries on alone
        }
        if (bot.looting == null) {
            LootContainer next = null;
            double best = 96 * 96;
            for (LootContainer container : bot.raid.containers.values()) {
                if (bot.visited.contains(container.id) || "body_bag".equals(container.type.id())
                        || (container.generated() && container.isEmpty())) {
                    continue;
                }
                double d = container.location.distanceSquared(mob.getLocation());
                if (d < best) {
                    best = d;
                    next = container;
                }
            }
            if (next == null) {
                return;
            }
            bot.looting = next;
            bot.lootUntil = 0;
            bot.home = next.location.clone();
        }
        LootContainer target = bot.looting;
        if (mob.getLocation().distanceSquared(target.location) > 2.8 * 2.8) {
            if (!mob.getPathfinder().hasPath()) {
                Pathfinder.PathResult path = mob.getPathfinder().findPath(target.location);
                if (path == null || !reaches(path, target.location, 2.5)) {
                    // Unreachable (behind a locked door, up a ladder): walking to the end of a partial
                    // path and planning it again would keep it there for the rest of the raid.
                    bot.visited.add(target.id);
                    bot.looting = null;
                } else {
                    mob.getPathfinder().moveTo(path, 1.0);
                }
            }
            return;
        }
        mob.getPathfinder().stopPathfinding();
        if (bot.lootUntil == 0) {
            bot.lootUntil = now + 20L * 4;
            mob.lookAt(target.location);
            return;
        }
        if (now < bot.lootUntil) {
            return;
        }
        // Takes the three most valuable things in it.
        if (!target.generated()) {
            target.generate(plugin.loot().roll(target.type, target.shift));
        }
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < target.size(); slot++) {
            if (target.get(slot) != null) {
                slots.add(slot);
            }
        }
        slots.sort((a, b) -> Long.compare(plugin.items().value(target.get(b)), plugin.items().value(target.get(a))));
        for (int i = 0; i < Math.min(3, slots.size()); i++) {
            bot.carried.add(target.get(slots.get(i)).clone());
            target.set(slots.get(i), null);
        }
        target.refreshLook();
        bot.visited.add(target.id);
        bot.looting = null;
    }

    private static final String[] CALLSIGNS = {"夜鸮", "灰狼", "蝮蛇", "渡鸦", "铁砧", "猎隼", "幽灵", "黑曜"};

    /** A rival operator squad: better gear and aim, they move from container to container taking the best loot. */
    public void operatorSquad(Raid raid, Location at) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String team = CALLSIGNS[rng.nextInt(CALLSIGNS.length)];
        Bot leader = null;
        for (int i = 0; i < 3; i++) {
            Location spot = at.clone().add(rng.nextDouble(-2, 2), 0, rng.nextDouble(-2, 2));
            if (!spot.getBlock().isPassable()) {
                spot = at.clone();
            }
            Bot bot = spawn(raid, spot, 6, "operator", Math.min(3, raid.tier.aiGear() + 1), false, "敌对干员 · " + team + "-" + (i + 1));
            if (bot == null) {
                continue;
            }
            if (leader == null) {
                leader = bot;
            } else {
                bot.leader = leader.mob.getUniqueId();
            }
        }
    }

    /**
     * A boss and its guards at the boss's post. {@code asleep}: they hold their posts without wandering
     * or answering gunfire elsewhere until {@link #wakeBoss} (its time has come), though they still see
     * whoever walks in on them and wake (all of them) when they spot someone or are shot.
     */
    public void spawnBoss(Raid raid, MapDef.BossDef def, boolean asleep) {
        Location at = def.pos().at(raid.world);
        Bot boss = spawn(raid, at, 10, "guard", 3, true, def.name());
        if (boss == null) {
            return;
        }
        boss.dormant = asleep;
        boss.group = def.id();
        for (int i = 0; i < def.guards(); i++) {
            Location g = at.clone().add(ThreadLocalRandom.current().nextDouble(-2, 2), 0, ThreadLocalRandom.current().nextDouble(-2, 2));
            if (!g.getBlock().isPassable()) {
                g = at.clone();
            }
            Bot guard = spawn(raid, g, 10, "guard", Math.min(3, raid.tier.aiGear() + 1), false, "指挥官护卫");
            if (guard != null) {
                guard.dormant = asleep;
                guard.group = def.id();
            }
        }
    }

    /** Wakes the boss {@code id} of a raid and its guards; returns the boss's name if it is still alive, else null. */
    public @Nullable String wakeBoss(Raid raid, String id) {
        String name = null;
        for (Bot bot : bots.values()) {
            if (bot.raid == raid && id.equals(bot.group) && bot.mob.isValid()) {
                bot.dormant = false;
                if (bot.boss) {
                    name = bot.name;
                }
            }
        }
        return name;
    }

    /**
     * A sleeping boss or guard saw someone, was hurt, heard a fight right next to it or lost one of
     * its group: it and the rest of its group wake up.
     */
    private void wake(Bot bot) {
        if (!bot.dormant || bot.group == null) {
            return;
        }
        for (Bot other : bots.values()) {
            if (other.raid == bot.raid && bot.group.equals(other.group)) {
                other.dormant = false;
            }
        }
    }

    private @Nullable Bot spawn(Raid raid, Location requested, double leash, String kind, int gear, boolean boss, String name) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        // Offsets around a post were only checked for a free block at the feet: next to a wall that
        // put soldiers' heads in it, and nothing kept them out of the water or off the map.
        Location at = standable(raid, requested);
        if (at == null) {
            at = spotNear(raid, requested, 2);
        }
        at.setYaw(requested.getYaw());
        String[] guns = switch (Math.min(3, gear)) {
            case 0 -> new String[]{"p18", "sv9", "r12", "k47", "sv9"};
            case 1 -> new String[]{"k47", "m4c", "sv9", "r12", "k47"};
            case 2 -> new String[]{"m4c", "scr", "v57", "kl7", "dm14"};
            default -> new String[]{"scr", "kl7", "m4c", "v57"};
        };
        if ("sniper".equals(kind)) {
            guns = gear >= 2 ? new String[]{"dm14", "awr"} : new String[]{"dm14"};
        }
        if ("shield".equals(kind)) {
            guns = gear >= 2 ? new String[]{"d50", "p18"} : new String[]{"p18"};
        }
        if (boss) {
            guns = new String[]{"kl7", "scr"};
        }
        ItemDef gun = plugin.registry().get(guns[rng.nextInt(guns.length)]);
        if (gun == null || gun.gun() == null) {
            return null;
        }
        List<ItemDef> ammoOptions = plugin.registry().ammoFor(gun.gun().caliber());
        ItemDef ammo = null;
        if (!ammoOptions.isEmpty()) {
            ammoOptions.sort((a, b) -> Integer.compare(a.ammo().penetration(), b.ammo().penetration()));
            int index = Math.min(ammoOptions.size() - 1, boss ? ammoOptions.size() - 1 : Math.max(0, gear - 1 + rng.nextInt(2)));
            ammo = ammoOptions.get(index);
        }
        int armorLevel = Math.min(6, Math.max(0, (boss ? 5 : "operator".equals(kind) ? gear + 2 : gear + 1) + rng.nextInt(-1, 2)));
        ItemDef finalAmmo = ammo;
        double accuracy = raid.tier.aiAccuracy() * (boss ? 1.15 : "sniper".equals(kind) ? 1.1 : "operator".equals(kind) ? 1.2 : 1.0);
        // Patrols are zombies and elite units (guards, snipers, bosses) husks: the resource pack dresses
        // the two as different soldiers (tools/art/soldiers.py) and gives them human sounds.
        @SuppressWarnings("unchecked")
        Class<Zombie> type = (Class<Zombie>) (Class<?>) ("operator".equals(kind) ? org.bukkit.entity.Drowned.class
                : "patrol".equals(kind) && !boss ? Zombie.class : org.bukkit.entity.Husk.class);
        Zombie mob = raid.world.spawn(at, type, CreatureSpawnEvent.SpawnReason.CUSTOM, z -> {
            z.setLeftHanded(false); // the gun model's third-person pose is tuned for the right hand
            z.setAdult();
            z.setShouldBurnInDay(false);
            z.setCanPickupItems(false);
            z.setPersistent(false);
            z.setRemoveWhenFarAway(false);
            z.setCanBreakDoors(false);
            z.setMaximumNoDamageTicks(0);
            EntityEquipment eq = z.getEquipment();
            eq.setItem(EquipmentSlot.HAND, plugin.items().loadedGun(gun, finalAmmo == null ? null : finalAmmo.id()));
            if ("shield".equals(kind)) {
                eq.setItem(EquipmentSlot.OFF_HAND, new ItemStack(org.bukkit.Material.SHIELD));
            }
            if (armorLevel > 0) {
                ItemDef helmet = plugin.registry().get("helmet_" + Math.max(1, armorLevel - rng.nextInt(2)));
                ItemDef vest = plugin.registry().get("armor_" + armorLevel);
                if (helmet != null && rng.nextDouble() < 0.8) {
                    eq.setHelmet(plugin.items().create(helmet, 1));
                }
                if (vest != null) {
                    eq.setChestplate(plugin.items().create(vest, 1));
                }
            }
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (slot != EquipmentSlot.BODY && slot != EquipmentSlot.SADDLE) {
                    eq.setDropChance(slot, 0f);
                }
            }
            double health = boss ? 380 : "sniper".equals(kind) ? 80 : "shield".equals(kind) || "operator".equals(kind) ? 120 : 100;
            setAttr(z, Attribute.MAX_HEALTH, health);
            z.setHealth(health);
            setAttr(z, Attribute.MOVEMENT_SPEED, boss ? 0.22 : 0.25);
            setAttr(z, Attribute.FOLLOW_RANGE, 48);
            setAttr(z, Attribute.KNOCKBACK_RESISTANCE, 0.8);
            setAttr(z, Attribute.SPAWN_REINFORCEMENTS, 0);
            // Zombies, husks and drowned are born with 2 points of vanilla armour, which took 1.6%
            // off every bullet and knife on top of the plugin's own armour.
            setAttr(z, Attribute.ARMOR, 0);
            if (boss) {
                setAttr(z, Attribute.SCALE, 1.12);
            }
            z.getPersistentDataContainer().set(Keys.key("ai"), PersistentDataType.STRING, kind);
        });
        Bukkit.getMobGoals().removeAllGoals(mob);
        // Paths may run through wooden doors; AiDoors opens them on the way (setCanBreakDoors(false)
        // above turned this off again).
        mob.getPathfinder().setCanOpenDoors(true);
        Bot bot = new Bot(mob, raid, at.clone(), Math.max(3, leash), kind, gun, ammo, name, boss, accuracy);
        bot.nextWanderTick = Bukkit.getCurrentTick() + rng.nextInt(40, 200);
        bots.put(mob.getUniqueId(), bot);
        return bot;
    }

    private static void setAttr(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    public void clear(Raid raid) {
        bots.values().removeIf(bot -> {
            if (bot.raid == raid) {
                bot.mob.remove();
                return true;
            }
            return false;
        });
    }

    /**
     * A strobe in the face: the soldier keeps moving and fighting, but its next shot is held back and
     * its rounds go wide until the effect wears off. A soldier that is already dazzled, or has just
     * got its eyes back, is not dazzled again, so holding a strobe on it cannot keep it blind.
     */
    public void dazzle(LivingEntity entity, int ticks) {
        dazzle(entity, ticks, false);
    }

    /**
     * Same as {@link #dazzle(LivingEntity, int)}; a flashbang ({@code overrideImmunity}) also works on a
     * soldier that has just recovered, and extends a dazzle already running.
     */
    public void dazzle(LivingEntity entity, int ticks, boolean overrideImmunity) {
        Bot bot = bots.get(entity.getUniqueId());
        if (bot == null || ticks <= 0) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        if (!overrideImmunity && (now < bot.dazzledUntil || now < bot.dazzleImmuneUntil)) {
            return;
        }
        bot.dazzledUntil = Math.max(bot.dazzledUntil, now + ticks);
        bot.dazzleImmuneUntil = bot.dazzledUntil + plugin.getConfig().getLong("ai.dazzle.immune-ticks", 80);
        bot.nextShotTick = Math.max(bot.nextShotTick, now + plugin.getConfig().getLong("ai.dazzle.shot-delay-ticks", 10));
    }

    /**
     * Gunfire and explosions draw nearby soldiers. A boss group asleep at its post ignores them
     * farther off, but wakes when one goes off within ai.boss-wake-radius blocks of any of them
     * (and within {@code radius}, so a suppressed shot has to be closer still).
     */
    public void alert(Location at, double radius, @Nullable Entity source) {
        if (isAi(source)) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        double wakeRadius = Math.min(radius, plugin.getConfig().getDouble("ai.boss-wake-radius", 12));
        for (Bot bot : bots.values()) {
            if (bot.dormant && bot.mob.getWorld() == at.getWorld() && bot.mob.getLocation().distanceSquared(at) <= wakeRadius * wakeRadius) {
                wake(bot);
            }
        }
        for (Bot bot : bots.values()) {
            if (bot.mob.getWorld() != at.getWorld() || bot.target != null || bot.dormant) {
                continue;
            }
            if (bot.mob.getLocation().distanceSquared(at) <= radius * radius) {
                bot.alertPos = at.clone();
                bot.alertUntil = now + 20L * 12;
            }
        }
    }

    // ------------------------------------------------------------------ brain

    /**
     * Every soldier thinks every 2 ticks, from whatever tick the plugin started on, so a check that
     * should come round every N ticks tests {@code now % N < 2}: {@code == 0} may never be true.
     */
    private void tick() {
        long now = Bukkit.getCurrentTick();
        List<UUID> dead = new ArrayList<>();
        for (Bot bot : bots.values()) {
            Zombie mob = bot.mob;
            if (!mob.isValid() || mob.isDead()) {
                dead.add(mob.getUniqueId());
                continue;
            }
            if (bot.raid.state != Raid.State.RUNNING) {
                continue;
            }
            think(bot, now);
        }
        dead.forEach(bots::remove);
        doors.tick(now);
        smokes.removeIf(smoke -> now >= smoke.until() || !plugin.raids().isRaidWorld(smoke.world()));
    }

    private void think(Bot bot, long now) {
        Zombie mob = bot.mob;
        if (doors.ahead(mob, bot.raid, now) == AiDoors.Ahead.LOCKED) {
            // A door it cannot open: it guards the doorway for a while instead of pushing at it.
            mob.getPathfinder().stopPathfinding();
            bot.holdUntil = now + HOLD_TICKS;
        }
        Player target = acquire(bot, now);
        if (bot.dormant) {
            if (target == null) {
                return; // asleep at its post: no wandering, no answering gunfire elsewhere
            }
            wake(bot);
        }
        Location eye = mob.getEyeLocation();
        if (target != null) {
            double distance = eye.distance(target.getEyeLocation());
            mob.lookAt(target);
            if (bot.firstSeenTick < 0) {
                bot.firstSeenTick = now;
                bot.burstLeft = 0;
                bot.nextShotTick = now + reactionTicks(bot);
                if (ThreadLocalRandom.current().nextInt(3) == 0) {
                    mob.getWorld().playSound(mob.getLocation(), "craftov:ai.spotted", SoundCategory.HOSTILE, 1.4f, 1f);
                }
                alertSquadmates(bot, target);
            }
            double preferred = "sniper".equals(bot.kind) ? 999 : "shield".equals(bot.kind) ? 7 : bot.gun.gun().range() * 0.8;
            if (distance > preferred && !"sniper".equals(bot.kind)) {
                if (!mob.getPathfinder().hasPath() || now % 10 < 2) {
                    go(bot, target.getLocation(), 1.0, now);
                }
            } else if (distance < 6 && !"sniper".equals(bot.kind) && !"shield".equals(bot.kind)) {
                Vector away = mob.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
                if (away.lengthSquared() > 0.01) {
                    // Backs off 4 blocks, or 2, but never off a pier or a ledge.
                    away.normalize();
                    Location back = standable(bot.raid, mob.getLocation().add(away.clone().multiply(4)));
                    if (back == null) {
                        back = standable(bot.raid, mob.getLocation().add(away.clone().multiply(2)));
                    }
                    if (back != null) {
                        mob.getPathfinder().moveTo(back, 1.0);
                    }
                }
            } else if (now % 40 < 2 && !"sniper".equals(bot.kind) && now >= bot.nextMoveTick && ThreadLocalRandom.current().nextBoolean()) {
                sidestep(bot, target, now);
            } else if (distance <= preferred && now >= bot.nextMoveTick) {
                // It holds its ground to shoot, but lets a sidestep under way finish: stopping here
                // every look cut each one off after a tenth of a block.
                mob.getPathfinder().stopPathfinding();
            }
            shoot(bot, target, distance, now);
            return;
        }
        bot.firstSeenTick = -1;
        if (bot.threatPos != null && now < bot.threatUntil) {
            respond(bot, now);
            return;
        }
        bot.threatPos = null;
        bot.coverPos = null;
        if ("operator".equals(bot.kind) && bot.lastSeen == null && (bot.alertPos == null || now >= bot.alertUntil)) {
            operate(bot, now);
            return;
        }
        if (bot.lastSeen != null && now - bot.lastSeenTick < 20L * 10) {
            if (!"sniper".equals(bot.kind) && (!mob.getPathfinder().hasPath() || now % 20 < 2 && !heading(mob, bot.lastSeen))) {
                go(bot, bot.lastSeen, 0.9, now);
            }
            return;
        }
        if (bot.lastSeen != null) {
            // The chase is over. A rival operator kept lastSeen for the rest of the raid and never
            // looted or followed its leader again; now it picks its next container afresh.
            bot.lastSeen = null;
            bot.looting = null;
        }
        if (bot.alertPos != null && now < bot.alertUntil) {
            if (!"sniper".equals(bot.kind) && !mob.getPathfinder().hasPath()) {
                Location toward = bot.alertPos.clone();
                if (toward.distanceSquared(bot.home) > Math.pow(bot.leash * 4, 2)) {
                    // As far that way as its leash allows, if it can stand there.
                    Vector dir = toward.toVector().subtract(bot.home.toVector()).normalize().multiply(bot.leash * 3);
                    toward = standable(bot.raid, bot.home.clone().add(dir));
                }
                if (toward != null) {
                    go(bot, toward, 0.9, now);
                }
            }
            return;
        }
        bot.alertPos = null;
        if (now >= bot.nextWanderTick && !"sniper".equals(bot.kind)) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            double radius = "guard".equals(bot.kind) ? Math.min(4, bot.leash) : bot.leash;
            // Only to somewhere it can stand: the dock patrol's walk used to reach 8 blocks out to sea.
            Location dest = null;
            for (int i = 0; i < 4 && dest == null; i++) {
                dest = standable(bot.raid, bot.home.clone().add(rng.nextDouble(-radius, radius), 0, rng.nextDouble(-radius, radius)));
            }
            if (dest != null) {
                mob.getPathfinder().moveTo(dest, 0.6);
            }
            bot.nextWanderTick = now + rng.nextInt(80, 220);
        }
        if (bot.ammoLeft < bot.gun.gun().magazine()) {
            bot.ammoLeft = bot.gun.gun().magazine();
        }
    }

    /**
     * Heads for {@code dest}. A path that cannot get there (a locked or iron door, a wall) still
     * takes the soldier as close as it gets, and it then holds there for a few seconds instead of
     * planning the same dead end again every tick.
     */
    private void go(Bot bot, Location dest, double speed, long now) {
        if (now < bot.holdUntil) {
            return;
        }
        Pathfinder pathfinder = bot.mob.getPathfinder();
        Pathfinder.PathResult path = pathfinder.findPath(dest);
        if (path == null) {
            bot.holdUntil = now + HOLD_TICKS;
            return;
        }
        if (!reaches(path, dest, 2.5)) {
            bot.holdUntil = now + HOLD_TICKS;
        }
        pathfinder.moveTo(path, speed);
    }

    /** Whether {@code path} ends within {@code slack} blocks of {@code dest}. */
    private static boolean reaches(Pathfinder.PathResult path, Location dest, double slack) {
        if (path.canReachFinalPoint()) {
            return true;
        }
        Location end = path.getFinalPoint();
        return end != null && end.getWorld() == dest.getWorld() && end.distanceSquared(dest) <= slack * slack;
    }

    /** Whether the soldier's current path already ends near {@code dest}. */
    private static boolean heading(Zombie mob, Location dest) {
        Pathfinder.PathResult path = mob.getPathfinder().getCurrentPath();
        Location end = path == null ? null : path.getFinalPoint();
        return end != null && end.getWorld() == dest.getWorld() && end.distanceSquared(dest) <= 2.5 * 2.5;
    }

    private long reactionTicks(Bot bot) {
        double base = plugin.getConfig().getDouble("ai.reaction-ticks", 14);
        return Math.round(base / Math.max(0.3, bot.accuracy) + ThreadLocalRandom.current().nextInt(0, 8));
    }

    /**
     * Who the soldier has spotted, if anyone. Nobody is seen at once: each look (every 2 ticks) adds to
     * the soldier's awareness of a player, faster the closer, the more of them shows (head, chest and
     * pelvis are checked separately, see {@link Sight}), the more they move and the brighter they
     * are lit, and only inside its field of view (120 degrees ahead, a little out to the sides,
     * nothing behind; wider once it is alert). Out of sight it fades again. Half way there the
     * soldier turns to look and comes over; at 1 it has spotted the player and fights. Someone it
     * was fighting a moment ago is spotted again as soon as any of them shows.
     */
    private @Nullable Player acquire(Bot bot, long now) {
        Zombie mob = bot.mob;
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        Look bestLook = null;
        Player suspect = null;
        double suspicion = 0;
        double sight = "sniper".equals(bot.kind) ? 90 : 48;
        Location from = mob.getLocation();
        Vector facing = from.getDirection().setY(0);
        boolean alerted = now < bot.alertUntil;
        var config = plugin.getConfig();
        double rate = config.getDouble("ai.awareness.rate", 2.0);
        double fade = config.getDouble("ai.awareness.fade", 0.25) * 0.1;
        java.util.Set<UUID> present = new java.util.HashSet<>();
        for (Player player : bot.raid.online()) {
            RaidPlayer rp = bot.raid.player(player.getUniqueId());
            // A player who just rejoined is seen and aimed at through their 2 s of protection
            // (shoot() holds fire until it ends), so soldiers fight on the moment it is over.
            if (rp == null || rp.state != RaidPlayer.State.ALIVE || now < rp.aiGraceUntil) {
                continue;
            }
            UUID id = player.getUniqueId();
            present.add(id);
            double aware = bot.awareness.getOrDefault(id, 0.0);
            // At night soldiers see about half as far, but a lit tactical light gives you away from beyond
            // normal sight, whichever way they face.
            boolean lit = plugin.lights().mode(player) != net.infnetwork.snowball.craftov.weapon.TacticalLight.Mode.OFF;
            double range = lit ? sight * 1.3 : bot.raid.night ? sight * 0.55 : sight;
            if (id.equals(bot.threatFrom) && now < bot.threatUntil) {
                range *= 1.5; // it knows which way to look for whoever just shot it
            }
            double d2 = from.distanceSquared(plugin.slides().feet(player));
            if (d2 > range * range) {
                bot.awareness.put(id, Math.max(0, aware - fade));
                continue;
            }
            double d = Math.sqrt(d2);
            // bot.target lets go 2 s after it lost sight; whoever it fought stays in mind for 10 s.
            boolean recent = id.equals(bot.lastFought) && now - bot.lastSeenTick < 20L * 10;
            boolean known = id.equals(bot.target) && now - bot.lastSeenTick < 40;
            // Hearing: footsteps carry as far for a soldier as for a player (FootstepService: walking 16
            // blocks, sprinting 32, half that through a wall, the silent walk nothing).
            boolean moving = moving(player) || plugin.slides().isSliding(player);
            boolean noisy = moving && plugin.footsteps().heardAt(player, mob.getEyeLocation());
            double view = view(facing, from, player.getLocation(), alerted);
            if (noisy) {
                view = Math.max(view, 0.8); // it turns towards the footsteps
            }
            Look look = null;
            double seen = 0;
            if (view > 0 || known || lit) {
                look = sightLines(bot, player);
                seen = look.shown();
            }
            if (seen > 0 && (known || lit)) {
                aware = 1;
            } else if (seen > 0 && view > 0) {
                double closeness = Math.pow(1 - d / range, 1.5) * (d < 6 && view >= 1 ? 3 : 1);
                boolean sliding = plugin.slides().isSliding(player);
                double stance = player.isSneaking() && !sliding ? 0.5 : 1.0;
                double motion = !moving ? 0.7 : player.isSprinting() || sliding ? 1.5 : 1.0;
                double light = 0.3 + 0.7 * player.getEyeLocation().getBlock().getLightLevel() / 15.0;
                aware = Math.min(1, aware + 0.1 * rate * closeness * stance * motion * light * view * seen * (alerted ? 1.5 : 1));
            } else {
                aware = Math.max(0, aware - fade);
                if (noisy && bot.target == null) {
                    // Footsteps behind a wall: go and look.
                    bot.alertPos = player.getLocation();
                    bot.alertUntil = now + 20L * 6;
                }
            }
            bot.awareness.put(id, aware);
            boolean spotted = seen > 0 && (aware >= 1 || recent && aware >= 0.5);
            if (spotted && d2 < bestDist) {
                best = player;
                bestDist = d2;
                bestLook = look;
            } else if (!spotted && seen > 0 && aware >= 0.4 && aware > suspicion) {
                suspect = player;
                suspicion = aware;
            }
        }
        bot.awareness.keySet().removeIf(id -> !present.contains(id) || bot.awareness.get(id) <= 0);
        if (best == null && suspect != null && bot.target == null) {
            // Something moved over there: it looks, and walks over to see what it was.
            mob.lookAt(suspect.getEyeLocation());
            bot.alertPos = suspect.getLocation();
            bot.alertUntil = Math.max(bot.alertUntil, now + 20L * 4);
        }
        bot.targetLook = bestLook;
        if (best != null) {
            if (!best.getUniqueId().equals(bot.target) || now - bot.lastSeenTick > 60) {
                bot.engagedSince = now;
            }
            bot.target = best.getUniqueId();
            bot.lastFought = bot.target;
            bot.lastSeen = best.getLocation();
            bot.lastSeenTick = now;
        } else if (now - bot.lastSeenTick > 40) {
            bot.target = null;
        } else if (bot.target != null && now - bot.lastSeenTick <= 30) {
            // It saw where you went in the moment you vanished (round a corner, through a door it
            // then has to open), so it follows you there rather than to the spot you left.
            Player gone = Bukkit.getPlayer(bot.target);
            if (gone != null && gone.getWorld() == mob.getWorld()) {
                bot.lastSeen = gone.getLocation();
            }
        }
        return best;
    }

    /**
     * How well a soldier facing {@code facing} (flat) can see {@code to} from {@code from}: 1 inside
     * the 120 degree cone ahead, 0.3 out to 100 degrees either side, 0 behind. Alert, it looks around:
     * the whole half ahead counts fully and the sides up to 120 degrees partly.
     */
    private static double view(Vector facing, Location from, Location to, boolean alerted) {
        Vector toward = to.toVector().subtract(from.toVector()).setY(0);
        if (facing.lengthSquared() < 1e-4 || toward.lengthSquared() < 0.25) {
            return 1;
        }
        double cos = facing.clone().normalize().dot(toward.normalize());
        if (alerted) {
            return cos >= 0 ? 1 : cos >= -0.5 ? 0.4 : 0;
        }
        return cos >= 0.5 ? 1 : cos >= -0.17 ? 0.3 : 0;
    }

    /**
     * How much of each sight line from the soldier's eyes to the player's head, chest and pelvis gets
     * through, and whether one of its rounds would: blocks as {@link Sight#look}, and smoke hides
     * what is behind more than {@link #SMOKE_OPAQUE} blocks of it (a thinner stretch only dims it).
     */
    private Look sightLines(Bot bot, Player player) {
        Vector eye = bot.mob.getEyeLocation().toVector();
        AmmoSpec ammo = bot.ammo == null ? null : bot.ammo.ammo();
        int pen = ammo == null ? 1 : ammo.penetration();
        Ballistics ballistics = plugin.weapons().ballistics();
        Vector feet = plugin.slides().feet(player).toVector();
        double[] heights = aimHeights(player);
        double[] seen = new double[heights.length];
        boolean[] open = new boolean[heights.length];
        for (int i = 0; i < heights.length; i++) {
            Vector point = feet.clone().add(new Vector(0, heights[i], 0));
            double smoke = smokeAlong(player.getWorld(), eye, point);
            if (smoke > SMOKE_OPAQUE) {
                continue;
            }
            Sight.Line line = Sight.look(player.getWorld(), eye, point, block -> ballistics.passes(block, pen));
            seen[i] = line.seen() * (1 - 0.5 * smoke / SMOKE_OPAQUE);
            open[i] = line.open();
        }
        return new Look(seen, open);
    }

    /**
     * A smoke grenade went off at {@code at}: for {@code ticks} soldiers cannot see through its
     * cloud, an upright ellipsoid a little smaller than the particles' spread ({@code radius} 6 makes
     * one 4.8 blocks across and 2.5 up and down, its centre 0.8 above the grenade). It fills
     * out over the first second and thins over the last three.
     */
    public void smoke(Location at, double radius, long ticks) {
        long now = Bukkit.getCurrentTick();
        smokes.add(new Smoke(at.getWorld(), at.toVector().add(new Vector(0, 0.8, 0)), radius * 0.8, 2.5, now, now + ticks));
    }

    /** Blocks of smoke a line from {@code from} to {@code to} passes through. */
    private double smokeAlong(World world, Vector from, Vector to) {
        long now = Bukkit.getCurrentTick();
        double total = 0;
        for (Smoke smoke : smokes) {
            if (smoke.world() != world || now < smoke.from()) {
                continue;
            }
            double grow = Math.min(1, (now - smoke.from() + 5) / 25.0);
            double thin = Math.min(1, 0.5 + (smoke.until() - now) / 120.0);
            double scale = Math.min(grow, thin);
            total += Sight.chord(from, to, smoke.centre(), smoke.radius() * scale, smoke.height() * scale);
        }
        return total;
    }

    /**
     * Head, chest and pelvis heights above a player's feet, for standing, crouching or crawling; above
     * the ground for a player sliding (sat low, the head 0.7 to 1.2 up: SlideService).
     */
    private double[] aimHeights(Player player) {
        if (plugin.slides().isSliding(player)) {
            return new double[]{1.0, 0.45, 0.2};
        }
        double h = player.getHeight();
        return new double[]{h - 0.2, h * 0.55, h * 0.33};
    }

    private static boolean moving(Player player) {
        org.bukkit.Input input = player.getCurrentInput();
        return input.isForward() || input.isBackward() || input.isLeft() || input.isRight();
    }

    private void alertSquadmates(Bot bot, Player target) {
        for (Bot other : bots.values()) {
            if (other != bot && other.raid == bot.raid && other.target == null && !other.dormant
                    && other.mob.getLocation().distanceSquared(bot.mob.getLocation()) < 24 * 24) {
                other.alertPos = target.getLocation();
                other.alertUntil = Bukkit.getCurrentTick() + 20L * 10;
            }
        }
    }

    private void shoot(Bot bot, Player target, double distance, long now) {
        GunSpec gun = bot.gun.gun();
        if (now < bot.reloadUntil || now < bot.nextShotTick || !plugin.raids().canBeShot(target)) {
            return;
        }
        if (bot.ammoLeft <= 0) {
            reload(bot, now);
            return;
        }
        if (bot.burstLeft <= 0) {
            bot.burstLeft = burst(gun, distance, 3, 7);
        }
        Location eye = bot.mob.getEyeLocation();
        Vector point = aimPoint(target, bot.targetLook);
        if (point == null) {
            // It sees the target only through something that stops its rounds (a fence, say):
            // move for a clear shot rather than empty the magazine into it. Snipers hold fire.
            reposition(bot, target, now);
            return;
        }
        if (friendlyInLine(bot, eye, point)) {
            // A fellow soldier in the way: step aside instead of shooting into its back.
            bot.nextShotTick = now + 6;
            reposition(bot, target, now);
            return;
        }
        // Rounds are hitscan, so there is nothing to lead: a moving target is just harder to hold on.
        fireRound(bot, eye, point, aimError(bot) * movingFactor(target) * settle(bot, now), now, target);
    }

    /**
     * How much wider a soldier's rounds land around a moving target: x1.4 at a walk, up to x1.52 at a
     * sprint. Bukkit's velocity of a walking player reads zero (and about -0.08 straight down when it
     * stands), so this uses the speed measured from its position each tick; a player pressing into a
     * wall is standing still.
     */
    private double movingFactor(Player target) {
        return 1.0 + 0.4 * Math.min(1.3, plugin.weapons().horizontalSpeed(target)
                / net.infnetwork.snowball.craftov.weapon.WeaponService.WALK_SPEED);
    }

    /** x2.6 on the first shot of a fight, settling to x1 over 3 s. */
    private static double settle(Bot bot, long now) {
        return 1.0 + 1.6 * Math.max(0, 1 - (now - bot.engagedSince) / 60.0);
    }

    /**
     * Rounds in the next burst: {@code min} to {@code max - 1} from an automatic gun, one or two past
     * the gun's effective range (single aimed shots there), one from anything else.
     */
    private static int burst(GunSpec gun, double distance, int min, int max) {
        if (!gun.modes().contains(net.infnetwork.snowball.craftov.weapon.FireMode.AUTO)) {
            return 1;
        }
        return distance > gun.range() ? ThreadLocalRandom.current().nextInt(1, 3) : ThreadLocalRandom.current().nextInt(min, max);
    }

    /**
     * How far from the point it aims at a soldier's rounds land, in metres at the target whatever the
     * distance: ai.aim-error goes from its first value at the lowest accuracy (常规, 0.55) to its second
     * at 1.0 and above (绝密 operators and bosses); snipers use ai.sniper-aim-error.
     */
    private double aimError(Bot bot) {
        boolean sniper = "sniper".equals(bot.kind);
        List<Double> range = plugin.getConfig().getDoubleList(sniper ? "ai.sniper-aim-error" : "ai.aim-error");
        double worst = range.isEmpty() ? (sniper ? 0.2 : 0.6) : range.get(0);
        double best = range.size() < 2 ? (sniper ? 0.1 : 0.25) : range.get(1);
        double skill = Math.max(0, Math.min(1, (bot.accuracy - 0.55) / 0.45));
        return worst + (best - worst) * skill;
    }

    /**
     * One pull of the trigger at {@code point}: the rounds land within {@code error} metres of it
     * (more when dazzled or shooting past smoke), with the muzzle flash, sound and casing, and the next
     * shot timed by the gun's rate of fire and the burst. {@code target} is who it shoots at (null:
     * a spot), for the debug log of soldier shots.
     */
    private void fireRound(Bot bot, Location eye, Vector point, double error, long now, @Nullable Player target) {
        GunSpec gun = bot.gun.gun();
        bot.burstLeft--;
        bot.ammoLeft--;
        double distance = Math.max(1, eye.toVector().distance(point));
        Vector aim = point.clone().subtract(eye.toVector()).normalize();
        if (now < bot.dazzledUntil) {
            error *= plugin.getConfig().getDouble("ai.dazzle.spread-multiplier", 3.0);
        }
        // Shooting past the edge of a smoke cloud: it sees you only dimly and goes wide.
        double haze = smokeAlong(eye.getWorld(), eye.toVector(), point);
        if (haze > 0) {
            error *= 1 + Math.min(SMOKE_OPAQUE, haze);
        }
        double spread = Math.toDegrees(Math.atan(error / distance));
        AmmoSpec ammo = bot.ammo == null ? null : bot.ammo.ammo();
        // A share of what a player deals with the same gun and ammo to the body and limbs (headshots
        // get less: scaledGun halves the head multiplier); nothing scales it again on the way
        // (DamageService.deal keeps vanilla's difficulty scaling out of hits on players).
        GunSpec scaled = scaledGun(gun, plugin.getConfig().getDouble("ai.damage-multiplier", 0.65) * (bot.boss ? 1.15 : 1.0));
        int hits = 0;
        for (int i = 0; i < gun.pellets(); i++) {
            LivingEntity hit = plugin.weapons().ballistics().fire(bot.mob, eye,
                    Ballistics.spread(aim, spread + (gun.pellets() > 1 ? gun.hipSpread() : 0)), scaled, ammo, bot.gun.name(), i == 0);
            if (target != null && hit == target) {
                hits++;
            }
        }
        if (target != null && plugin.getConfig().getBoolean("debug", false)) {
            // aim: how high above the target's feet it aimed (the chest, or the head over low cover).
            plugin.getLogger().info(String.format("[debug] ai %s shot %s dist=%.1f speed=%.3f moving=%.2f settle=%.2f spread=%.2f pellets=%d hits=%d aim=%.2f",
                    bot.kind, target.getName(), distance, plugin.weapons().horizontalSpeed(target), movingFactor(target), settle(bot, now),
                    spread, gun.pellets(), hits, point.getY() - target.getLocation().getY()));
        }
        Ballistics.muzzle(bot.mob, aim);
        Location muzzle = Ballistics.muzzlePoint(bot.mob, aim);
        plugin.weapons().audio().shot(bot.mob, muzzle, gun, false);
        plugin.weapons().fx().muzzleLight(bot.mob, muzzle);
        if (!"pump".equals(gun.action()) && !"bolt".equals(gun.action())) {
            plugin.weapons().audio().casing(bot.mob, gun, 9 + ThreadLocalRandom.current().nextInt(5));
        }
        long interval = Math.max(2, Math.round(1200.0 / gun.rpm()));
        if (bot.burstLeft <= 0) {
            interval += ThreadLocalRandom.current().nextInt(12, 30);
        }
        bot.nextShotTick = now + interval;
    }

    /**
     * Where to aim at {@code target}, among the parts it sees and a round would reach (not through a
     * fence, say): the chest (misses then land on the body or past it, rarely the head),
     * else the head (someone crouched behind low cover), else the pelvis (under something). Null if
     * none of them. {@code look} is the last look at the target; without one the chest.
     */
    private @Nullable Vector aimPoint(Player target, @Nullable Look look) {
        double[] heights = aimHeights(target);
        Vector feet = plugin.slides().feet(target).toVector();
        if (look == null) {
            return feet.add(new Vector(0, heights[1], 0));
        }
        for (int part : new int[]{1, 0, 2}) {
            if (look.seen()[part] > 0 && look.open()[part]) {
                return feet.add(new Vector(0, heights[part], 0));
            }
        }
        return null;
    }

    /** Whether a fellow soldier stands in the line of fire from {@code eye} to {@code point}. */
    private boolean friendlyInLine(Bot bot, Location eye, Vector point) {
        Vector line = point.clone().subtract(eye.toVector());
        double length = line.length();
        if (length < 1) {
            return false;
        }
        RayTraceResult hit = eye.getWorld().rayTraceEntities(eye, line.multiply(1 / length), length - 0.5, 0.3,
                entity -> entity != bot.mob && isAi(entity));
        return hit != null;
    }

    /** A couple of steps to the side, to get a clear shot at {@code target}. */
    private void reposition(Bot bot, Player target, long now) {
        if ("sniper".equals(bot.kind) || now < bot.nextMoveTick || bot.mob.getPathfinder().hasPath()) {
            return;
        }
        sidestep(bot, target, now);
    }

    /**
     * Three blocks to one side of the line to {@code target}, chosen at random (the other side if it
     * cannot stand there, see {@link #standable}); for the next 1.5 s it neither stops nor starts
     * another step, so it gets there.
     */
    private void sidestep(Bot bot, Player target, long now) {
        bot.nextMoveTick = now + 30;
        Location at = bot.mob.getLocation();
        Vector dir = target.getLocation().toVector().subtract(at.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) {
            return;
        }
        Vector side = new Vector(-dir.getZ(), 0, dir.getX()).normalize().multiply(ThreadLocalRandom.current().nextBoolean() ? 3 : -3);
        Location dest = standable(bot.raid, at.clone().add(side));
        if (dest == null) {
            dest = standable(bot.raid, at.clone().subtract(side));
        }
        if (dest != null) {
            bot.mob.getPathfinder().moveTo(dest, 1.0);
        }
    }

    private static GunSpec scaledGun(GunSpec gun, double mult) {
        return new GunSpec(gun.caliber(), gun.slot(), gun.magazine(), gun.rpm(), gun.modes(), gun.damage() * mult, gun.range(),
                gun.maxRange(), gun.hipSpread(), gun.adsSpread(), gun.moveSpread(), gun.recoil(), gun.recoilH(), gun.recoilReturn(), gun.reloadTicks(), gun.adsClass(),
                gun.pellets(), Math.max(1.0, gun.headMultiplier() * 0.5), gun.soundVolume(), gun.moveSpeed(), gun.mount(), gun.scope(), gun.audio(), gun.action(),
                gun.handling());
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        Bot bot = bots.remove(event.getEntity().getUniqueId());
        if (bot == null) {
            return;
        }
        // A sleeping guard killed outright (a grenade, one headshot) still wakes the rest of its group.
        wake(bot);
        event.getDrops().clear();
        event.setDroppedExp(0);
        Raid raid = bot.raid;
        DamageService.LastHit hit = plugin.damage().lastHit(bot.mob.getUniqueId());
        Player killer = hit == null || hit.attacker() == null ? null : Bukkit.getPlayer(hit.attacker());
        RaidPlayer rp = killer == null ? null : raid.player(killer.getUniqueId());
        // Only a killer still in the raid: one who has died or got out had their kills saved then.
        if (killer != null && rp != null && rp.active()) {
            rp.aiKills++;
            // A knife kill is one the killing record says was a cut, not whatever is in hand now.
            plugin.tasks().onAiKill(killer, bot.boss, hit.headshot(), hit.melee());
            Notify.info(killer, "<accent>✖ 击败 <white>" + Text.escape(bot.name)
                    + (hit.headshot() ? " <danger>爆头" : ""));
            plugin.killFeed().soldierKilled(raid, killer, bot.name, hit.weapon(), hit.headshot());
        }
        dropBody(bot);
    }

    private void dropBody(Bot bot) {
        ContainerType type = plugin.loot().type("body_bag");
        if (type == null) {
            return;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<ItemStack> items = new ArrayList<>();
        EntityEquipment eq = bot.mob.getEquipment();
        ItemStack gun = eq.getItemInMainHand();
        var config = plugin.getConfig();
        // Bodies are worth farming less the harder the raid: its guns are worth more there.
        List<Double> gunChances = config.getDoubleList("ai.drop.gun-chance");
        double gunChance = gunChances.isEmpty() ? 0.6 : gunChances.get(Math.min(gunChances.size() - 1, Math.max(0, bot.raid.tier.aiGear())));
        if (!gun.isEmpty() && rng.nextDouble() < (bot.boss ? 1.0 : gunChance)) {
            ItemStack copy = gun.clone();
            plugin.items().setAmmo(copy, rng.nextInt(bot.gun.gun().magazine() / 2 + 1), bot.ammo == null ? null : bot.ammo.id());
            items.add(copy);
        }
        if (bot.ammo != null) {
            items.add(plugin.items().create(bot.ammo, Math.min(bot.ammo.maxStack(), rng.nextInt(10, 40))));
        }
        List<Double> wear = config.getDoubleList("ai.drop.armor-durability");
        double least = wear.isEmpty() ? 0.3 : wear.get(0);
        double most = wear.size() < 2 ? 0.7 : Math.max(least, wear.get(1));
        for (ItemStack armor : new ItemStack[]{eq.getHelmet(), eq.getChestplate()}) {
            if (armor != null && !armor.isEmpty() && rng.nextDouble() < 0.45) {
                ItemStack copy = armor.clone();
                ItemDef def = plugin.items().defOf(copy);
                if (def != null) {
                    // Never better than it was when it fell (the fight wore it down).
                    double left = def.durability() * (least + (most - least) * rng.nextDouble());
                    plugin.items().setDura(copy, Math.max(1, Math.min(net.infnetwork.snowball.craftov.item.ItemFactory.dura(copy, def.durability()), left)));
                }
                items.add(copy);
            }
        }
        ContainerType crate = plugin.loot().type(bot.boss ? "vault" : "parcel");
        if (crate != null) {
            items.addAll(plugin.loot().roll(crate, bot.raid.tier.lootShift() + (bot.boss ? 1 : 0)));
        }
        if (rng.nextDouble() < 0.35) {
            ItemDef med = plugin.loot().pick(Category.MEDICAL, Rarity.GREEN, rng);
            if (med != null) {
                items.add(plugin.items().create(med, 1));
            }
        }
        if (bot.boss && rng.nextDouble() < 0.4) {
            ItemDef key = plugin.registry().get("key_hq");
            if (key != null) {
                items.add(plugin.items().create(key, 1));
            }
        }
        // The fallen soldier stays as a body in the same uniform (the corpse skins mirror the zombie and
        // husk skins, see tools/art/soldiers.py), wearing the helmet and vest it had.
        items.addAll(bot.carried);
        boolean elite = bot.mob instanceof org.bukkit.entity.Husk;
        boolean operator = bot.mob instanceof org.bukkit.entity.Drowned;
        io.papermc.paper.datacomponent.item.ResolvableProfile look = io.papermc.paper.datacomponent.item.ResolvableProfile
                .resolvableProfile().skinPatch(p -> p.body(net.kyori.adventure.key.Key.key("craftov", "entity/soldier/"
                        + (operator ? "operator" : elite ? "elite" : "patrol"))).model(org.bukkit.profile.PlayerTextures.SkinModel.CLASSIC)).build();
        plugin.raids().spawnBody(bot.raid, bot.mob.getLocation(), bot.name + " 的遗物", items, look, eq.getHelmet(), eq.getChestplate());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (isAi(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (isAi(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * A soldier stays what it is: a zombie under water turns into a drowned and a husk into a zombie,
     * and the new mob is no soldier (it would vanish without a body).
     */
    @EventHandler(ignoreCancelled = true)
    public void onTransform(org.bukkit.event.entity.EntityTransformEvent event) {
        if (isAi(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Nor does it choke in a wall or drown: dying of where it was put is no death a player caused. */
    @EventHandler(ignoreCancelled = true)
    public void onChoke(org.bukkit.event.entity.EntityDamageEvent event) {
        var cause = event.getCause();
        if ((cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.SUFFOCATION
                || cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.DROWNING) && isAi(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        World world = event.getLocation().getWorld();
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM
                && (plugin.raids().isRaidWorld(world) || plugin.lobby().isLobby(world))) {
            event.setCancelled(true);
        }
    }

    /**
     * A player hit this soldier (or its shield) with a round, a blade or a grenade. A sleeping
     * soldier wakes with its whole group. Someone it is fighting already changes nothing. A
     * hit from anyone else gives away roughly where it came from (within ai.threat.error-meters, less
     * close up) but not who: the soldier grows more aware of the shooter (+0.5, at most 0.9, so it
     * still has to see them to fight them), turns that way and for ai.threat.seconds fires bursts at
     * the spot and works its way towards it from cover to cover (see {@link #respond}); soldiers within
     * 24 blocks are called over to the spot.
     */
    public void onBotHurt(LivingEntity victim, @Nullable LivingEntity attacker) {
        Bot bot = bots.get(victim.getUniqueId());
        if (bot == null || !(attacker instanceof Player player) || player.getWorld() != victim.getWorld()) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        UUID id = player.getUniqueId();
        wake(bot);
        if (id.equals(bot.target) && now - bot.lastSeenTick < 40) {
            return;
        }
        var config = plugin.getConfig();
        bot.awareness.merge(id, 0.5, (old, add) -> Math.max(old, Math.min(0.9, old + add)));
        Location from = player.getLocation();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double error = Math.min(config.getDouble("ai.threat.error-meters", 5), 1 + 0.1 * from.distance(victim.getLocation()));
        double angle = rng.nextDouble(Math.PI * 2);
        double off = error * Math.sqrt(rng.nextDouble());
        Location guess = from.clone().add(Math.cos(angle) * off, 0, Math.sin(angle) * off);
        boolean fresh = bot.threatPos == null || now >= bot.threatUntil;
        bot.threatPos = guess;
        bot.threatFrom = id;
        bot.threatUntil = now + 20L * config.getLong("ai.threat.seconds", 8);
        bot.alertPos = guess.clone();
        bot.alertUntil = Math.max(bot.alertUntil, bot.threatUntil + 20L * 4);
        if (fresh) {
            bot.coverPos = null;
            bot.coverUntil = 0;
            bot.nextCoverSearch = 0;
            bot.nextShotTick = Math.max(bot.nextShotTick, now + reactionTicks(bot));
            if (rng.nextBoolean()) {
                victim.getWorld().playSound(victim.getLocation(), "craftov:ai.spotted", SoundCategory.HOSTILE, 1.4f, 1f);
            }
        }
        for (Bot other : bots.values()) {
            if (other != bot && other.raid == bot.raid && other.target == null && !other.dormant && other.mob.getWorld() == victim.getWorld()
                    && other.mob.getLocation().distanceSquared(victim.getLocation()) < 24 * 24) {
                other.alertPos = guess.clone();
                other.alertUntil = Math.max(other.alertUntil, now + 20L * 10);
            }
        }
    }

    /**
     * Under fire from someone it has not spotted: it looks where the shots came from, works its way
     * there from cover to cover (2-4 s behind each; with no cover around it closes in a few blocks at
     * a time; snipers stay put) and fires bursts at the spot whenever it has a line of fire.
     */
    private void respond(Bot bot, long now) {
        Zombie mob = bot.mob;
        Location threat = bot.threatPos;
        if (threat == null || threat.getWorld() != mob.getWorld()) {
            bot.threatUntil = 0;
            return;
        }
        mob.lookAt(threat.clone().add(0, 1.5, 0));
        if (!"sniper".equals(bot.kind)) {
            boolean there = bot.coverPos != null && mob.getLocation().distanceSquared(bot.coverPos) < 1.2 * 1.2;
            if (there && bot.coverUntil == 0) {
                bot.coverUntil = now + ThreadLocalRandom.current().nextInt(40, 80);
                mob.getPathfinder().stopPathfinding();
            }
            if ((bot.coverPos == null || there && now >= bot.coverUntil) && now >= bot.nextCoverSearch) {
                bot.nextCoverSearch = now + 30;
                bot.coverPos = findCover(bot, threat);
                bot.coverUntil = 0;
                if (bot.coverPos == null && !mob.getPathfinder().hasPath()) {
                    // Nothing to hide behind: close in on the spot a few blocks at a time.
                    Vector toward = threat.toVector().subtract(mob.getLocation().toVector()).setY(0);
                    if (toward.lengthSquared() > 16) {
                        Location step = standable(bot.raid, mob.getLocation().add(toward.normalize().multiply(5)));
                        if (step != null && step.distanceSquared(bot.home) <= Math.pow(bot.leash * 4, 2)) {
                            go(bot, step, 1.0, now);
                        }
                    }
                }
            }
            if (bot.coverPos != null && !there && !mob.getPathfinder().hasPath()) {
                go(bot, bot.coverPos, 1.0, now);
                if (now < bot.holdUntil) {
                    bot.coverPos = null; // cannot get there: look for another
                }
            }
        }
        suppress(bot, threat, now);
    }

    /**
     * A spot within 9 blocks where a wall hides a soldier's chest from {@code threat}, no farther
     * from it than the soldier is now (it closes in rather than falls back) and within its leash:
     * the one that gains most ground. Null if there is none.
     */
    private @Nullable Location findCover(Bot bot, Location threat) {
        Location at = bot.mob.getLocation();
        Vector shooter = threat.toVector().add(new Vector(0, 1.5, 0));
        double current = at.distance(threat);
        double reach = bot.leash * 4;
        Location best = null;
        double bestScore = Double.MAX_VALUE;
        for (int ring = 3; ring <= 9; ring += 2) {
            for (int k = 0; k < 12; k++) {
                double angle = Math.PI * 2 * k / 12 + ring;
                Location spot = standable(bot.raid, at.clone().add(Math.cos(angle) * ring, 0, Math.sin(angle) * ring));
                if (spot == null) {
                    continue;
                }
                double toThreat = spot.distance(threat);
                if (toThreat > current + 1 || spot.distanceSquared(bot.home) > reach * reach
                        || Sight.through(at.getWorld(), shooter, spot.toVector().add(new Vector(0, 1.1, 0))) > 0) {
                    continue;
                }
                double score = toThreat + 0.5 * spot.distance(at);
                if (score < bestScore) {
                    best = spot;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    /**
     * Suppressing fire: short bursts at {@code threat}, wider than aimed fire, while it has a line of
     * fire there (not into the wall it hides behind, not through a fellow soldier). Snipers only shoot
     * at what they see.
     */
    private void suppress(Bot bot, Location threat, long now) {
        GunSpec gun = bot.gun.gun();
        if ("sniper".equals(bot.kind) || now < bot.reloadUntil || now < bot.nextShotTick) {
            return;
        }
        if (bot.ammoLeft <= 0) {
            reload(bot, now);
            return;
        }
        Location eye = bot.mob.getEyeLocation();
        Vector point = threat.toVector().add(new Vector(0, 1.0, 0));
        double distance = eye.toVector().distance(point);
        if (distance < 1 || distance > gun.maxRange()) {
            return;
        }
        Vector dir = point.clone().subtract(eye.toVector()).multiply(1 / distance);
        RayTraceResult wall = eye.getWorld().rayTraceBlocks(eye, dir, distance, org.bukkit.FluidCollisionMode.NEVER, true);
        if (wall != null && wall.getHitPosition().distance(eye.toVector()) < distance * 0.7 || friendlyInLine(bot, eye, point)) {
            bot.nextShotTick = now + 10;
            return;
        }
        if (bot.burstLeft <= 0) {
            bot.burstLeft = burst(gun, distance, 2, 5);
        }
        fireRound(bot, eye, point, aimError(bot) * 2.5, now, null);
        if (bot.burstLeft <= 0) {
            bot.nextShotTick += ThreadLocalRandom.current().nextInt(20, 40);
        }
    }

    private void reload(Bot bot, long now) {
        GunSpec gun = bot.gun.gun();
        bot.ammoLeft = gun.magazine();
        bot.reloadUntil = now + gun.reloadTicks() + 10;
        plugin.weapons().audio().foley(bot.mob, gun, "magout", 0.9f);
    }

    /**
     * A spot a soldier can stand on (see {@link #standable}) up to {@code spread} blocks from {@code
     * base}, tried at random a few times; else {@code base} as it is.
     */
    private static Location spotNear(Raid raid, Location base, double spread) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < 8; i++) {
            Location spot = standable(raid, base.clone().add(rng.nextDouble(-spread, spread), 0, rng.nextDouble(-spread, spread)));
            if (spot != null) {
                return spot;
            }
        }
        return base.clone();
    }

    /**
     * A spot a soldier can stand on at {@code at} or a block above or below it: two passable, dry
     * blocks over solid ground, inside the map's bounds. Null if there is none.
     */
    private static @Nullable Location standable(Raid raid, Location at) {
        if (!raid.map.inside(at.getX(), at.getZ())) {
            return null;
        }
        for (int dy : new int[]{0, 1, -1}) {
            Location spot = new Location(at.getWorld(), Math.floor(at.getX()) + 0.5, Math.floor(at.getY()) + dy, Math.floor(at.getZ()) + 0.5);
            org.bukkit.block.Block feet = spot.getBlock();
            org.bukkit.block.Block head = feet.getRelative(org.bukkit.block.BlockFace.UP);
            org.bukkit.block.Block floor = feet.getRelative(org.bukkit.block.BlockFace.DOWN);
            if (feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid()
                    && floor.getType().isSolid() && !floor.isLiquid()) {
                return spot;
            }
        }
        return null;
    }
}
