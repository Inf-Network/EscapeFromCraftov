package net.infnetwork.snowball.craftov.combat;

import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.WorldBorder;
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
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 100 HP model with wounds tracked per body part (bleeding, fractures), medical items with timed
 * use, movement penalties and low-health feedback (red vignette and heartbeat).
 */
public final class HealthService implements Listener {

    public static final double MAX_HP = 100.0;
    private static final NamespacedKey SPEED_KEY = new NamespacedKey("craftov", "speed");
    private static final NamespacedKey JUMP_KEY = new NamespacedKey("craftov", "jump");
    /** Most urgent first: bleeding to death beats a broken leg. */
    private static final List<BodyPart> BLEED_ORDER = List.of(BodyPart.CHEST, BodyPart.STOMACH, BodyPart.HEAD,
            BodyPart.LEFT_LEG, BodyPart.RIGHT_LEG, BodyPart.LEFT_ARM, BodyPart.RIGHT_ARM);
    private static final List<BodyPart> FRACTURE_ORDER = List.of(BodyPart.LEFT_LEG, BodyPart.RIGHT_LEG,
            BodyPart.RIGHT_ARM, BodyPart.LEFT_ARM);

    /** A medical item being applied right now. */
    public record MedUse(ItemDef def, long startTick, long endTick) {
        public double progress(long now) {
            return Math.min(1.0, (now - startTick) / (double) Math.max(1, endTick - startTick));
        }

        public double secondsLeft(long now) {
            return Math.max(0, (endTick - now) / 20.0);
        }
    }

    private final CraftovPlugin plugin;
    private final Map<UUID, EnumMap<BodyPart, EnumSet<Injury>>> wounds = new HashMap<>();
    private final Map<UUID, Long> painkillerUntil = new HashMap<>();
    private final Map<UUID, Long> adrenalineUntil = new HashMap<>();
    private final Map<UUID, Double> lastSpeed = new HashMap<>();
    private final Map<UUID, Double> lastJump = new HashMap<>();
    private final Map<UUID, MedUse> medUse = new HashMap<>();
    private final Map<UUID, WorldBorder> vignettes = new HashMap<>();

    public HealthService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickWounds, 30L, 30L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickSpeed, 5L, 5L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickLowHealth, 10L, 10L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickMedUse, 2L, 2L);
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
        UUID id = player.getUniqueId();
        wounds.remove(id);
        painkillerUntil.remove(id);
        adrenalineUntil.remove(id);
        medUse.remove(id);
        setVignette(player, 0);
        player.setFoodLevel(20);
        forceSpeedUpdate(player);
    }

    // ------------------------------------------------------------------ wounds

    /** Wounds by body part (a copy; empty parts are omitted). */
    public Map<BodyPart, Set<Injury>> wounds(Player player) {
        EnumMap<BodyPart, EnumSet<Injury>> map = wounds.get(player.getUniqueId());
        Map<BodyPart, Set<Injury>> out = new EnumMap<>(BodyPart.class);
        if (map != null) {
            map.forEach((part, set) -> {
                if (!set.isEmpty()) {
                    out.put(part, Collections.unmodifiableSet(EnumSet.copyOf(set)));
                }
            });
        }
        return out;
    }

    /** Every injury type the player currently has, on any part. */
    public Set<Injury> injuries(Player player) {
        EnumSet<Injury> all = EnumSet.noneOf(Injury.class);
        EnumMap<BodyPart, EnumSet<Injury>> map = wounds.get(player.getUniqueId());
        if (map != null) {
            map.values().forEach(all::addAll);
        }
        return all;
    }

    public boolean hasInjury(Player player, Injury injury) {
        return injuries(player).contains(injury);
    }

    public boolean hasInjury(Player player, BodyPart part, Injury injury) {
        EnumMap<BodyPart, EnumSet<Injury>> map = wounds.get(player.getUniqueId());
        return map != null && map.containsKey(part) && map.get(part).contains(injury);
    }

    public void addInjury(Player player, BodyPart part, Injury injury) {
        if (injury == Injury.FRACTURE && !part.canFracture()) {
            return;
        }
        EnumSet<Injury> set = wounds.computeIfAbsent(player.getUniqueId(), k -> new EnumMap<>(BodyPart.class))
                .computeIfAbsent(part, k -> EnumSet.noneOf(Injury.class));
        if (injury == Injury.LIGHT_BLEED && set.contains(Injury.HEAVY_BLEED)) {
            return;
        }
        if (injury == Injury.HEAVY_BLEED) {
            set.remove(Injury.LIGHT_BLEED);
        }
        if (set.add(injury)) {
            player.sendActionBar(Text.mm("<" + injury.hex() + ">⚠ " + part.displayName() + injury.displayName()));
            player.playSound(player.getLocation(), "craftov:status.injury", SoundCategory.PLAYERS, 0.8f, 1f);
            updateFood(player);
            forceSpeedUpdate(player);
        }
    }

    /** Adds an injury to a sensible part (used by tests and environmental damage). */
    public void addInjury(Player player, Injury injury) {
        BodyPart part = injury == Injury.FRACTURE
                ? (ThreadLocalRandom.current().nextBoolean() ? BodyPart.LEFT_LEG : BodyPart.RIGHT_LEG)
                : BodyPart.CHEST;
        addInjury(player, part, injury);
    }

    /** Removes an injury type from every part. */
    public void cure(Player player, Injury injury) {
        EnumMap<BodyPart, EnumSet<Injury>> map = wounds.get(player.getUniqueId());
        if (map != null) {
            map.values().forEach(set -> set.remove(injury));
        }
        updateFood(player);
        forceSpeedUpdate(player);
    }

    /**
     * Treats up to {@code count} wounds the item can handle, most dangerous first. Returns a short
     * description of each treated wound, e.g. "左腿骨折".
     */
    public List<String> treat(Player player, Set<Injury> cures, int count) {
        List<String> done = new ArrayList<>();
        EnumMap<BodyPart, EnumSet<Injury>> map = wounds.get(player.getUniqueId());
        if (map == null) {
            return done;
        }
        for (Injury injury : List.of(Injury.HEAVY_BLEED, Injury.LIGHT_BLEED, Injury.FRACTURE)) {
            boolean treatable = cures.contains(injury) || (injury == Injury.LIGHT_BLEED && cures.contains(Injury.HEAVY_BLEED));
            if (!treatable) {
                continue;
            }
            for (BodyPart part : injury == Injury.FRACTURE ? FRACTURE_ORDER : BLEED_ORDER) {
                if (done.size() >= count) {
                    break;
                }
                EnumSet<Injury> set = map.get(part);
                if (set != null && set.remove(injury)) {
                    done.add(part.displayName() + injury.displayName());
                }
            }
        }
        updateFood(player);
        forceSpeedUpdate(player);
        return done;
    }

    /** HUD colour state: 0 ok, 1 light bleed, 2 heavy bleed, 3 fracture, 4 fracture and bleeding. */
    public int partState(Player player, BodyPart part) {
        EnumMap<BodyPart, EnumSet<Injury>> map = wounds.get(player.getUniqueId());
        EnumSet<Injury> set = map == null ? null : map.get(part);
        if (set == null || set.isEmpty()) {
            return 0;
        }
        boolean fracture = set.contains(Injury.FRACTURE);
        boolean heavy = set.contains(Injury.HEAVY_BLEED);
        boolean bleeding = heavy || set.contains(Injury.LIGHT_BLEED);
        if (fracture) {
            return bleeding ? 4 : 3;
        }
        return heavy ? 2 : 1;
    }

    /** Fractured arms (or legs) that currently hurt, i.e. not masked by a painkiller. */
    public int activeFractures(Player player, boolean arms) {
        if (painkilled(player)) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<BodyPart, Set<Injury>> e : wounds(player).entrySet()) {
            if (e.getValue().contains(Injury.FRACTURE) && (arms ? e.getKey().isArm() : e.getKey().isLeg())) {
                count++;
            }
        }
        return count;
    }

    public void rollInjuries(Player player, BodyPart part, double damage) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double scale = Math.min(2.0, damage / 30.0);
        double heavy = part.armored() ? 0.08 : 0.04;
        if (rng.nextDouble() < heavy * scale) {
            addInjury(player, part, Injury.HEAVY_BLEED);
        } else if (rng.nextDouble() < 0.26 * scale) {
            addInjury(player, part, Injury.LIGHT_BLEED);
        }
        if (part.canFracture() && rng.nextDouble() < 0.2 * scale) {
            addInjury(player, part, Injury.FRACTURE);
        }
    }

    public boolean painkilled(Player player) {
        return painkillerUntil.getOrDefault(player.getUniqueId(), 0L) > System.currentTimeMillis();
    }

    public int painkillerSeconds(Player player) {
        return (int) Math.max(0, (painkillerUntil.getOrDefault(player.getUniqueId(), 0L) - System.currentTimeMillis()) / 1000);
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

    /** Sprinting is impossible with an untreated broken leg or no stamina (food at 6 disables it). */
    public void updateFood(Player player) {
        boolean noSprint = plugin.raids().isInRaid(player)
                && (activeFractures(player, false) > 0 || plugin.stamina().exhausted(player));
        int food = noSprint ? 6 : 20;
        if (player.getFoodLevel() != food) {
            player.setFoodLevel(food);
        }
        if (noSprint && player.isSprinting()) {
            player.setSprinting(false);
        }
    }

    // ------------------------------------------------------------------ ticking

    private void tickWounds() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            EnumMap<BodyPart, EnumSet<Injury>> map = wounds.get(player.getUniqueId());
            if (map == null || !plugin.raids().isInRaid(player) || plugin.downed().isDowned(player)) {
                continue;
            }
            double bleed = 0;
            BodyPart worst = null;
            for (Map.Entry<BodyPart, EnumSet<Injury>> e : map.entrySet()) {
                if (e.getValue().contains(Injury.HEAVY_BLEED)) {
                    bleed += 2.6;
                    worst = e.getKey();
                } else if (e.getValue().contains(Injury.LIGHT_BLEED)) {
                    bleed += 1.0;
                    if (worst == null) {
                        worst = e.getKey();
                    }
                }
            }
            if (bleed > 0 && worst != null) {
                plugin.damage().recordHit(player, null, "失血", worst);
                plugin.damage().deal(player, null, bleed);
                player.getWorld().spawnParticle(Particle.BLOCK, player.getLocation().add(0, 0.8, 0), 6, 0.2, 0.3, 0.2,
                        org.bukkit.Material.REDSTONE_BLOCK.createBlockData());
            }
            updateFood(player);
        }
    }

    private void tickSpeed() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            double factor = speedFactor(player);
            Double previous = lastSpeed.get(player.getUniqueId());
            if (previous == null || Math.abs(previous - factor) > 0.001) {
                lastSpeed.put(player.getUniqueId(), factor);
                applyModifier(player, Attribute.MOVEMENT_SPEED, SPEED_KEY, factor);
            }
            double jump = plugin.raids().isInRaid(player) ? Math.max(0.55, 1.0 - 0.2 * activeFractures(player, false)) : 1.0;
            Double previousJump = lastJump.get(player.getUniqueId());
            if (previousJump == null || Math.abs(previousJump - jump) > 0.001) {
                lastJump.put(player.getUniqueId(), jump);
                applyModifier(player, Attribute.JUMP_STRENGTH, JUMP_KEY, jump);
            }
        }
    }

    private static void applyModifier(Player player, Attribute attribute, NamespacedKey key, double factor) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(key);
        if (Math.abs(factor - 1.0) > 0.001) {
            instance.addModifier(new AttributeModifier(key, factor - 1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                    EquipmentSlotGroup.ANY));
        }
    }

    public void forceSpeedUpdate(Player player) {
        lastSpeed.remove(player.getUniqueId());
        lastJump.remove(player.getUniqueId());
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
        factor -= 0.15 * activeFractures(player, false);
        factor -= plugin.stamina().overweightPenalty(player);
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

    /** Red vignette and heartbeat below 35 HP. */
    private void tickLowHealth() {
        long tick = Bukkit.getCurrentTick();
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean active = plugin.raids().isInRaid(player) && !player.isDead() && !plugin.downed().isDowned(player);
            double hp = player.getHealth();
            double intensity = active && hp < 35 ? Math.min(0.85, 0.25 + (35 - hp) / 35.0 * 0.6) : 0;
            setVignette(player, intensity);
            if (intensity > 0 && tick % (hp < 18 ? 10 : 20) == 0) {
                player.playSound(player.getLocation(), "craftov:status.heartbeat", SoundCategory.PLAYERS, 0.9f, 1f);
            }
        }
    }

    /**
     * Drives the client's world-border warning tint. The virtual border is centred on the player
     * 1000 blocks away, so it is never visible, and the warning distance sets how red the edges are.
     */
    private void setVignette(Player player, double intensity) {
        WorldBorder border = vignettes.get(player.getUniqueId());
        if (intensity <= 0) {
            if (border != null) {
                vignettes.remove(player.getUniqueId());
                player.setWorldBorder(null);
            }
            return;
        }
        if (border == null) {
            border = Bukkit.createWorldBorder();
            border.setSize(2000);
            vignettes.put(player.getUniqueId(), border);
        }
        Location at = player.getLocation();
        border.setCenter(at.getX(), at.getZ());
        border.setWarningDistance((int) Math.round(1000 / (1 - intensity)));
        player.setWorldBorder(border);
    }

    // ------------------------------------------------------------------ medical items

    public @Nullable MedUse medUse(Player player) {
        return medUse.get(player.getUniqueId());
    }

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
            return;
        }
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        MedUse current = medUse.get(player.getUniqueId());
        if (current == null || !current.def().id().equals(def.id())) {
            medUse.put(player.getUniqueId(), new MedUse(def, now, now + Math.round(med.useSeconds() * 20)));
            player.getWorld().playSound(player.getLocation(), "craftov:" + med.sound(), SoundCategory.PLAYERS, 0.8f, 1f);
        }
    }

    private void tickMedUse() {
        medUse.entrySet().removeIf(e -> {
            Player player = Bukkit.getPlayer(e.getKey());
            return player == null || (!player.hasActiveItem() && Bukkit.getCurrentTick() - e.getValue().startTick() > 4);
        });
    }

    @EventHandler
    public void onStopUsing(PlayerStopUsingItemEvent event) {
        ItemDef def = plugin.items().defOf(event.getItem());
        if (def != null && def.med() != null) {
            medUse.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        medUse.remove(event.getPlayer().getUniqueId());
    }

    private boolean useful(Player player, ItemDef def) {
        MedSpec med = def.med();
        if (med.effect() != MedSpec.Effect.NONE) {
            return true;
        }
        Set<Injury> current = injuries(player);
        for (Injury injury : med.cures()) {
            if (current.contains(injury) || (injury == Injury.HEAVY_BLEED && current.contains(Injury.LIGHT_BLEED))) {
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
        medUse.remove(player.getUniqueId());
        MedSpec med = def.med();
        boolean consumed = true;
        List<String> treated = treat(player, med.cures(), med.cureCount());
        switch (med.effect()) {
            case PAINKILLER -> painkillerUntil.put(player.getUniqueId(), System.currentTimeMillis() + med.effectSeconds() * 1000L);
            case ADRENALINE -> {
                grantAdrenaline(player, med.effectSeconds());
                plugin.stamina().refill(player);
            }
            case SELF_REVIVE -> plugin.downed().revive(player, null);
            default -> {
            }
        }
        StringBuilder msg = new StringBuilder();
        if (!treated.isEmpty()) {
            msg.append("<#5BD16B>已处理 ").append(String.join("、", treated));
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
            msg.append(msg.isEmpty() ? "" : "  ").append("<#5BD16B>+").append(Math.round(heal)).append(" HP  <#8A8F98>剩余治疗量 ")
                    .append(Math.round(Math.max(0, remaining)));
        } else if (msg.isEmpty()) {
            msg.append("<#5BD16B>已使用 ").append(Text.escape(def.name()));
        }
        player.sendActionBar(Text.mm(msg.toString()));
        if (consumed) {
            replaceOne(player, event.getHand(), stack, null);
        }
        player.getWorld().playSound(player.getLocation(), "craftov:med.finish", SoundCategory.PLAYERS, 0.8f, 1f);
        forceSpeedUpdate(player);
    }

    private static void replaceOne(Player player, org.bukkit.inventory.EquipmentSlot hand, ItemStack used, @Nullable ItemStack replacement) {
        ItemStack inHand = player.getInventory().getItem(hand);
        if (inHand.getAmount() > 1) {
            inHand.setAmount(inHand.getAmount() - 1);
            player.getInventory().setItem(hand, inHand);
            if (replacement != null) {
                CraftovPlugin.get().carry().give(player, replacement, true);
            }
        } else {
            player.getInventory().setItem(hand, replacement);
        }
    }

    // ------------------------------------------------------------------ vanilla suppression

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            event.setCancelled(true);
            updateFood(player);
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
            plugin.damage().recordHit(player, null, "坠落", BodyPart.LEFT_LEG);
            if (event.getDamage() >= 25) {
                BodyPart leg = ThreadLocalRandom.current().nextBoolean() ? BodyPart.LEFT_LEG : BodyPart.RIGHT_LEG;
                addInjury(player, leg, Injury.FRACTURE);
                if (event.getDamage() >= 50) {
                    addInjury(player, leg == BodyPart.LEFT_LEG ? BodyPart.RIGHT_LEG : BodyPart.LEFT_LEG, Injury.FRACTURE);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        medUse.remove(id);
        vignettes.remove(id);
        lastSpeed.remove(id);
        lastJump.remove(id);
    }
}
