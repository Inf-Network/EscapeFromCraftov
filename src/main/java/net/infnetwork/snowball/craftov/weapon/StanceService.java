package net.infnetwork.snowball.craftov.weapon;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ChargedProjectiles;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.util.Nms;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
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

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How other players see someone holding a gun. Vanilla only has one two-handed aiming pose that a
 * server can trigger without a mod: holding a charged crossbow. So while a player has a gun up, the
 * gun is sent to everyone else as a charged crossbow that keeps the gun's item model (the model
 * switches to a variant posed for that stance on {@code charge_type}); the holder's own client keeps
 * the real item, so first person, ADS and reload frames are untouched. Sprinting, reloading, being
 * downed or swimming lowers the gun back to the normal one-handed carry.
 */
public final class StanceService implements Listener, Nms.ViewRewriter {

    private final CraftovPlugin plugin;
    /** Entity ids of players holding a gun up, read by the Netty threads. */
    private final Set<Integer> raised = ConcurrentHashMap.newKeySet();
    /** Entity ids of players with a gun in hand at all (middle click goes to the tactical light). */
    private final Set<Integer> armed = ConcurrentHashMap.newKeySet();
    private final List<ItemStack> chargedArrow = List.of(ItemStack.of(Material.ARROW));

    public StanceService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Nms.installView(player, this);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L);
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isGun(player.getInventory().getItemInMainHand())) {
                armed.add(player.getEntityId());
            } else {
                armed.remove(player.getEntityId());
            }
            boolean want = wantsRaised(player);
            boolean changed = want ? raised.add(player.getEntityId()) : raised.remove(player.getEntityId());
            if (changed) {
                // Resend the real item; the view handler turns it into whatever the new stance shows.
                ItemStack hand = player.getInventory().getItemInMainHand();
                for (Player viewer : player.getTrackedBy()) {
                    viewer.sendEquipmentChange(player, EquipmentSlot.HAND, hand);
                }
            }
        }
    }

    private boolean wantsRaised(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR || player.isSprinting() || player.isSwimming() || player.isGliding()
                || player.isInsideVehicle() || plugin.downed().isDowned(player) || !isGun(player.getInventory().getItemInMainHand())) {
            return false;
        }
        WeaponService.GunState state = plugin.weapons().state(player);
        return state == null || !state.reloading();
    }

    private boolean isGun(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getType() != Material.PAPER) {
            return false;
        }
        String id = stack.getPersistentDataContainer().get(Keys.ITEM_ID, PersistentDataType.STRING);
        ItemDef def = id == null ? null : plugin.registry().get(id);
        return def != null && def.isGun();
    }

    // ------------------------------------------------------------------ Netty side

    @Override
    public boolean raised(int entityId) {
        return raised.contains(entityId);
    }

    @Override
    public boolean takesPick(int entityId) {
        return armed.contains(entityId);
    }

    @Override
    public @Nullable ItemStack mainHand(int entityId, ItemStack real) {
        if (!raised.contains(entityId) || !isGun(real)) {
            return null;
        }
        ItemStack shown = real.withType(Material.CROSSBOW);
        // Crossbows are damageable, so the stack must stay unstackable or the packet fails to encode.
        shown.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        shown.setAmount(1);
        shown.unsetData(DataComponentTypes.LORE);
        shown.setData(DataComponentTypes.CHARGED_PROJECTILES, ChargedProjectiles.chargedProjectiles(chargedArrow));
        return shown;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Nms.installView(event.getPlayer(), this);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        raised.remove(event.getPlayer().getEntityId());
        armed.remove(event.getPlayer().getEntityId());
    }
}
