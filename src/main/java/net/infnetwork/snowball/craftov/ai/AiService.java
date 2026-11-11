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
        final Location home;
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

    /** Melee class of an AI: "boss", "special" (guards, snipers, escorts) or "normal", or null if not AI. */
    public @Nullable String meleeClass(Entity entity) {
        Bot bot = bots.get(entity.getUniqueId());
        if (bot == null) {
            return null;
        }
        return bot.boss ? "boss" : "patrol".equals(bot.kind) ? "normal" : "special";
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
                spawn(raid, at, def.radius(), def.kind(), raid.tier.aiGear(), false, NAMES[rng.nextInt(NAMES.length)]);
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
        int armorLevel = Math.min(6, Math.max(0, (boss ? 5 : gear + 1) + rng.nextInt(-1, 2)));
        ItemDef finalAmmo = ammo;
        double accuracy = raid.tier.aiAccuracy() * (boss ? 1.15 : "sniper".equals(kind) ? 1.1 : 1.0);
        Zombie mob = raid.world.spawn(at, Zombie.class, CreatureSpawnEvent.SpawnReason.CUSTOM, z -> {
            z.setLeftHanded(false); // the gun model's third-person pose is tuned for the right hand
            z.setAdult();
            z.setShouldBurnInDay(false);
            z.setCanPickupItems(false);
            z.setPersistent(false);
            z.setRemoveWhenFarAway(false);
            z.setSilent(true);
            z.setCanBreakDoors(false);
            z.setMaximumNoDamageTicks(0);
            EntityEquipment eq = z.getEquipment();
            eq.setItem(EquipmentSlot.HAND, plugin.items().loadedGun(gun, finalAmmo == null ? null : finalAmmo.id()));
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
            setAttr(z, Attribute.MAX_HEALTH, boss ? 380 : "sniper".equals(kind) ? 80 : 100);
            z.setHealth(boss ? 380 : "sniper".equals(kind) ? 80 : 100);
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
            double preferred = "sniper".equals(bot.kind) ? 999 : bot.gun.gun().range() * 0.8;
            if (distance > preferred && !"sniper".equals(bot.kind)) {
                mob.getPathfinder().moveTo(target.getLocation(), 1.0);
            } else if (distance < 6 && !"sniper".equals(bot.kind)) {
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
        double range = "sniper".equals(bot.kind) ? 90 : 48;
        Vector facing = mob.getLocation().getDirection().setY(0);
        for (Player player : bot.raid.online()) {
            RaidPlayer rp = bot.raid.player(player.getUniqueId());
            if (rp == null || rp.state != RaidPlayer.State.ALIVE || !plugin.raids().canBeShot(player)
                    || now < rp.protectedUntil + plugin.getConfig().getLong("ai.insertion-grace-ticks", 240)) {
                continue;
            }
            double d = mob.getLocation().distanceSquared(player.getLocation());
            if (d > range * range || d >= bestDist) {
                continue;
            }
            boolean known = player.getUniqueId().equals(bot.target) && now - bot.lastSeenTick < 40;
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
        plugin.weapons().audio().shot(bot.mob, Ballistics.muzzlePoint(bot.mob, aim), gun, false);
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
        Location at = bot.mob.getLocation();
        LootContainer container = new LootContainer(LootContainer.newId(), type, at, 0, bot.name + " 的遗物");
        container.fill(items, false);
        container.spawn(at.getYaw());
        bot.raid.containers.put(container.id, container);
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
