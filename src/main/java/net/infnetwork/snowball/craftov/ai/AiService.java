package net.infnetwork.snowball.craftov.ai;

import net.infnetwork.snowball.craftov.CraftovPlugin;
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

    private final CraftovPlugin plugin;
    private final Map<UUID, Bot> bots = new HashMap<>();

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
        long stunnedUntil;
        /** Rival operators: what they have looted (dropped on death), the container they are on, squad leader. */
        final List<ItemStack> carried = new ArrayList<>();
        final java.util.Set<String> visited = new java.util.HashSet<>();
        @Nullable LootContainer looting;
        long lootUntil;
        @Nullable UUID leader;

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
            if (bot.raid == raid && bot.boss && bot.mob.isValid()) {
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

    /** Melee class of an AI: "boss", "special" (guards, snipers, escorts) or "normal", or null if not AI. */
    public @Nullable String meleeClass(Entity entity) {
        Bot bot = bots.get(entity.getUniqueId());
        if (bot == null) {
            return null;
        }
        return bot.boss ? "boss" : "patrol".equals(bot.kind) ? "normal" : "special";
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
            Location at = raid.map.spawns().get(rng.nextInt(raid.map.spawns().size())).pos().at(raid.world);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (raid.state == Raid.State.RUNNING) {
                    operatorSquad(raid, at);
                }
            }, 20L * plugin.getConfig().getInt("ai.operator-delay-seconds", 60));
        }
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
                if (!mob.getPathfinder().moveTo(target.location, 1.0)) {
                    bot.visited.add(target.id); // unreachable
                    bot.looting = null;
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

    private static final String[] CALLSIGNS = {"夜枭", "灰狼", "蝮蛇", "渡鸦", "铁砧", "猎隼", "幽灵", "黑曜"};

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

    public void spawnBoss(Raid raid, MapDef.BossDef def) {
        Location at = def.pos().at(raid.world);
        Bot boss = spawn(raid, at, 10, "guard", 3, true, def.name());
        if (boss == null) {
            return;
        }
        for (int i = 0; i < def.guards(); i++) {
            Location g = at.clone().add(ThreadLocalRandom.current().nextDouble(-2, 2), 0, ThreadLocalRandom.current().nextDouble(-2, 2));
            if (!g.getBlock().isPassable()) {
                g = at.clone();
            }
            spawn(raid, g, 10, "guard", Math.min(3, raid.tier.aiGear() + 1), false, "指挥官护卫");
        }
    }

    private @Nullable Bot spawn(Raid raid, Location at, double leash, String kind, int gear, boolean boss, String name) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
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
            if (boss) {
                setAttr(z, Attribute.SCALE, 1.12);
            }
            z.getPersistentDataContainer().set(Keys.key("ai"), PersistentDataType.STRING, kind);
        });
        Bukkit.getMobGoals().removeAllGoals(mob);
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

    public void stun(Location at, double radius, int ticks) {
        for (Bot bot : bots.values()) {
            if (bot.mob.getWorld() == at.getWorld() && bot.mob.getLocation().distanceSquared(at) <= radius * radius) {
                bot.stunnedUntil = Bukkit.getCurrentTick() + ticks;
                bot.mob.getPathfinder().stopPathfinding();
            }
        }
    }

    /** Gunfire and explosions draw nearby soldiers. */
    public void alert(Location at, double radius, @Nullable Entity source) {
        if (isAi(source)) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        for (Bot bot : bots.values()) {
            if (bot.mob.getWorld() != at.getWorld() || bot.target != null) {
                continue;
            }
            if (bot.mob.getLocation().distanceSquared(at) <= radius * radius) {
                bot.alertPos = at.clone();
                bot.alertUntil = now + 20L * 12;
            }
        }
    }

    // ------------------------------------------------------------------ brain

    private void tick() {
        long now = Bukkit.getCurrentTick();
        List<UUID> dead = new ArrayList<>();
        for (Bot bot : bots.values()) {
            Zombie mob = bot.mob;
            if (!mob.isValid() || mob.isDead()) {
                dead.add(mob.getUniqueId());
                continue;
            }
            if (bot.raid.state != Raid.State.RUNNING || now < bot.stunnedUntil) {
                continue;
            }
            think(bot, now);
        }
        dead.forEach(bots::remove);
    }

    private void think(Bot bot, long now) {
        Zombie mob = bot.mob;
        Player target = acquire(bot, now);
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
                mob.getPathfinder().moveTo(target.getLocation(), 1.0);
            } else if (distance < 6 && !"sniper".equals(bot.kind) && !"shield".equals(bot.kind)) {
                Vector away = mob.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
                if (away.lengthSquared() > 0.01) {
                    mob.getPathfinder().moveTo(mob.getLocation().add(away.normalize().multiply(4)), 1.0);
                }
            } else if (now % 40 == 0 && !"sniper".equals(bot.kind) && ThreadLocalRandom.current().nextBoolean()) {
                Vector dir = target.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
                if (dir.lengthSquared() > 0.01) {
                    Vector side = new Vector(-dir.getZ(), 0, dir.getX()).normalize().multiply(ThreadLocalRandom.current().nextBoolean() ? 3 : -3);
                    mob.getPathfinder().moveTo(mob.getLocation().add(side), 1.0);
                }
            } else if (distance <= preferred) {
                mob.getPathfinder().stopPathfinding();
            }
            shoot(bot, target, distance, now);
            return;
        }
        bot.firstSeenTick = -1;
        if ("operator".equals(bot.kind) && bot.lastSeen == null && (bot.alertPos == null || now >= bot.alertUntil)) {
            operate(bot, now);
            return;
        }
        if (bot.lastSeen != null && now - bot.lastSeenTick < 20L * 10) {
            if (!"sniper".equals(bot.kind) && !mob.getPathfinder().hasPath()) {
                mob.getPathfinder().moveTo(bot.lastSeen, 0.9);
            }
            return;
        }
        if (bot.alertPos != null && now < bot.alertUntil) {
            if (!"sniper".equals(bot.kind) && !mob.getPathfinder().hasPath()) {
                Location toward = bot.alertPos.clone();
                if (toward.distanceSquared(bot.home) > Math.pow(bot.leash * 4, 2)) {
                    Vector dir = toward.toVector().subtract(bot.home.toVector()).normalize().multiply(bot.leash * 3);
                    toward = bot.home.clone().add(dir);
                }
                mob.getPathfinder().moveTo(toward, 0.9);
            }
            return;
        }
        bot.alertPos = null;
        if (now >= bot.nextWanderTick && !"sniper".equals(bot.kind)) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            double radius = "guard".equals(bot.kind) ? Math.min(4, bot.leash) : bot.leash;
            Location dest = bot.home.clone().add(rng.nextDouble(-radius, radius), 0, rng.nextDouble(-radius, radius));
            mob.getPathfinder().moveTo(dest, 0.6);
            bot.nextWanderTick = now + rng.nextInt(80, 220);
        }
        if (bot.ammoLeft < bot.gun.gun().magazine()) {
            bot.ammoLeft = bot.gun.gun().magazine();
        }
    }

    private long reactionTicks(Bot bot) {
        double base = plugin.getConfig().getDouble("ai.reaction-ticks", 14);
        return Math.round(base / Math.max(0.3, bot.accuracy) + ThreadLocalRandom.current().nextInt(0, 8));
    }

    private @Nullable Player acquire(Bot bot, long now) {
        Zombie mob = bot.mob;
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        double sight = "sniper".equals(bot.kind) ? 90 : 48;
        Vector facing = mob.getLocation().getDirection().setY(0);
        for (Player player : bot.raid.online()) {
            RaidPlayer rp = bot.raid.player(player.getUniqueId());
            if (rp == null || rp.state != RaidPlayer.State.ALIVE || !plugin.raids().canBeShot(player)
                    || now < rp.protectedUntil + plugin.getConfig().getLong("ai.insertion-grace-ticks", 240)) {
                continue;
            }
            // At night soldiers see about half as far, but a lit tactical light gives you away from beyond
            // normal sight, whichever way they face.
            boolean lit = plugin.lights().mode(player) != net.infnetwork.snowball.craftov.weapon.TacticalLight.Mode.OFF;
            double range = lit ? sight * 1.3 : bot.raid.night ? sight * 0.55 : sight;
            double d = mob.getLocation().distanceSquared(player.getLocation());
            if (d > range * range || d >= bestDist) {
                continue;
            }
            boolean known = player.getUniqueId().equals(bot.target) && now - bot.lastSeenTick < 40;
            if (lit && mob.hasLineOfSight(player)) {
                known = true;
            }
            // Hearing: sprinting carries 24 blocks and plain walking 10; sneaking (silent in vanilla too) makes no noise.
            double heard = player.isSneaking() || !moving(player) ? 0 : player.isSprinting() ? 24 : 10;
            boolean noisy = d < heard * heard;
            if (!known && d > 64) {
                Vector to = player.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
                double dot = facing.lengthSquared() < 1e-4 || to.lengthSquared() < 1e-4 ? 1 : facing.normalize().dot(to.normalize());
                if (dot < 0.2 && !noisy && now >= bot.alertUntil) {
                    continue;
                }
                if (player.isSneaking() && d > 18 * 18) {
                    continue;
                }
            }
            if (!mob.hasLineOfSight(player)) {
                if (noisy && bot.target == null) {
                    // Footsteps behind a wall: go and look.
                    bot.alertPos = player.getLocation();
                    bot.alertUntil = now + 20L * 6;
                }
                continue;
            }
            best = player;
            bestDist = d;
        }
        if (best != null) {
            if (!best.getUniqueId().equals(bot.target) || now - bot.lastSeenTick > 60) {
                bot.engagedSince = now;
            }
            bot.target = best.getUniqueId();
            bot.lastSeen = best.getLocation();
            bot.lastSeenTick = now;
        } else if (now - bot.lastSeenTick > 40) {
            bot.target = null;
        }
        return best;
    }

    private static boolean moving(Player player) {
        org.bukkit.Input input = player.getCurrentInput();
        return input.isForward() || input.isBackward() || input.isLeft() || input.isRight();
    }

    private void alertSquadmates(Bot bot, Player target) {
        for (Bot other : bots.values()) {
            if (other != bot && other.raid == bot.raid && other.target == null
                    && other.mob.getLocation().distanceSquared(bot.mob.getLocation()) < 24 * 24) {
                other.alertPos = target.getLocation();
                other.alertUntil = Bukkit.getCurrentTick() + 20L * 10;
            }
        }
    }

    private void shoot(Bot bot, Player target, double distance, long now) {
        GunSpec gun = bot.gun.gun();
        if (now < bot.reloadUntil || now < bot.nextShotTick) {
            return;
        }
        if (bot.ammoLeft <= 0) {
            bot.ammoLeft = gun.magazine();
            bot.reloadUntil = now + gun.reloadTicks() + 10;
            plugin.weapons().audio().foley(bot.mob, gun, "magout", 0.9f);
            return;
        }
        if (bot.burstLeft <= 0) {
            boolean auto = gun.modes().contains(net.infnetwork.snowball.craftov.weapon.FireMode.AUTO);
            bot.burstLeft = auto ? ThreadLocalRandom.current().nextInt(3, 7) : 1;
        }
        bot.burstLeft--;
        bot.ammoLeft--;
        Location eye = bot.mob.getEyeLocation();
        // Centre of mass, not the neck: misses should land on the body or past it, rarely the head.
        Vector aim = target.getLocation().add(0, target.isSneaking() ? 0.7 : 0.95, 0).toVector()
                .add(target.getVelocity().clone().multiply(Math.min(6, distance / 20.0)))
                .subtract(eye.toVector()).normalize();
        double moving = target.getVelocity().setY(0).lengthSquared() > 0.004 ? 1.4 : 1.0;
        double settle = 1.0 + 1.6 * Math.max(0, 1 - (now - bot.engagedSince) / 60.0);
        double spread = (2.6 + distance * 0.05) * moving * settle / Math.max(0.25, bot.accuracy);
        if ("sniper".equals(bot.kind)) {
            spread *= 0.45;
        }
        AmmoSpec ammo = bot.ammo == null ? null : bot.ammo.ammo();
        GunSpec scaled = scaledGun(gun, plugin.getConfig().getDouble("ai.damage-multiplier", 0.65) * (bot.boss ? 1.15 : 1.0));
        for (int i = 0; i < gun.pellets(); i++) {
            plugin.weapons().ballistics().fire(bot.mob, eye, Ballistics.spread(aim, spread + (gun.pellets() > 1 ? gun.hipSpread() : 0)),
                    scaled, ammo, bot.gun.name(), i == 0);
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

    private static GunSpec scaledGun(GunSpec gun, double mult) {
        return new GunSpec(gun.caliber(), gun.slot(), gun.magazine(), gun.rpm(), gun.modes(), gun.damage() * mult, gun.range(),
                gun.maxRange(), gun.hipSpread(), gun.adsSpread(), gun.recoil(), gun.recoilH(), gun.reloadTicks(), gun.zoom(),
                gun.pellets(), Math.max(1.0, gun.headMultiplier() * 0.5), gun.sound(), gun.soundVolume(), gun.moveSpeed(), gun.mount(), gun.scope(), gun.audio(), gun.action());
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        Bot bot = bots.remove(event.getEntity().getUniqueId());
        if (bot == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        Raid raid = bot.raid;
        DamageService.LastHit hit = plugin.damage().lastHit(bot.mob.getUniqueId());
        Player killer = hit == null || hit.attacker() == null ? null : Bukkit.getPlayer(hit.attacker());
        if (killer != null) {
            RaidPlayer rp = raid.player(killer.getUniqueId());
            if (rp != null) {
                rp.aiKills++;
                plugin.tasks().onAiKill(killer, bot.boss, hit.zone() == net.infnetwork.snowball.craftov.combat.HitZone.HEAD,
                        plugin.carry().isMelee(killer.getInventory().getItemInMainHand()));
            }
            killer.sendActionBar(Text.mm("<#FFC23D>✖ 击败 <white>" + Text.escape(bot.name)
                    + (hit.zone() == net.infnetwork.snowball.craftov.combat.HitZone.HEAD ? " <#FF4D4D>爆头" : "")));
            if (bot.boss) {
                plugin.raids().announce(raid, "<#FFC23D>" + Text.escape(killer.getName()) + " 击败了 <#FF4D4D>" + Text.escape(bot.name) + "<#FFC23D>！");
            }
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
        if (!gun.isEmpty() && rng.nextDouble() < (bot.boss ? 1.0 : 0.75)) {
            ItemStack copy = gun.clone();
            plugin.items().setAmmo(copy, rng.nextInt(bot.gun.gun().magazine() / 2 + 1), bot.ammo == null ? null : bot.ammo.id());
            items.add(copy);
        }
        if (bot.ammo != null) {
            items.add(plugin.items().create(bot.ammo, Math.min(bot.ammo.maxStack(), rng.nextInt(10, 40))));
        }
        for (ItemStack armor : new ItemStack[]{eq.getHelmet(), eq.getChestplate()}) {
            if (armor != null && !armor.isEmpty() && rng.nextDouble() < 0.45) {
                ItemStack copy = armor.clone();
                ItemDef def = plugin.items().defOf(copy);
                if (def != null) {
                    plugin.items().setDura(copy, Math.max(1, def.durability() * rng.nextDouble(0.2, 0.7)));
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

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        World world = event.getLocation().getWorld();
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM
                && (plugin.raids().isRaidWorld(world) || plugin.lobby().isLobby(world))) {
            event.setCancelled(true);
        }
    }

    public void onBotHurt(LivingEntity victim, @Nullable LivingEntity attacker) {
        Bot bot = bots.get(victim.getUniqueId());
        if (bot != null && attacker instanceof Player player) {
            bot.target = player.getUniqueId();
            bot.lastSeen = player.getLocation();
            bot.lastSeenTick = Bukkit.getCurrentTick();
            bot.alertPos = player.getLocation();
            bot.alertUntil = Bukkit.getCurrentTick() + 200;
        }
    }
}
