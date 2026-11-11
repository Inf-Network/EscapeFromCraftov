package net.infnetwork.snowball.craftov.combat;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
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

/** Ballistic damage: falloff, hit zones, armor penetration, durability and injuries. */
public final class DamageService implements Listener {

    /** Who last hurt an entity, for kill credit and kill feed. */
    public record LastHit(@Nullable UUID attacker, String attackerName, String weapon, HitZone zone, BodyPart part, long tick) {
    }

    /** One hit taken, kept for the death report. */
    public record HitRecord(String attacker, String weapon, BodyPart part, double damage, int distance) {
    }

    private final CraftovPlugin plugin;
    private final Map<UUID, LastHit> lastHits = new HashMap<>();
    private final Map<UUID, java.util.List<HitRecord>> hitsTaken = new HashMap<>();
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

    public void recordHit(LivingEntity victim, @Nullable LivingEntity attacker, String weapon, BodyPart part) {
        lastHits.put(victim.getUniqueId(), new LastHit(attacker == null ? null : attacker.getUniqueId(),
                attacker == null ? "未知" : displayName(attacker), weapon, part.zone(), part, Bukkit.getCurrentTick()));
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
        double falloff = 1.0;
        if (distance > gun.range()) {
            double over = (distance - gun.range()) / Math.max(1.0, gun.maxRange() - gun.range());
            falloff = Math.max(0.5, 1.0 - over * 0.5);
        }
        double base = gun.damage() * (ammo == null ? 1.0 : ammo.damageMult()) * falloff;
        double zoneMult = switch (part) {
            case HEAD -> gun.headMultiplier();
            case CHEST -> 1.0;
            case STOMACH -> 0.95;
            case LEFT_ARM, RIGHT_ARM -> 0.65;
            case LEFT_LEG, RIGHT_LEG -> 0.7;
        };
        int pen = ammo == null ? 1 : ammo.penetration();
        double armorMult = 1.0;
        boolean penetrated = true;
        EntityEquipment equipment = victim.getEquipment();
        if (equipment != null && (zone == HitZone.HEAD || part.armored())) {
            ItemStack armorStack = zone == HitZone.HEAD ? equipment.getHelmet() : equipment.getChestplate();
            ItemDef armor = plugin.items().defOf(armorStack);
            if (armor != null && armor.isArmor()) {
                double dura = ItemFactory.dura(armorStack, armor.durability());
                if (dura > 0) {
                    int diff = pen - armor.armorClass();
                    armorMult = diff >= 1 ? 1.0 : diff == 0 ? 0.8 : diff == -1 ? 0.5 : diff == -2 ? 0.3 : 0.18;
                    penetrated = diff >= 0;
                    double loss = base * (ammo == null ? 1.0 : ammo.armorDamage()) * (penetrated ? 0.3 : 0.2);
                    if (victim instanceof Player p) {
                        loss *= plugin.operators().armorWearMultiplier(p);
                    }
                    double newDura = Math.max(0, dura - loss);
                    ItemStack updated = armorStack.clone();
                    plugin.items().setDura(updated, newDura);
                    if (zone == HitZone.HEAD) {
                        equipment.setHelmet(updated);
                    } else {
                        equipment.setChestplate(updated);
                    }
                    if (newDura <= 0 && victim instanceof Player p) {
                        p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BREAK, SoundCategory.PLAYERS, 1f, 0.8f);
                    }
                    victim.getWorld().playSound(victim.getLocation(), "craftov:hit.armor", SoundCategory.PLAYERS, 1.2f,
                            penetrated ? 1.0f : 1.25f);
                }
            }
        }
        double damage = base * zoneMult * armorMult;
        if (victim instanceof Player target && penetrated && zone != HitZone.HEAD) {
            plugin.health().rollInjuries(target, part, damage);
        }
        recordHit(victim, shooter, weaponName, part);
        if (victim instanceof Player) {
            hitsTaken.computeIfAbsent(victim.getUniqueId(), k -> new java.util.ArrayList<>())
                    .add(new HitRecord(shooter == null ? "未知" : displayName(shooter), weaponName, part, damage, (int) Math.round(distance)));
        }
        deal(victim, shooter, damage);
        plugin.ai().onBotHurt(victim, shooter);
        if (shooter instanceof Player player) {
            feedback(player, victim, damage, zone);
        }
        return damage;
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

    private void feedback(Player shooter, LivingEntity victim, double damage, HitZone zone) {
        boolean lethal = victim.isDead() || victim.getHealth() <= 0
                || (victim instanceof Player p && plugin.downed().isDowned(p));
        String sound = lethal ? "craftov:hit.kill" : zone == HitZone.HEAD ? "craftov:hit.head" : "craftov:hit.body";
        shooter.playSound(shooter.getLocation(), sound, SoundCategory.PLAYERS, 0.9f, 1f);
        plugin.hud().hitMarker(shooter, zone == HitZone.HEAD, lethal);
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

    /** A knife hit: AI die in a fixed number of hits per class, players take flat damage. */
    private void melee(Player player, LivingEntity victim, ItemDef def) {
        net.infnetwork.snowball.craftov.weapon.MeleeSpec spec = def.melee();
        long now = org.bukkit.Bukkit.getCurrentTick();
        if (now - lastMelee.getOrDefault(player.getUniqueId(), -1000L) < spec.cooldownTicks()
                || player.getEyeLocation().distance(victim.getLocation().add(0, victim.getHeight() / 2, 0)) > spec.reach() + 0.8
                || !plugin.raids().canFire(player)) {
            return;
        }
        lastMelee.put(player.getUniqueId(), now);
        String cls = plugin.ai().meleeClass(victim);
        double damage;
        if (cls != null) {
            int hits = switch (cls) {
                case "boss" -> spec.hitsBoss();
                case "special" -> spec.hitsSpecial();
                default -> spec.hitsNormal();
            };
            org.bukkit.attribute.AttributeInstance max = victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            damage = (max == null ? victim.getHealth() : max.getValue()) / Math.max(1, hits) + 0.01;
        } else if (victim instanceof Player) {
            damage = spec.playerDamage();
        } else {
            return;
        }
        recordHit(victim, player, def.name(), BodyPart.CHEST);
        if (victim instanceof Player) {
            hitsTaken.computeIfAbsent(victim.getUniqueId(), k -> new java.util.ArrayList<>())
                    .add(new HitRecord(player.getName(), def.name(), BodyPart.CHEST, damage, 1));
        }
        deal(victim, player, damage);
        plugin.ai().onBotHurt(victim, player);
        victim.getWorld().playSound(victim.getLocation(), "minecraft:entity.player.attack.sweep", SoundCategory.PLAYERS, 1f, 1.2f);
        feedback(player, victim, damage, HitZone.TORSO);
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
