package net.infnetwork.snowball.craftov.weapon;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ChargedProjectiles;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.util.Nms;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How other players see someone's hands. A server cannot animate a player model, but the vanilla
 * client picks each arm's pose from what the player holds and whether they are using it, so what
 * everyone else is told about those two things decides the pose they see. The ladder:
 *
 * <ul>
 *   <li>{@link Stance#LOW}: a gun carried low in both hands, muzzle down, standing, walking, crouching,
 *   sprinting or while it comes up after a switch. Others get the gun with a crossbow's use
 *   animation and are told the player is using it, which is the crossbow-charge pose; that pose
 *   slowly lifts the support arm the longer the use runs, so the use is restarted every other tick
 *   and the arms stay where it starts.</li>
 *   <li>{@link Stance#SHOULDER}: aiming, a shot fired in the last moment, or sliding (sat on the
 *   slide's seat, SlideService): the gun as a charged crossbow, which is the two-handed aim along the
 *   look (crossbow hold).</li>
 *   <li>{@link Stance#RELOAD}: low, but the use runs on, so the support arm comes up to the
 *   magazine; it is restarted as the new magazine goes in and comes up again.</li>
 *   <li>{@link Stance#THROW}: a grenade being cooked is held overhead (the trident throw).</li>
 *   <li>{@link Stance#DOWNED}: crawling with empty hands.</li>
 *   <li>{@link Stance#REVIVE}: working on a downed squadmate with a bandage (the brush pose).</li>
 * </ul>
 *
 * <p>Everything here is what <em>others</em> are sent (see {@link Nms.ViewRewriter}): the player's own
 * client keeps the real item and is never told they are using it, so first person, ADS, the
 * reload frames and the trigger (which needs the client to be digging, not using) are untouched.
 * Kill-replay stand-ins are posed the same way ({@link #pose}). The gun's item model shows its own
 * third-person variant for each stance (tools/art/build.py: the low carry on custom_model_data flag
 * 0, the shouldered one on the crossbow's charge).
 */
public final class StanceService implements Listener, Nms.ViewRewriter {

    public enum Stance {
        /** Nothing to pose: the real item as vanilla shows it (a knife, a med, swimming, a spectator). */
        NONE,
        LOW,
        SHOULDER,
        RELOAD,
        THROW,
        DOWNED,
        REVIVE;

        /** The stances shown by telling others the item in hand is being used. */
        boolean using() {
            return this == LOW || this == RELOAD || this == REVIVE;
        }

        /** The stances with a gun (or a bandage) held in both hands: arm swings are not shown. */
        boolean twoHanded() {
            return this == LOW || this == SHOULDER || this == RELOAD || this == REVIVE || this == DOWNED;
        }
    }

    /** Ticks a gun stays shouldered after its last shot, and on a sprint (run-and-gun). */
    private static final int FIRE_TICKS = 30;
    private static final int SPRINT_FIRE_TICKS = 10;
    /** Reload progress at which the new magazine goes in (tools/art/reload.py MAG_KEYS): the support arm comes up again. */
    private static final double MAG_IN = 0.55;
    /** Bit 1 of the living-entity flags: using the item in hand (bit 2 would make it the off hand). */
    private static final byte USING = 1;

    private final CraftovPlugin plugin;
    /** Stance by entity id, read by the Netty threads; NONE is left out. */
    private final Map<Integer, Stance> stances = new ConcurrentHashMap<>();
    /**
     * Entity ids of those crouching (read by the Netty threads): the arms swing back with the body's
     * lean, and the gun's model turns it back in the hand (custom_model_data flag 1).
     */
    private final Set<Integer> crouching = ConcurrentHashMap.newKeySet();
    /** Entity ids of players with a gun in hand at all (middle click goes to the tactical light). */
    private final Set<Integer> armed = ConcurrentHashMap.newKeySet();
    /** Kill-replay stand-ins posed by {@link #pose}, by entity id. */
    private final Map<Integer, LivingEntity> standIns = new HashMap<>();
    /** Who has been sent each posed entity's use since its stance last changed, by entity id. */
    private final Map<Integer, Set<UUID>> told = new HashMap<>();
    /** Reload progress last seen per player id, to restart the support arm as the magazine goes in. */
    private final Map<Integer, Double> reloadSeen = new HashMap<>();
    private final List<ItemStack> chargedArrow = List.of(ItemStack.of(Material.ARROW));
    private final ItemStack bandage;

    public StanceService(CraftovPlugin plugin) {
        this.plugin = plugin;
        bandage = ItemStack.of(Material.PAPER);
        bandage.setData(DataComponentTypes.ITEM_MODEL, Key.key("craftov", "bandage"));
        bandage.setData(DataComponentTypes.CONSUMABLE, use(ItemUseAnimation.BRUSH));
    }

    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Nms.installView(player, this);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    /** A use that never runs out while it is shown, silent and without particles. */
    private static Consumable use(ItemUseAnimation animation) {
        return Consumable.consumable()
                .consumeSeconds(3600f)
                .animation(animation)
                .sound(Key.key("minecraft", "intentionally_empty"))
                .hasConsumeParticles(false)
                .build();
    }

    private void tick() {
        long now = Bukkit.getCurrentTick();
        Set<Integer> seen = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            int id = player.getEntityId();
            seen.add(id);
            if (isGun(player.getInventory().getItemInMainHand())) {
                armed.add(id);
            } else {
                armed.remove(id);
            }
            update(player, want(player), player.isSneaking(), now);
        }
        for (Integer gone : standIns.entrySet().stream().filter(e -> !e.getValue().isValid()).map(Map.Entry::getKey).toList()) {
            forget(gone);
        }
        for (Map.Entry<Integer, LivingEntity> entry : standIns.entrySet()) {
            seen.add(entry.getKey());
            Stance stance = stances.getOrDefault(entry.getKey(), Stance.NONE);
            keepUsing(entry.getValue(), stance, false, now);
        }
        stances.keySet().retainAll(seen);
        crouching.retainAll(seen);
        armed.retainAll(seen);
        told.keySet().retainAll(seen);
        reloadSeen.keySet().retainAll(seen);
    }

    /** Moves a player to {@code want}, telling everyone who sees them, and keeps a use going. */
    private void update(Player player, Stance want, boolean crouched, long now) {
        int id = player.getEntityId();
        boolean restart = false;
        if (want == Stance.RELOAD) {
            WeaponService.GunState state = plugin.weapons().state(player);
            double progress = state == null ? 0 : state.reloadProgress();
            Double before = reloadSeen.put(id, progress);
            restart = before != null && before < MAG_IN && progress >= MAG_IN;
        } else {
            reloadSeen.remove(id);
        }
        show(player, player.getInventory().getItemInMainHand(), want, crouched);
        keepUsing(player, want, restart, now);
    }

    /**
     * Records the entity's stance and, if it or the crouch changed, sends whoever sees it the real
     * item in hand again: the view handler turns it into what the new stance shows, and the use (or
     * its end) follows, so the client starts the use with the right item in hand.
     */
    private void show(LivingEntity entity, ItemStack hand, Stance want, boolean crouched) {
        int id = entity.getEntityId();
        Stance had = want == Stance.NONE ? stances.remove(id) : stances.put(id, want);
        if (had == null) {
            had = Stance.NONE;
        }
        boolean crouchChanged = crouched ? crouching.add(id) : crouching.remove(id);
        if (want == had && !(crouchChanged && want != Stance.NONE)) {
            return;
        }
        if (want != had) {
            told.remove(id);
        }
        for (Player viewer : entity.getTrackedBy()) {
            viewer.sendEquipmentChange(entity, EquipmentSlot.HAND, hand);
            if (had.using() && !want.using()) {
                Nms.sendLivingFlags(viewer, id, (byte) 0);
            }
        }
    }

    /**
     * Sends the use to whoever sees the entity and has not been sent it since the stance changed (a
     * new stance, or someone who just came into view), and restarts it: every other tick for the low
     * carry, whose pose drifts as the use runs, and on {@code restart} for a reload. Someone who just
     * came into view of an empty-handed reviver is sent the hand first: an empty hand is not among
     * what the server sends of an entity coming into view, so the bandage would never reach them.
     */
    private void keepUsing(LivingEntity entity, Stance stance, boolean restart, long now) {
        if (!stance.using()) {
            return;
        }
        int id = entity.getEntityId();
        Set<UUID> sent = told.computeIfAbsent(id, k -> new HashSet<>());
        boolean all = restart || stance == Stance.LOW && now % 2 == 0;
        Set<UUID> present = new HashSet<>();
        for (Player viewer : entity.getTrackedBy()) {
            present.add(viewer.getUniqueId());
            boolean fresh = !sent.contains(viewer.getUniqueId());
            if (fresh && stance == Stance.REVIVE && entity.getEquipment() != null && entity.getEquipment().getItemInMainHand().isEmpty()) {
                viewer.sendEquipmentChange(entity, EquipmentSlot.HAND, ItemStack.empty());
            }
            if (all || fresh) {
                Nms.sendLivingFlags(viewer, id, (byte) 0, USING);
                sent.add(viewer.getUniqueId());
            }
        }
        sent.retainAll(present);
    }

    private Stance want(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return Stance.NONE;
        }
        if (plugin.downed().isDowned(player)) {
            return Stance.DOWNED;
        }
        if (plugin.downed().isReviving(player)) {
            return Stance.REVIVE;
        }
        if (player.hasActiveItem() && isThrowable(player.getActiveItem())) {
            return Stance.THROW;
        }
        // A slide's seat is not a vehicle: the player sits on it gun up (see below).
        boolean sliding = plugin.slides().isSliding(player);
        if (player.isSwimming() || player.isGliding() || player.isInsideVehicle() && !sliding
                || !isGun(player.getInventory().getItemInMainHand())) {
            return Stance.NONE;
        }
        WeaponService.GunState state = plugin.weapons().state(player);
        if (state == null || state.slot != player.getInventory().getHeldItemSlot()) {
            return Stance.LOW;
        }
        if (state.reloading()) {
            return Stance.RELOAD;
        }
        if (state.drawing()) {
            return Stance.LOW;
        }
        if (plugin.weapons().isAiming(player)) {
            return Stance.SHOULDER;
        }
        if (sliding) {
            // Sliding in, gun up: seated with the gun along the look, ready to fire.
            return Stance.SHOULDER;
        }
        long since = Bukkit.getCurrentTick() - state.lastShotTick;
        return state.lastShotTick > 0 && since <= (player.isSprinting() ? SPRINT_FIRE_TICKS : FIRE_TICKS) ? Stance.SHOULDER : Stance.LOW;
    }

    /** What the player is shown doing to others right now. */
    public Stance stanceOf(LivingEntity entity) {
        return stances.getOrDefault(entity.getEntityId(), Stance.NONE);
    }

    /**
     * Poses a kill-replay stand-in (a mannequin seen only by the player watching): its real
     * equipment is what the recorded player held, and it is shown in {@code stance} as a player
     * would be. Call again whenever the recorded stance changes; {@link #release} when it goes.
     */
    public void pose(LivingEntity standIn, Stance stance, boolean crouched) {
        standIns.put(standIn.getEntityId(), standIn);
        ItemStack hand = standIn.getEquipment() == null ? ItemStack.empty() : standIn.getEquipment().getItemInMainHand();
        show(standIn, hand, stance, crouched);
        keepUsing(standIn, stance, false, Bukkit.getCurrentTick());
    }

    public void release(LivingEntity standIn) {
        forget(standIn.getEntityId());
    }

    private void forget(int id) {
        standIns.remove(id);
        stances.remove(id);
        crouching.remove(id);
        told.remove(id);
    }

    /**
     * The stance a recorded moment shows, for a kill replay: what {@link #stanceOf} says, except
     * that a soldier (which has no stance of its own) shoulders its gun.
     */
    public Stance recorded(LivingEntity entity) {
        return entity instanceof Player ? stanceOf(entity) : Stance.SHOULDER;
    }

    private boolean isGun(@Nullable ItemStack stack) {
        ItemDef def = defOf(stack);
        return def != null && def.isGun();
    }

    private boolean isThrowable(@Nullable ItemStack stack) {
        ItemDef def = defOf(stack);
        return def != null && def.throwable() != null;
    }

    private @Nullable ItemDef defOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getType() != Material.PAPER) {
            return null;
        }
        String id = stack.getPersistentDataContainer().get(Keys.ITEM_ID, PersistentDataType.STRING);
        return id == null ? null : plugin.registry().get(id);
    }

    // ------------------------------------------------------------------ Netty side

    @Override
    public boolean raised(int entityId) {
        Stance stance = stances.get(entityId);
        return stance != null && stance.twoHanded();
    }

    @Override
    public boolean takesPick(int entityId) {
        return armed.contains(entityId);
    }

    @Override
    public @Nullable ItemStack mainHand(int entityId, ItemStack real) {
        Stance stance = stances.get(entityId);
        if (stance == null) {
            return null;
        }
        boolean crouched = crouching.contains(entityId);
        return switch (stance) {
            case NONE -> null;
            case LOW, RELOAD -> isGun(real) ? lowCopy(real, crouched) : null;
            case SHOULDER -> isGun(real) ? raisedCopy(real, crouched) : null;
            case THROW -> isThrowable(real) ? withUse(real, ItemUseAnimation.TRIDENT) : null;
            case DOWNED -> ItemStack.empty();
            case REVIVE -> bandage.clone();
        };
    }

    /** Other players' vanilla footsteps, which FootstepService replaces with its own. */
    @Override
    public boolean dropsSound(String key, String source) {
        var footsteps = plugin.footsteps();
        return footsteps != null && footsteps.replaces(key, source);
    }

    @Override
    public byte livingFlags(int entityId, byte real) {
        Stance stance = stances.get(entityId);
        if (stance == null || stance == Stance.NONE || stance == Stance.THROW) {
            return real;
        }
        // Using the main-hand item for the poses that are a use; for the rest whatever the server says
        // less the use, which a stale packet from before the stance could still carry.
        return stance.using() ? (byte) ((real & ~3) | USING) : (byte) (real & ~3);
    }

    /** The gun as a charged crossbow keeping its model: held that way, a humanoid shoulders it. */
    public ItemStack raisedCopy(ItemStack real, boolean crouched) {
        ItemStack shown = real.withType(Material.CROSSBOW);
        // Crossbows are damageable, so the stack must stay unstackable or the packet fails to encode.
        shown.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        shown.setAmount(1);
        shown.unsetData(DataComponentTypes.LORE);
        shown.setData(DataComponentTypes.CHARGED_PROJECTILES, ChargedProjectiles.chargedProjectiles(chargedArrow));
        flags(shown, real, false, crouched);
        return shown;
    }

    /**
     * The gun carried low: a use with the crossbow's animation (the crossbow-charge pose while it is
     * used) and custom_model_data flag 0, on which the item model holds the gun low and level.
     */
    private ItemStack lowCopy(ItemStack real, boolean crouched) {
        ItemStack shown = withUse(real, ItemUseAnimation.CROSSBOW);
        flags(shown, real, true, crouched);
        return shown;
    }

    /** custom_model_data flags 0 (carried low) and 1 (crouching), the real stack's strings (the attachments) kept. */
    private static void flags(ItemStack shown, ItemStack real, boolean low, boolean crouched) {
        CustomModelData data = real.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        CustomModelData.Builder cmd = CustomModelData.customModelData().addFlag(low).addFlag(crouched);
        if (data != null) {
            cmd.addStrings(data.strings());
        }
        shown.setData(DataComponentTypes.CUSTOM_MODEL_DATA, cmd.build());
    }

    private static ItemStack withUse(ItemStack real, ItemUseAnimation animation) {
        ItemStack shown = real.clone();
        shown.unsetData(DataComponentTypes.LORE);
        shown.setData(DataComponentTypes.CONSUMABLE, use(animation));
        return shown;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Nms.installView(event.getPlayer(), this);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        int id = event.getPlayer().getEntityId();
        stances.remove(id);
        armed.remove(id);
        told.remove(id);
        reloadSeen.remove(id);
    }
}
