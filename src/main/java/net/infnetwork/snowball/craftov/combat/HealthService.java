package net.infnetwork.snowball.craftov.combat;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import net.infnetwork.snowball.craftov.util.Nms;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
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
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
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
import java.util.HashSet;
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
    /** The off hand's slot in the player inventory. */
    private static final int OFFHAND_SLOT = 40;
    /** Most urgent first: bleeding to death beats a broken leg. */
    private static final List<BodyPart> BLEED_ORDER = List.of(BodyPart.CHEST, BodyPart.STOMACH, BodyPart.HEAD,
            BodyPart.LEFT_LEG, BodyPart.RIGHT_LEG, BodyPart.LEFT_ARM, BodyPart.RIGHT_ARM);
    private static final List<BodyPart> FRACTURE_ORDER = List.of(BodyPart.LEFT_LEG, BodyPart.RIGHT_LEG,
            BodyPart.RIGHT_ARM, BodyPart.LEFT_ARM);

    /** A medical item being applied right now, and how many of its cues have played. */
    public record MedUse(ItemDef def, long startTick, long endTick, int cuesPlayed) {
        public double progress(long now) {
            return Math.min(1.0, (now - startTick) / (double) Math.max(1, endTick - startTick));
        }

        public double secondsLeft(long now) {
            return Math.max(0, (endTick - now) / 20.0);
        }
    }

    private final CraftovPlugin plugin;
    private final Map<UUID, EnumMap<BodyPart, EnumSet<Injury>>> wounds = new HashMap<>();
    /**
     * Who made the bleeding wound on each part (absent: nobody, or unknown). Only that attacker's
     * kill credit is kept going while it bleeds.
     */
    private final Map<UUID, EnumMap<BodyPart, UUID>> bleedSources = new HashMap<>();
    private final Map<UUID, Long> painkillerUntil = new HashMap<>();
    private final Map<UUID, Long> adrenalineUntil = new HashMap<>();
    private final Map<UUID, Double> lastSpeed = new HashMap<>();
    private final Map<UUID, Double> lastJump = new HashMap<>();
    private final Map<UUID, MedUse> medUse = new HashMap<>();
    private final Map<UUID, WorldBorder> vignettes = new HashMap<>();
    /**
     * Runs of {@link #tickLowHealth} so far: the heartbeat's beat. The server tick will not do, as
     * the timer never runs on a multiple of 10 (the scheduler counts from -1 while the plugin
     * enables), and a test for one silenced the heartbeat altogether.
     */
    private long lowHealthRuns;

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
        bleedSources.remove(id);
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
        addInjury(player, part, injury, null);
    }

    /**
     * Adds an injury made by {@code source} (null: nobody, or unknown). A new or worsened bleed on
     * the part becomes the source's wound, whose bleeding keeps their kill credit going; one already
     * there as bad stays with whoever made it.
     */
    public void addInjury(Player player, BodyPart part, Injury injury, @Nullable UUID source) {
        if (addWound(player.getUniqueId(), part, injury, source)) {
            Notify.info(player, "<" + injury.hex() + ">⚠ " + part.displayName() + injury.displayName());
            player.playSound(player.getLocation(), "craftov:status.injury", SoundCategory.PLAYERS, 0.8f, 1f);
            updateFood(player);
            forceSpeedUpdate(player);
        }
    }

    /** Adds an injury made by {@code source} to {@code uuid}'s wounds, online or not. True if it is new. */
    private boolean addWound(UUID uuid, BodyPart part, Injury injury, @Nullable UUID source) {
        if (injury == Injury.FRACTURE && !part.canFracture()) {
            return false;
        }
        EnumSet<Injury> set = wounds.computeIfAbsent(uuid, k -> new EnumMap<>(BodyPart.class))
                .computeIfAbsent(part, k -> EnumSet.noneOf(Injury.class));
        if (injury == Injury.LIGHT_BLEED && set.contains(Injury.HEAVY_BLEED)) {
            return false;
        }
        if (injury == Injury.HEAVY_BLEED) {
            set.remove(Injury.LIGHT_BLEED);
        }
        if (!set.add(injury)) {
            return false;
        }
        if (injury != Injury.FRACTURE) {
            EnumMap<BodyPart, UUID> sources = bleedSources.computeIfAbsent(uuid, k -> new EnumMap<>(BodyPart.class));
            if (source == null) {
                sources.remove(part);
            } else {
                sources.put(part, source);
            }
        }
        return true;
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

    /** One treated wound: e.g. "左腿骨折", and how much of the item's healing pool it used. */
    public record Treated(String label, double cost) {
    }

    /**
     * Healing pool a medkit or surgery kit uses up to treat one wound of this kind
     * (medical.wound-cost). Treating used to be free, so a surgery kit at full health cleared every
     * fracture and bleed on the body and stayed in the pocket.
     */
    public double woundCost(Injury injury) {
        double fallback = switch (injury) {
            case LIGHT_BLEED -> 15;
            case HEAVY_BLEED, FRACTURE -> 40;
        };
        return Math.max(0, plugin.getConfig().getDouble("medical.wound-cost." + injury.name(), fallback));
    }

    private static boolean treatable(Set<Injury> cures, Injury injury) {
        return cures.contains(injury) || (injury == Injury.LIGHT_BLEED && cures.contains(Injury.HEAVY_BLEED));
    }

    /**
     * Treats up to {@code med.cureCount()} wounds the item can handle, most dangerous first, and
     * only on the worst body part for a one-part item. An item with a healing pool pays for each
     * wound out of {@code pool} (what is left in it) and stops once that is spent; the last wound
     * may take the rest. Returns each treated wound with what it cost.
     */
    public List<Treated> treat(Player player, MedSpec med, double pool) {
        List<Treated> done = new ArrayList<>();
        EnumMap<BodyPart, EnumSet<Injury>> map = wounds.get(player.getUniqueId());
        if (map == null) {
            return done;
        }
        BodyPart only = med.onePart() ? worstPart(map, med.cures()) : null;
        double left = pool;
        for (Injury injury : List.of(Injury.HEAVY_BLEED, Injury.LIGHT_BLEED, Injury.FRACTURE)) {
            if (!treatable(med.cures(), injury)) {
                continue;
            }
            for (BodyPart part : injury == Injury.FRACTURE ? FRACTURE_ORDER : BLEED_ORDER) {
                if (done.size() >= med.cureCount() || (med.pool() > 0 && left <= 0.5)) {
                    break;
                }
                if (only != null && part != only) {
                    continue;
                }
                EnumSet<Injury> set = map.get(part);
                if (set != null && set.remove(injury)) {
                    double cost = med.pool() > 0 ? Math.min(left, woundCost(injury)) : 0;
                    left -= cost;
                    done.add(new Treated(part.displayName() + injury.displayName(), cost));
                }
            }
        }
        updateFood(player);
        forceSpeedUpdate(player);
        return done;
    }

    /** The body part whose wounds {@code cures} can treat cost the most to treat (most urgent first on a tie). */
    private @Nullable BodyPart worstPart(EnumMap<BodyPart, EnumSet<Injury>> map, Set<Injury> cures) {
        BodyPart worst = null;
        double most = 0;
        for (BodyPart part : BLEED_ORDER) {
            EnumSet<Injury> set = map.get(part);
            double total = 0;
            if (set != null) {
                for (Injury injury : set) {
                    if (treatable(cures, injury)) {
                        total += woundCost(injury) + 1; // +1: a wound counts even when it costs nothing
                    }
                }
            }
            if (total > most) {
                most = total;
                worst = part;
            }
        }
        return worst;
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

    /** The wounds a hit of {@code damage} on {@code part} may cause, made by {@code source}. */
    public void rollInjuries(Player player, BodyPart part, double damage, @Nullable LivingEntity source) {
        UUID from = source == null ? null : source.getUniqueId();
        for (Injury injury : roll(part, damage)) {
            addInjury(player, part, injury, from);
        }
    }

    /**
     * The same for a disconnected player, from a hit their stand-in took: the wounds wait for them,
     * and a bleed bleeds the stand-in on (tickWounds), as it would the player.
     */
    public void rollInjuries(UUID offline, BodyPart part, double damage, @Nullable LivingEntity source) {
        UUID from = source == null ? null : source.getUniqueId();
        for (Injury injury : roll(part, damage)) {
            addWound(offline, part, injury, from);
        }
    }

    /** What a penetrating hit of {@code damage} does to {@code part}: bleeding, a fracture, both or neither. */
    private static List<Injury> roll(BodyPart part, double damage) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double scale = Math.min(2.0, damage / 30.0);
        double heavy = part.armored() ? 0.08 : 0.04;
        List<Injury> out = new ArrayList<>(2);
        if (rng.nextDouble() < heavy * scale) {
            out.add(Injury.HEAVY_BLEED);
        } else if (rng.nextDouble() < 0.26 * scale) {
            out.add(Injury.LIGHT_BLEED);
        }
        if (part.canFracture() && rng.nextDouble() < 0.2 * scale) {
            out.add(Injury.FRACTURE);
        }
        return out;
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

    /**
     * Sets the player's health without a hit, where a drop is to show on their own client as one
     * does (the view tilts): a tripwire, the test armory's injury (a knock-down has its own,
     * {@link #setHealthKnockedDown}). A bleed in the same tick reaches the client in the same health
     * update, and bleeding is kept from the client (RaidManager.loseHealth), so the drop is marked to
     * show (Nms.loudHealthLoss). Only a drop is marked: a mark no update carries could show the next
     * bleed instead.
     */
    public void setHealthShown(Player player, double health) {
        if (health < player.getHealth()) {
            Nms.SelfView view = plugin.fov().selfView(player);
            if (view != null) {
                Nms.loudHealthLoss(view);
            }
        }
        player.setHealth(health);
    }

    /**
     * Knocked down to {@code health}: the player's own client is shown a drop to it as from a hit
     * (the view tilts), whatever health the server had. Bleeding is kept from the client, so it
     * usually still believes the health from before the bleed, while a bleed that ran down to the
     * knock-down left the server's at or below the one set here (a light bleed always does): no
     * drop for the server, and so none for the client without the loud mark, which shows the true
     * health.
     * <p>
     * The health update that carries the mark is sent at once, and that is what makes every
     * knock-down tilt the view, not only one that leaves the health as it was. The player's own
     * entity data carries the (scaled) health too: CraftPlayer.updateScaledHealth sets it, and the
     * entity tracker sends it to the player as well as to those tracking them. On the client it sets
     * the health without a tilt. The tracker runs in the world tick, before the connection tick sends
     * the health update, so a change made earlier in the tick (a bleed from the scheduler, a hit
     * from a packet) reaches the client as entity data first, and the tick's update then finds the
     * client already at the new health: no drop, no tilt. Sent here, the update goes out ahead of
     * the entity data. It also covers a health that did not change (a bleed that landed on 1), for
     * which the tick sends no update at all, and it takes the mark at once, so the mark never waits
     * for a later update.
     */
    public void setHealthKnockedDown(Player player, double health) {
        Nms.SelfView view = plugin.fov().selfView(player);
        if (view != null) {
            Nms.loudHealthLoss(view);
        }
        player.setHealth(health);
        // Now, not left to the tick: the tick's update would come after the entity data that already
        // took the client to this health, changed or not (see above).
        player.sendHealthUpdate();
    }

    /** In a raid, an untreated broken leg, no stamina or an overloaded pack leave no sprint in the player. */
    public boolean sprintBlocked(Player player) {
        return plugin.raids().isInRaid(player)
                && (activeFractures(player, false) > 0 || plugin.stamina().exhausted(player) || plugin.stamina().overloaded(player));
    }

    /**
     * Sprinting is impossible when {@link #sprintBlocked}, and while aiming down the sights (food at 6
     * disables it: the vanilla client has no other switch for it).
     */
    public void updateFood(Player player) {
        boolean noSprint = sprintBlocked(player) || plugin.weapons().stopsSprint(player);
        int food = noSprint ? 6 : 20;
        if (player.getFoodLevel() != food) {
            player.setFoodLevel(food);
        }
        if (noSprint && player.isSprinting()) {
            player.setSprinting(false);
        }
    }

    // ------------------------------------------------------------------ ticking

    /** Health lost to bleeding every 1.5 s, the part bleeding worst, and who made the bleeding wounds. */
    private record Bleed(double amount, BodyPart worst, Set<UUID> sources) {
    }

    private static @Nullable Bleed bleed(@Nullable EnumMap<BodyPart, EnumSet<Injury>> map, @Nullable EnumMap<BodyPart, UUID> by) {
        if (map == null) {
            return null;
        }
        double bleed = 0;
        BodyPart worst = null;
        Set<UUID> sources = new HashSet<>();
        for (Map.Entry<BodyPart, EnumSet<Injury>> e : map.entrySet()) {
            if (e.getValue().contains(Injury.HEAVY_BLEED)) {
                bleed += 2.6;
                worst = e.getKey();
            } else if (e.getValue().contains(Injury.LIGHT_BLEED)) {
                bleed += 1.0;
                if (worst == null) {
                    worst = e.getKey();
                }
            } else {
                continue;
            }
            UUID source = by == null ? null : by.get(e.getKey());
            if (source != null) {
                sources.add(source);
            }
        }
        return bleed > 0 && worst != null ? new Bleed(bleed, worst, sources) : null;
    }

    private void tickWounds() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            EnumMap<BodyPart, EnumSet<Injury>> map = wounds.get(player.getUniqueId());
            if (map == null || !plugin.raids().isInRaid(player) || plugin.downed().isDowned(player)) {
                continue;
            }
            Bleed bleed = bleed(map, bleedSources.get(player.getUniqueId()));
            if (bleed != null) {
                // Not a hit: no hurt sound for everyone around, no hurt flash, only a quiet drip
                // for the one bleeding (the HUD's body shows where).
                plugin.damage().recordBleed(player, "失血", bleed.worst(), bleed.sources());
                plugin.raids().loseHealth(player, bleed.amount());
                if (!plugin.downed().isDowned(player) && plugin.raids().isInRaid(player)) {
                    player.playSound(player.getLocation(), "craftov:status.bleed", SoundCategory.PLAYERS, 0.4f, 1f);
                }
                bleedDrops(player);
            }
            updateFood(player);
        }
        // Leaving the game stopped the bleeding: the wounds stayed as they were until the player
        // came back. They bleed on now, into the stand-in or the health kept for the player.
        for (Raid raid : new ArrayList<>(plugin.raids().raids())) {
            if (raid.state != Raid.State.RUNNING) {
                continue;
            }
            for (RaidPlayer rp : new ArrayList<>(raid.players.values())) {
                Bleed bleed = rp.state == RaidPlayer.State.DISCONNECTED && !rp.inFlight
                        ? bleed(wounds.get(rp.uuid), bleedSources.get(rp.uuid)) : null;
                if (bleed != null) {
                    plugin.raids().bleedOffline(raid, rp, bleed.amount(), bleed.worst(), bleed.sources());
                }
            }
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

    /** Re-applies the movement modifiers right away (aiming slows the player from the tick it starts). */
    public void forceSpeedUpdate(Player player) {
        lastJump.remove(player.getUniqueId());
        double factor = speedFactor(player);
        lastSpeed.put(player.getUniqueId(), factor);
        applyModifier(player, Attribute.MOVEMENT_SPEED, SPEED_KEY, factor);
    }

    private double speedFactor(Player player) {
        if (!plugin.raids().isInRaid(player)) {
            // The range: aiming slows the player as it would in a raid; nothing else does.
            return plugin.weapons().adsSpeedFactor(player);
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

    /**
     * Red vignette and heartbeat below 35 HP. Runs every 10 ticks: the heart beats on every run
     * below 18 HP (twice a second) and on every other run from 18 to 35 (once a second).
     */
    private void tickLowHealth() {
        boolean everyOther = lowHealthRuns++ % 2 == 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean active = plugin.raids().isInRaid(player) && !player.isDead() && !plugin.downed().isDowned(player);
            double hp = player.getHealth();
            double intensity = active && hp < 35 ? Math.min(0.85, 0.25 + (35 - hp) / 35.0 * 0.6) : 0;
            setVignette(player, intensity);
            if (intensity > 0 && (hp < 18 || everyOther)) {
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
            Text.send(player, "<muted>医疗物品只能在行动中使用。");
            return;
        }
        if (plugin.downed().isReviving(player)) {
            event.setCancelled(true);
            Notify.hint(player, "救援时不能使用医疗物品");
            return;
        }
        boolean downed = plugin.downed().isDowned(player);
        if (med.effect() == MedSpec.Effect.SELF_REVIVE ? !downed : downed) {
            event.setCancelled(true);
            Notify.info(player, downed ? "<danger>倒地时只能使用自救注射器" : "<muted>只有倒地时才能使用");
            return;
        }
        if (!useful(player, def)) {
            event.setCancelled(true);
            Notify.hint(player, "当前不需要使用 " + Text.escape(def.name()));
            return;
        }
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        MedUse current = medUse.get(player.getUniqueId());
        if (current == null || !current.def().id().equals(def.id())) {
            medUse.put(player.getUniqueId(), cues(player, new MedUse(def, now, now + useTicks(event.getItem(), med), 0), now));
        }
    }

    /**
     * Plays the cues of a use that are due by {@code now} and not played yet (items.yml med.cues: a
     * wrapper torn open as it starts, gauze wound on, a strap pulled tight), for everyone nearby and
     * following the player; returns the use with them counted.
     */
    private static MedUse cues(Player player, MedUse use, long now) {
        List<MedSpec.Cue> cues = use.def().med().cues();
        double progress = use.progress(now);
        int played = use.cuesPlayed();
        while (played < cues.size() && cues.get(played).at() <= progress + 1e-6) {
            player.getWorld().playSound(player, "craftov:" + cues.get(played).sound(), SoundCategory.PLAYERS, 0.8f, 1f);
            played++;
        }
        return played == use.cuesPlayed() ? use : new MedUse(use.def(), use.startTick(), use.endTick(), played);
    }

    /**
     * How long using {@code stack} takes, in ticks: the time in its consumable component, which is
     * what the client animates and vanilla counts down (a stack made before items.yml changed the
     * time keeps the old one until it is rebuilt), else the configured time.
     */
    private static long useTicks(@Nullable ItemStack stack, MedSpec med) {
        Consumable consumable = stack == null ? null : stack.getData(DataComponentTypes.CONSUMABLE);
        return (long) ((consumable != null ? consumable.consumeSeconds() : (float) med.useSeconds()) * 20f);
    }

    private void tickMedUse() {
        long now = Bukkit.getCurrentTick();
        medUse.entrySet().removeIf(e -> {
            Player player = Bukkit.getPlayer(e.getKey());
            return player == null || (!player.hasActiveItem() && now - e.getValue().startTick() > 4);
        });
        for (Map.Entry<UUID, MedUse> e : medUse.entrySet()) {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player != null && player.hasActiveItem()) {
                e.setValue(cues(player, e.getValue(), now));
            }
        }
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

    /**
     * Ends a medical item's use now, if one is going on: before something else is put into the hand
     * it is used from. Vanilla carries a use on for as long as that hand holds the same kind of item,
     * and every Craftov item is paper, so it would go on with whatever was put there.
     * {@link #onMedFinish} refuses such a finish anyway; this spares the player the wait for a use
     * that cannot count. Other uses (a grenade being cooked) are left alone.
     */
    public void interruptMed(Player player) {
        if (medUse.remove(player.getUniqueId()) != null && player.hasActiveItem()) {
            player.clearActiveItem();
        }
    }

    /** An inventory click that moves something into or out of the hand a medical item is used from. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseHandClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !medUse.containsKey(player.getUniqueId())) {
            return;
        }
        int hand = activeSlot(player);
        boolean touched = event.getClickedInventory() == player.getInventory() && event.getSlot() == hand
                || event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() == hand
                || event.getClick() == ClickType.SWAP_OFFHAND && hand == OFFHAND_SLOT;
        if (touched) {
            interruptMed(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseHandDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !medUse.containsKey(player.getUniqueId())) {
            return;
        }
        int hand = activeSlot(player);
        for (int raw : event.getRawSlots()) {
            if (event.getView().getInventory(raw) == player.getInventory() && event.getView().convertSlot(raw) == hand) {
                interruptMed(player);
                return;
            }
        }
    }

    /** The player-inventory slot of the hand an item is being used from. */
    private static int activeSlot(Player player) {
        return player.getActiveItemHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND ? OFFHAND_SLOT
                : player.getInventory().getHeldItemSlot();
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
        MedUse use = medUse.remove(player.getUniqueId());
        if (use == null || !use.def().id().equals(def.id()) || Bukkit.getCurrentTick() < use.endTick() - 2) {
            // Not the item whose use began, or not for as long as it takes. Vanilla only checks that
            // the hand still holds paper: a surgery kit swapped in by an inventory click during a
            // 1.5 second painkiller would be what finishes.
            player.clearActiveItem();
            Notify.hint(player, "使用中断，请重新使用 " + Text.escape(def.name()));
            return;
        }
        MedSpec med = def.med();
        boolean consumed = true;
        double remaining = med.pool() > 0 ? ItemFactory.dura(stack, med.pool()) : 0;
        List<Treated> treated = treat(player, med, remaining);
        for (Treated t : treated) {
            remaining -= t.cost();
        }
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
            msg.append("<ok>已处理 ").append(String.join("、", treated.stream().map(Treated::label).toList()));
        }
        if (med.pool() > 0) {
            // Whatever the wounds left of the pool heals, up to the item's per-use amount. A medic's
            // bonus makes each point of the pool heal more (1.2 HP), so the pool pays only for what
            // was healed: multiplying the heal and then capping it at full health took the whole
            // multiplied amount out of the pool for health that was never restored.
            double missing = Math.max(0, MAX_HP - player.getHealth());
            double mult = plugin.operators().healMultiplier(player);
            double base = Math.max(0, Math.min(med.healPerUse() > 0 ? med.healPerUse() : remaining, remaining));
            double heal = Math.min(missing, base * mult);
            heal(player, heal);
            remaining -= heal / mult;
            if (remaining > 0.5) {
                consumed = false;
                ItemStack updated = stack.clone();
                updated.setAmount(1);
                plugin.items().setDura(updated, remaining);
                replaceOne(player, event.getHand(), stack, updated);
            }
            msg.append(msg.isEmpty() ? "" : "  ").append("<ok>+").append(Math.round(heal)).append(" HP  <muted>剩余治疗量 ")
                    .append(Math.round(Math.max(0, remaining)));
        } else if (msg.isEmpty()) {
            msg.append("<ok>已使用 ").append(Text.escape(def.name()));
        }
        Notify.info(player, msg.toString());
        if (consumed) {
            replaceOne(player, event.getHand(), stack, null);
            if (!ItemFactory.isTest(stack)) {
                plugin.raids().usedUp(player, def.id(), 1);
            }
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

    /**
     * Falls hurt five times as much in a raid. This runs before RaidManager.onDamage (HIGHEST), which
     * needs the full damage to tell whether the fall downs or kills; what the fall does to the legs
     * waits for {@link #onFallen}, once nothing has cancelled it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player
                && plugin.raids().isInRaid(player)) {
            event.setDamage(event.getDamage() * 5.0);
        }
    }

    /**
     * A fall that went through. A downed or spawn-protected player takes no fall damage
     * (RaidManager.onDamage cancels it), so crawling down a ledge neither breaks a leg nor names the
     * fall as the cause of a later bleed-out. A fall that downs or kills is cancelled there too and
     * handed to {@link #fallen} directly.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallen(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player
                && plugin.raids().isInRaid(player)) {
            fallen(player, event.getDamage());
        }
    }

    /**
     * Records a fall of {@code damage} (already multiplied) as the cause, keeping the credit with
     * whoever hurt the player last, and breaks a leg from 25 damage (8 blocks) and both from 50.
     */
    public void fallen(Player player, double damage) {
        plugin.damage().recordHit(player, null, "坠落", BodyPart.LEFT_LEG);
        if (damage >= 25) {
            BodyPart leg = ThreadLocalRandom.current().nextBoolean() ? BodyPart.LEFT_LEG : BodyPart.RIGHT_LEG;
            addInjury(player, leg, Injury.FRACTURE);
            if (damage >= 50) {
                addInjury(player, leg == BodyPart.LEFT_LEG ? BodyPart.RIGHT_LEG : BodyPart.LEFT_LEG, Injury.FRACTURE);
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

    /**
     * Dark drops round a bleeding player, for everyone else: at the bleeder's own body they filled
     * the lower half of their screen with big maroon squares (the HUD already shows them bleeding).
     */
    private static void bleedDrops(Player player) {
        org.bukkit.Location at = player.getLocation().add(0, 0.8, 0);
        Particle.DustOptions dust = new Particle.DustOptions(org.bukkit.Color.fromRGB(88, 8, 10), 0.9f);
        for (Player other : player.getWorld().getPlayers()) {
            if (other != player && other.getLocation().distanceSquared(at) < 48 * 48) {
                other.spawnParticle(Particle.DUST, at, 6, 0.2, 0.3, 0.2, 0, dust);
            }
        }
    }
}
