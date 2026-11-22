package net.infnetwork.snowball.craftov.combat;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.weapon.AmmoSpec;
import net.infnetwork.snowball.craftov.weapon.GunSpec;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ballistic damage: falloff, hit zones, armor penetration, durability and injuries.
 *
 * <p>A helmet covers the head and a vest the chest and stomach. How much of a hit gets through
 * depends on the round's penetration minus the armour's class ({@code combat.armor.damage-taken}):
 * armour always takes something off, even against a stronger round, and a weaker round is mostly
 * stopped. Every hit wears the armour, and worn armour protects less: above half its durability
 * ({@code combat.armor.full-protection-above}) it works fully, below that its protection fades
 * linearly to nothing, and the part of a hit that wears more than the durability left goes straight
 * through. A round the class stops cannot make the chest or stomach bleed while the vest is at
 * full strength; once it is giving way, it may.
 *
 * <p>Knives go through the same armour with their own damage and penetration (items.yml
 * {@code melee}), land where the blade meets the target's box, and are stopped by a shield from the
 * front like a bullet.
 */
public final class DamageService implements Listener {

    /**
     * Damage multipliers by penetration minus armour class when armour is at full strength: +1 or
     * more, 0, -1, -2, -3 or less. {@code combat.armor.damage-taken} overrides it.
     */
    private static final double[] DAMAGE_TAKEN = {0.85, 0.6, 0.4, 0.25, 0.15};
    /** Default share of durability above which armour protects fully. */
    public static final double FULL_PROTECTION_ABOVE = 0.5;
    /** Default durability lost per point of a hit's damage, times the round's armor-damage. */
    private static final double WEAR = 0.3;

    /**
     * What the armour covering a part did to one hit: the share of its damage that gets through,
     * whether it reached the body (for bleeding), and whether there was armour to strike at all.
     */
    private record ArmorHit(double damageMult, boolean penetrated, boolean armored) {
        static final ArmorHit NONE = new ArmorHit(1.0, true, false);
    }

    /** Who last hurt an entity, for kill credit and kill feed; {@code melee} when it was a knife cut. */
    public record LastHit(@Nullable UUID attacker, String attackerName, String weapon, HitZone zone, BodyPart part, long tick,
                          boolean melee) {
        /**
         * A shot to the head. A knife cut that lands on the head is not one: a level swing at a
         * standing target lands there, so counting it would hand out headshot credit for most knife kills.
         */
        public boolean headshot() {
            return zone == HitZone.HEAD && !melee;
        }
    }

    /** What one hit did to its target, for the shooter's hit sound and marker. */
    public enum Outcome { HIT, DOWNED, KILLED }

    /** One hit taken, kept for the death report. */
    public record HitRecord(String attacker, String weapon, BodyPart part, double damage, int distance) {
    }

    private final CraftovPlugin plugin;
    private final Map<UUID, LastHit> lastHits = new HashMap<>();
    private final Map<UUID, java.util.List<HitRecord>> hitsTaken = new HashMap<>();
    /** Shooter -> tick of their last knock-down or kill marker. */
    private final Map<UUID, Long> strongMarks = new HashMap<>();
    private boolean applying;

    public DamageService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public @Nullable LastHit lastHit(UUID victim) {
        LastHit hit = lastHits.get(victim);
        if (hit == null || Bukkit.getCurrentTick() - hit.tick() > 20 * 30) {
            return null;
        }
        return hit;
    }

    public void forget(UUID victim) {
        lastHits.remove(victim);
        hitsTaken.remove(victim);
    }

    /** Hits a player took since their last {@link #forget}, oldest first. */
    public java.util.List<HitRecord> hitsTaken(UUID victim) {
        return java.util.List.copyOf(hitsTaken.getOrDefault(victim, java.util.List.of()));
    }

    public void recordHit(LivingEntity victim, @Nullable LivingEntity attacker, String weapon, HitZone zone) {
        recordHit(victim, attacker, weapon, BodyPart.defaultFor(zone));
    }

    /**
     * Records who hurt the victim and with what. Damage nobody dealt ({@code attacker} null: a fall,
     * shelling, your own grenade) only names the cause: whoever hurt the victim in the last 30
     * seconds keeps the credit, so a fall or a shell cannot take a kill away from them, and the
     * death reads "killed by them (fall)". It does not restart their 30 seconds; the bleeding of a
     * wound they made does ({@link #recordBleed}).
     */
    public void recordHit(LivingEntity victim, @Nullable LivingEntity attacker, String weapon, BodyPart part) {
        recordHit(victim, attacker, weapon, part, false, false);
    }

    /**
     * Wounds bleeding, made by {@code sources}: like a hit nobody dealt, and when the victim's last
     * attacker made one of them, it also restarts their 30 seconds. A player shot and left bleeding
     * who runs off and bleeds out is the shooter's kill, however long it takes. A wound someone else
     * made (an earlier attacker, a soldier) keeps nobody's credit going: a later attacker whose
     * round only struck armour must not ride it to a kill minutes on.
     */
    public void recordBleed(LivingEntity victim, String weapon, BodyPart part, java.util.Set<UUID> sources) {
        LastHit last = lastHit(victim.getUniqueId());
        boolean theirs = last != null && last.attacker() != null && sources.contains(last.attacker());
        recordHit(victim, null, weapon, part, false, theirs);
    }

    private void recordHit(LivingEntity victim, @Nullable LivingEntity attacker, String weapon, BodyPart part, boolean melee,
                           boolean renews) {
        long now = Bukkit.getCurrentTick();
        LastHit last = attacker == null ? lastHit(victim.getUniqueId()) : null;
        if (last != null && last.attacker() != null) {
            lastHits.put(victim.getUniqueId(), new LastHit(last.attacker(), last.attackerName(), weapon, part.zone(), part,
                    renews ? now : last.tick(), false));
            return;
        }
        lastHits.put(victim.getUniqueId(), new LastHit(attacker == null ? null : attacker.getUniqueId(),
                attacker == null ? "未知" : displayName(attacker), weapon, part.zone(), part, now, melee));
    }

    public static String displayName(Entity entity) {
        if (entity instanceof Player player) {
            return player.getName();
        }
        String ai = CraftovPlugin.get().ai().nameOf(entity);
        if (ai != null) {
            return ai;
        }
        Component custom = entity.customName();
        return custom == null ? entity.getName() : net.infnetwork.snowball.craftov.util.Text.plain(custom);
    }

    /**
     * Applies one bullet. Returns the damage dealt (after armor), or -1 when the hit was ignored.
     */
    public double bullet(@Nullable LivingEntity shooter, LivingEntity victim, BodyPart part, GunSpec gun,
                         @Nullable AmmoSpec ammo, double distance, String weaponName) {
        HitZone zone = part.zone();
        if (plugin.ai().shieldBlocks(victim, shooter, part)) {
            shieldHit(victim, shooter);
            return 0;
        }
        double falloff = 1.0;
        if (distance > gun.range()) {
            double over = (distance - gun.range()) / Math.max(1.0, gun.maxRange() - gun.range());
            falloff = Math.max(0.5, 1.0 - over * 0.5);
        }
        double base = gun.damage() * (ammo == null ? 1.0 : ammo.damageMult()) * falloff;
        double zoneMult = zoneMultiplier(part, gun.headMultiplier());
        ArmorHit armorHit = armor(victim, part, base, ammo == null ? 1 : ammo.penetration(),
                ammo == null ? 1.0 : ammo.armorDamage());
        double damage = base * zoneMult * armorHit.damageMult();
        if (victim instanceof Player target && armorHit.penetrated() && zone != HitZone.HEAD) {
            plugin.health().rollInjuries(target, part, damage, shooter);
        }
        recordHit(victim, shooter, weaponName, part);
        if (victim instanceof Player) {
            hitsTaken.computeIfAbsent(victim.getUniqueId(), k -> new java.util.ArrayList<>())
                    .add(new HitRecord(shooter == null ? "未知" : displayName(shooter), weaponName, part, damage, (int) Math.round(distance)));
        }
        RaidPlayer.@Nullable State before = stateOf(victim);
        deal(victim, shooter, damage);
        Outcome outcome = outcome(victim, before);
        if (victim instanceof Player hurt && shooter != null) {
            plugin.hud().damageFrom(hurt, shooter.getLocation());
        }
        plugin.ai().onBotHurt(victim, shooter);
        if (shooter instanceof Player player) {
            feedback(player, victim, damage, zone, outcome);
        }
        return damage;
    }

    /** A ballistic shield takes the hit: sparks and a ring of steel, nothing gets through. */
    private void shieldHit(LivingEntity victim, @Nullable LivingEntity attacker) {
        Location face = victim.getEyeLocation().subtract(0, 0.5, 0)
                .add(victim.getLocation().getDirection().setY(0).normalize().multiply(0.45));
        victim.getWorld().spawnParticle(org.bukkit.Particle.ELECTRIC_SPARK, face, 8, 0.15, 0.2, 0.15, 0.3);
        victim.getWorld().playSound(face, "craftov:impact.metal", SoundCategory.PLAYERS, 1f,
                0.9f + ThreadLocalRandom.current().nextFloat() * 0.2f);
        plugin.ai().onBotHurt(victim, attacker);
    }

    /**
     * Runs one hit of {@code base} damage with penetration {@code pen} through the helmet (head) or
     * vest (chest, stomach) covering the part, wears the armour by {@code armorDamage}, and plays the
     * impact: armour, flesh, or both when it gets through. Bullets and knives share it.
     */
    private ArmorHit armor(LivingEntity victim, BodyPart part, double base, int pen, double armorDamage) {
        ArmorHit hit = wearArmor(victim, part, base, pen, armorDamage);
        plugin.weapons().audio().bodyImpact(victim, part == BodyPart.HEAD, hit.armored(), hit.penetrated());
        return hit;
    }

    /** {@link #armor} without the sound; {@link ArmorHit#NONE} when nothing, or only broken armour, covers the part. */
    private ArmorHit wearArmor(LivingEntity victim, BodyPart part, double base, int pen, double armorDamage) {
        boolean head = part == BodyPart.HEAD;
        EntityEquipment equipment = victim.getEquipment();
        if (equipment == null || !(head || part.armored())) {
            return ArmorHit.NONE;
        }
        ItemStack armorStack = head ? equipment.getHelmet() : equipment.getChestplate();
        ItemDef armor = plugin.items().defOf(armorStack);
        if (armor == null || !armor.isArmor()) {
            return ArmorHit.NONE;
        }
        double dura = ItemFactory.dura(armorStack, armor.durability());
        if (dura <= 0) {
            return ArmorHit.NONE;
        }
        int diff = pen - armor.armorClass();
        boolean overmatched = diff >= 0;
        double wear = plugin.getConfig().getDouble(overmatched ? "combat.armor.wear-penetrated" : "combat.armor.wear-blocked", WEAR);
        double loss = base * armorDamage * wear;
        if (victim instanceof Player p) {
            loss *= plugin.operators().armorWearMultiplier(p);
        }
        double strength = armorStrength(dura, armor.durability(), loss,
                plugin.getConfig().getDouble("combat.armor.full-protection-above", FULL_PROTECTION_ABOVE));
        double damageMult = 1.0 - (1.0 - damageTaken(diff)) * strength;
        // A round the class stops can still wound through armour that is giving way.
        boolean penetrated = overmatched || ThreadLocalRandom.current().nextDouble() >= strength;

        double newDura = Math.max(0, dura - loss);
        ItemStack updated = armorStack.clone();
        plugin.items().setDura(updated, newDura);
        if (head) {
            equipment.setHelmet(updated);
        } else {
            equipment.setChestplate(updated);
        }
        if (newDura <= 0 && victim instanceof Player p) {
            p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BREAK, SoundCategory.PLAYERS, 1f, 0.8f);
        }
        return new ArmorHit(damageMult, penetrated, true);
    }

    /**
     * How much of its protection a piece of armour still gives, 0..1: all of it above {@code fullAbove}
     * of its durability, fading linearly to none at 0, and only the share of this hit's {@code wear}
     * that the durability left can absorb (the rest goes straight through).
     */
    public static double armorStrength(double dura, double maxDura, double wear, double fullAbove) {
        if (dura <= 0 || maxDura <= 0) {
            return 0;
        }
        double condition = fullAbove <= 0 ? 1.0 : Math.min(1.0, dura / maxDura / fullAbove);
        double absorbed = wear <= 0 ? 1.0 : Math.min(1.0, dura / wear);
        return condition * absorbed;
    }

    /**
     * Damage multiplier for where a hit lands: the weapon's own head multiplier, otherwise
     * combat.zone-multipliers. Limbs take less because they share the one health pool with the
     * torso and are a big, unarmoured share of the hitbox (legs alone are 40% of it).
     */
    private double zoneMultiplier(BodyPart part, double head) {
        return switch (part) {
            case HEAD -> head;
            case CHEST -> plugin.getConfig().getDouble("combat.zone-multipliers.chest", 1.0);
            case STOMACH -> plugin.getConfig().getDouble("combat.zone-multipliers.stomach", 0.95);
            case LEFT_ARM, RIGHT_ARM -> plugin.getConfig().getDouble("combat.zone-multipliers.arm", 0.65);
            case LEFT_LEG, RIGHT_LEG -> plugin.getConfig().getDouble("combat.zone-multipliers.leg", 0.7);
        };
    }

    /**
     * {@link #zoneMultiplier} for a knife: the arms and legs take combat.melee.zone-multipliers, less
     * than a bullet does. No armour covers them, so at a bullet's 0.65 / 0.7 the machete downed a
     * player in any armour with three limb cuts; at 0.6 it takes four, as many leg shots as a K47 with
     * PS. The head and torso are the same as for a bullet.
     */
    private double meleeZoneMultiplier(BodyPart part, double head) {
        return switch (part) {
            case LEFT_ARM, RIGHT_ARM -> plugin.getConfig().getDouble("combat.melee.zone-multipliers.arm", 0.6);
            case LEFT_LEG, RIGHT_LEG -> plugin.getConfig().getDouble("combat.melee.zone-multipliers.leg", 0.6);
            default -> zoneMultiplier(part, head);
        };
    }

    /** Damage multiplier of full-strength armour for this penetration minus class (combat.armor.damage-taken). */
    private double damageTaken(int diff) {
        int index = Math.max(0, 1 - diff);
        java.util.List<Double> table = plugin.getConfig().getDoubleList("combat.armor.damage-taken");
        double value = table.isEmpty() ? DAMAGE_TAKEN[Math.min(index, DAMAGE_TAKEN.length - 1)]
                : table.get(Math.min(index, table.size() - 1));
        return Math.max(0, Math.min(1, value));
    }

    /** Deals raw damage without knockback or invulnerability frames. */
    public void deal(LivingEntity victim, @Nullable LivingEntity attacker, double amount) {
        if (amount <= 0 || victim.isDead()) {
            return;
        }
        victim.setNoDamageTicks(0);
        DamageSource.Builder builder = DamageSource.builder(attacker instanceof Player ? DamageType.PLAYER_ATTACK
                : attacker != null ? DamageType.MOB_ATTACK : DamageType.GENERIC);
        if (attacker != null) {
            builder.withCausingEntity(attacker).withDirectEntity(attacker);
        }
        applying = true;
        try {
            victim.damage(amount, builder.build());
        } finally {
            applying = false;
        }
        victim.setNoDamageTicks(0);
    }

    public boolean isApplying() {
        return applying;
    }

    /** A raid player's state before a hit, for {@link #outcome}; null for anyone else. */
    private RaidPlayer.@Nullable State stateOf(LivingEntity victim) {
        RaidPlayer rp = victim instanceof Player player ? plugin.raids().session(player) : null;
        return rp == null ? null : rp.state;
    }

    /**
     * What a hit just did, from the victim's state {@code before} it and now. A raid player is
     * downed (standing before, crawling now) or killed (active before, dead now); a hit on someone
     * already crawling is a plain hit unless it finishes them. Anyone else is killed when dead.
     */
    private Outcome outcome(LivingEntity victim, RaidPlayer.@Nullable State before) {
        RaidPlayer rp = before != null && victim instanceof Player player ? plugin.raids().session(player) : null;
        if (rp != null) {
            if (rp.state == RaidPlayer.State.DEAD && before != RaidPlayer.State.DEAD) {
                return Outcome.KILLED;
            }
            return rp.state == RaidPlayer.State.DOWNED && before == RaidPlayer.State.ALIVE ? Outcome.DOWNED : Outcome.HIT;
        }
        return victim.isDead() || victim.getHealth() <= 0 ? Outcome.KILLED : Outcome.HIT;
    }

    /**
     * The shooter's hit sound and marker, by what the hit did: the kill chime and red cross only for
     * the hit that kills, the knock-down sound and ring for the one that downs, and the plain body or
     * head hit otherwise, shots at a crawling player included.
     */
    private void feedback(Player shooter, LivingEntity victim, double damage, HitZone zone, Outcome outcome) {
        long now = Bukkit.getCurrentTick();
        // The rest of a shotgun blast lands the same tick: plain hits on the now crawling body must
        // not drown out the knock-down that came first.
        boolean covered = outcome == Outcome.HIT && strongMarks.getOrDefault(shooter.getUniqueId(), -1L) == now;
        if (outcome != Outcome.HIT) {
            strongMarks.put(shooter.getUniqueId(), now);
        }
        if (!covered) {
            String sound = switch (outcome) {
                case KILLED -> "craftov:hit.kill";
                case DOWNED -> "craftov:hit.knock";
                case HIT -> zone == HitZone.HEAD ? "craftov:hit.head" : "craftov:hit.body";
            };
            shooter.playSound(shooter.getLocation(), sound, SoundCategory.PLAYERS, 0.9f, 1f);
            plugin.hud().hitMarker(shooter, zone == HitZone.HEAD, outcome);
        }
        if (!plugin.getConfig().getBoolean("combat.damage-numbers", true)) {
            return;
        }
        Location at = victim.getEyeLocation().add((ThreadLocalRandom.current().nextDouble() - 0.5) * 0.6, 0.35,
                (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.6);
        String color = zone == HitZone.HEAD ? "#FF4D4D" : "#FFFFFF";
        TextDisplay text = victim.getWorld().spawn(at, TextDisplay.class, d -> {
            d.text(net.infnetwork.snowball.craftov.util.Text.mm("<" + color + "><b>" + Math.round(damage)));
            d.setBillboard(Display.Billboard.CENTER);
            d.setSeeThrough(true);
            d.setShadowed(true);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.setTeleportDuration(10);
        });
        shooter.showEntity(plugin, text);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (text.isValid()) {
                text.teleport(text.getLocation().add(0, 0.5, 0));
            }
        }, 2L);
        Bukkit.getScheduler().runTaskLater(plugin, text::remove, 14L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent event) {
        if (applying) {
            event.setCancelled(true);
        }
    }

    private final java.util.Map<UUID, Long> lastMelee = new java.util.HashMap<>();

    /**
     * A knife hit. It lands on the body part where the blade meets the victim's box, a shield in
     * front stops it, and otherwise it goes through the helmet or vest on that part like a round
     * with the knife's damage and penetration: armour takes off what its class stops and wears.
     *
     * <p>The blade follows the attacker's view, so a level swing at a standing target lands on its
     * head: knives get no head bonus (the machete less than its full damage, items.yml {@code head})
     * and a helmet takes the cut, and a bare head goes down in as many cuts as a bare chest.
     */
    private void melee(Player player, LivingEntity victim, ItemDef def) {
        net.infnetwork.snowball.craftov.weapon.MeleeSpec spec = def.melee();
        long now = Bukkit.getCurrentTick();
        Location eye = player.getEyeLocation();
        if (now - lastMelee.getOrDefault(player.getUniqueId(), -1000L) < spec.cooldownTicks()
                || !eye.getWorld().equals(victim.getWorld())
                || eye.distance(victim.getLocation().add(0, victim.getHeight() / 2, 0)) > spec.reach() + 0.8
                || !plugin.raids().canFire(player)
                || !(victim instanceof Player || plugin.ai().isAi(victim))
                || (victim instanceof Player target && !plugin.raids().canBeShot(target))
                || !plugin.raids().canDamage(player, victim)) {
            return;
        }
        lastMelee.put(player.getUniqueId(), now);
        org.bukkit.util.Vector dir = eye.getDirection();
        org.bukkit.util.Vector point = meleePoint(eye, dir, victim, spec.reach() + 0.8);
        BodyPart part = BodyPart.of(victim, point, dir);
        victim.getWorld().playSound(victim.getLocation(), "minecraft:entity.player.attack.sweep", SoundCategory.PLAYERS, 1f, 1.2f);
        if (plugin.ai().shieldBlocks(victim, player, part)) {
            shieldHit(victim, player);
            return;
        }
        double base = spec.damage();
        ArmorHit armorHit = armor(victim, part, base, spec.penetration(), spec.armorDamage());
        double damage = base * meleeZoneMultiplier(part, spec.headMultiplier()) * armorHit.damageMult();
        if (victim instanceof Player target && armorHit.penetrated() && part != BodyPart.HEAD) {
            plugin.health().rollInjuries(target, part, damage, player);
        }
        recordHit(victim, player, def.name(), part, true, false);
        if (victim instanceof Player) {
            hitsTaken.computeIfAbsent(victim.getUniqueId(), k -> new java.util.ArrayList<>())
                    .add(new HitRecord(player.getName(), def.name(), part, damage, (int) Math.round(eye.toVector().distance(point))));
        }
        RaidPlayer.@Nullable State before = stateOf(victim);
        deal(victim, player, damage);
        Outcome outcome = outcome(victim, before);
        if (victim instanceof Player hurt) {
            plugin.hud().damageFrom(hurt, player.getLocation());
        }
        plugin.ai().onBotHurt(victim, player);
        feedback(player, victim, damage, part.zone(), outcome);
    }

    /**
     * Where a swing from {@code eye} along {@code dir} meets the victim's box. The client picked the
     * victim along its own view, which the server may see a little off, so a miss falls back to the
     * point of the box nearest the line of the swing at the victim's distance.
     */
    private static org.bukkit.util.Vector meleePoint(Location eye, org.bukkit.util.Vector dir, LivingEntity victim, double reach) {
        org.bukkit.util.BoundingBox box = victim.getBoundingBox();
        org.bukkit.util.Vector from = eye.toVector();
        org.bukkit.util.RayTraceResult hit = box.rayTrace(from, dir, reach);
        if (hit != null) {
            return hit.getHitPosition();
        }
        org.bukkit.util.Vector along = from.clone().add(dir.clone().multiply(from.distance(box.getCenter())));
        return new org.bukkit.util.Vector(
                Math.max(box.getMinX(), Math.min(box.getMaxX(), along.getX())),
                Math.max(box.getMinY(), Math.min(box.getMaxY(), along.getY())),
                Math.max(box.getMinZ(), Math.min(box.getMaxZ(), along.getZ())));
    }

    /** Guns and tactical items are not melee weapons; punches do token damage only. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent event) {
        if (applying) {
            return;
        }
        if (event.getDamager() instanceof Player player && event.getEntity() instanceof LivingEntity) {
            ItemDef def = plugin.items().defOf(player.getInventory().getItemInMainHand());
            if (def != null && def.isGun()) {
                event.setCancelled(true);
                return;
            }
            if (def != null && def.isMelee() && event.getEntity() instanceof LivingEntity victim) {
                event.setCancelled(true);
                melee(player, victim, def);
                return;
            }
            event.setDamage(Math.min(event.getDamage(), 4.0));
        }
    }
}
