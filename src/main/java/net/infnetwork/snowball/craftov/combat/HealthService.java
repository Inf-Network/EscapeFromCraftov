package net.infnetwork.snowball.craftov.combat;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 100 HP model, injuries (bleeding / fractures), medical items and movement speed. */
public final class HealthService implements Listener {

    public static final double MAX_HP = 100.0;
    private static final NamespacedKey SPEED_KEY = new NamespacedKey("craftov", "speed");

    private final CraftovPlugin plugin;
    private final Map<UUID, Set<Injury>> injuries = new HashMap<>();
    private final Map<UUID, Long> painkillerUntil = new HashMap<>();
    private final Map<UUID, Long> adrenalineUntil = new HashMap<>();
    private final Map<UUID, Double> lastSpeed = new HashMap<>();

    public HealthService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickInjuries, 30L, 30L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickSpeed, 5L, 5L);
    }

    // ------------------------------------------------------------------ setup

    public void setupPlayer(Player player, boolean heal) {
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) {
            max.setBaseValue(MAX_HP);
        }
        player.setHealthScale(20.0);
        player.setHealthScaled(true);
        if (heal) {
            player.setHealth(MAX_HP);
            clear(player);
        }
        player.setFoodLevel(20);
        player.setSaturation(5f);
        player.setFireTicks(0);
        player.setMaximumNoDamageTicks(0);
    }

    public void clear(Player player) {
        injuries.remove(player.getUniqueId());
        painkillerUntil.remove(player.getUniqueId());
        adrenalineUntil.remove(player.getUniqueId());
        player.setFoodLevel(20);
    }

    public Set<Injury> injuries(Player player) {
        return injuries.getOrDefault(player.getUniqueId(), EnumSet.noneOf(Injury.class));
    }

    public boolean hasInjury(Player player, Injury injury) {
        return injuries(player).contains(injury);
    }

    public void addInjury(Player player, Injury injury) {
        Set<Injury> set = injuries.computeIfAbsent(player.getUniqueId(), k -> EnumSet.noneOf(Injury.class));
        if (set.add(injury)) {
            player.sendActionBar(Text.mm("<" + injury.hex() + ">⚠ " + injury.displayName()));
            player.playSound(player.getLocation(), "craftov:status.injury", SoundCategory.PLAYERS, 0.8f, 1f);
        }
    }

    public void cure(Player player, Injury injury) {
        Set<Injury> set = injuries.get(player.getUniqueId());
        if (set != null) {
            set.remove(injury);
        }
    }

    public void rollInjuries(Player player, HitZone zone, double damage) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double scale = Math.min(2.0, damage / 30.0);
        if (rng.nextDouble() < 0.06 * scale) {
            addInjury(player, Injury.HEAVY_BLEED);
        } else if (rng.nextDouble() < 0.22 * scale) {
            addInjury(player, Injury.LIGHT_BLEED);
        }
        if (zone == HitZone.LEGS && rng.nextDouble() < 0.2 * scale) {
            addInjury(player, Injury.FRACTURE);
        }
    }

    public boolean painkilled(Player player) {
        return painkillerUntil.getOrDefault(player.getUniqueId(), 0L) > System.currentTimeMillis();
    }

    public boolean adrenaline(Player player) {
        return adrenalineUntil.getOrDefault(player.getUniqueId(), 0L) > System.currentTimeMillis();
    }

    public void grantAdrenaline(Player player, int seconds) {
        adrenalineUntil.put(player.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
    }

    public void heal(Player player, double amount) {
        if (player.isDead()) {
            return;
        }
        player.setHealth(Math.min(MAX_HP, player.getHealth() + amount));
    }

    // ------------------------------------------------------------------ ticking

    private void tickInjuries() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Set<Injury> set = injuries.get(player.getUniqueId());
            if (set == null || set.isEmpty() || !plugin.raids().isInRaid(player) || plugin.downed().isDowned(player)) {
                continue;
            }
            double bleed = 0;
            if (set.contains(Injury.HEAVY_BLEED)) {
                bleed += 3.0;
            }
            if (set.contains(Injury.LIGHT_BLEED)) {
                bleed += 1.2;
            }
            if (bleed > 0) {
                plugin.damage().recordHit(player, null, "失血", HitZone.TORSO);
                plugin.damage().deal(player, null, bleed);
                player.getWorld().spawnParticle(Particle.BLOCK, player.getLocation().add(0, 0.8, 0), 6, 0.2, 0.3, 0.2,
                        org.bukkit.Material.REDSTONE_BLOCK.createBlockData());
            }
            boolean fracture = set.contains(Injury.FRACTURE) && !painkilled(player);
            player.setFoodLevel(fracture ? 6 : 20);
        }
    }

    private void tickSpeed() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            double factor = speedFactor(player);
            Double previous = lastSpeed.get(player.getUniqueId());
            if (previous != null && Math.abs(previous - factor) < 0.001) {
                continue;
            }
            lastSpeed.put(player.getUniqueId(), factor);
            AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed == null) {
                continue;
            }
            speed.removeModifier(SPEED_KEY);
            if (Math.abs(factor - 1.0) > 0.001) {
                speed.addModifier(new AttributeModifier(SPEED_KEY, factor - 1.0,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
            }
        }
    }

    public void forceSpeedUpdate(Player player) {
        lastSpeed.remove(player.getUniqueId());
    }

    private double speedFactor(Player player) {
        if (!plugin.raids().isInRaid(player)) {
            return 1.0;
        }
        double factor = 1.0;
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            ItemDef def = plugin.items().defOf(armor);
            if (def != null) {
                factor -= def.speedPenalty();
            }
        }
        if (hasInjury(player, Injury.FRACTURE) && !painkilled(player)) {
            factor -= 0.25;
        }
        if (adrenaline(player)) {
            factor += 0.2;
        }
        factor *= plugin.weapons().adsSpeedFactor(player);
        factor *= plugin.operators().speedFactor(player);
        if (plugin.downed().isDowned(player)) {
            factor = 0.35;
        }
        return Math.max(0.2, factor);
    }

    // ------------------------------------------------------------------ medical items

    @EventHandler(priority = EventPriority.HIGH)
    public void onMedStart(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemDef def = plugin.items().defOf(event.getItem());
        if (def == null || def.med() == null) {
            return;
        }
        Player player = event.getPlayer();
        MedSpec med = def.med();
        if (!plugin.raids().isInRaid(player)) {
            event.setCancelled(true);
            Text.send(player, "<#9AA0A6>医疗物品只能在行动中使用。");
            return;
        }
        boolean downed = plugin.downed().isDowned(player);
        if (med.effect() == MedSpec.Effect.SELF_REVIVE ? !downed : downed) {
            event.setCancelled(true);
            player.sendActionBar(Text.mm(downed ? "<#FF8A80>倒地时只能使用自救注射器" : "<#9AA0A6>只有倒地时才能使用"));
            return;
        }
        if (!useful(player, def)) {
            event.setCancelled(true);
            player.sendActionBar(Text.mm("<#9AA0A6>当前不需要使用 " + Text.escape(def.name())));
        }
    }

    private boolean useful(Player player, ItemDef def) {
        MedSpec med = def.med();
        if (med.effect() != MedSpec.Effect.NONE) {
            return true;
        }
        for (Injury injury : med.cures()) {
            if (hasInjury(player, injury)) {
                return true;
            }
        }
        return med.pool() > 0 && player.getHealth() < MAX_HP - 0.5;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMedFinish(PlayerItemConsumeEvent event) {
        ItemStack stack = event.getItem();
        ItemDef def = plugin.items().defOf(stack);
        if (def == null) {
            return;
        }
        event.setCancelled(true);
        if (def.med() == null) {
            return;
        }
        Player player = event.getPlayer();
        MedSpec med = def.med();
        boolean consumed = true;
        for (Injury injury : med.cures()) {
            cure(player, injury);
        }
        switch (med.effect()) {
            case PAINKILLER -> painkillerUntil.put(player.getUniqueId(), System.currentTimeMillis() + med.effectSeconds() * 1000L);
            case ADRENALINE -> grantAdrenaline(player, med.effectSeconds());
            case SELF_REVIVE -> plugin.downed().revive(player, null);
            default -> {
            }
        }
        if (med.pool() > 0) {
            double remaining = ItemFactory.dura(stack, med.pool());
            double missing = MAX_HP - player.getHealth();
            double heal = Math.min(Math.min(missing, med.healPerUse() > 0 ? med.healPerUse() : missing), remaining);
            heal *= plugin.operators().healMultiplier(player);
            heal(player, heal);
            remaining -= heal;
            if (remaining > 0.5) {
                consumed = false;
                ItemStack updated = stack.clone();
                updated.setAmount(1);
                plugin.items().setDura(updated, remaining);
                replaceOne(player, event.getHand(), stack, updated);
            }
            player.sendActionBar(Text.mm("<#5BD16B>+" + Math.round(heal) + " HP  <#8A8F98>剩余治疗量 " + Math.round(Math.max(0, remaining))));
        } else {
            player.sendActionBar(Text.mm("<#5BD16B>已使用 " + Text.escape(def.name())));
        }
        if (consumed) {
            replaceOne(player, event.getHand(), stack, null);
        }
        player.getWorld().playSound(player.getLocation(), "craftov:med.finish", SoundCategory.PLAYERS, 0.8f, 1f);
        forceSpeedUpdate(player);
    }

    private static void replaceOne(Player player, org.bukkit.inventory.EquipmentSlot hand, ItemStack used, ItemStack replacement) {
        ItemStack inHand = player.getInventory().getItem(hand);
        if (inHand.getAmount() > 1) {
            inHand.setAmount(inHand.getAmount() - 1);
            player.getInventory().setItem(hand, inHand);
            if (replacement != null) {
                plugin().carry().give(player, replacement, true);
            }
        } else {
            player.getInventory().setItem(hand, replacement);
        }
    }

    private static CraftovPlugin plugin() {
        return CraftovPlugin.get();
    }

    // ------------------------------------------------------------------ vanilla suppression

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            event.setCancelled(true);
            boolean fracture = hasInjury(player, Injury.FRACTURE) && !painkilled(player) && plugin.raids().isInRaid(player);
            player.setFoodLevel(fracture ? 6 : 20);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player player && plugin.raids().isInRaid(player)
                && (event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED
                || event.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player
                && plugin.raids().isInRaid(player)) {
            event.setDamage(event.getDamage() * 5.0);
            if (event.getDamage() >= 25) {
                addInjury(player, Injury.FRACTURE);
            }
        }
    }
}
