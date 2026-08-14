package com.birditch.escapefromcraftov.game.plugin.network;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/** Freezes direct or not-yet-validated Game joins until their assignment is accepted. */
public final class PendingPlayerGuard implements Listener {

  private final PendingJoinHandler pendingJoins;

  public PendingPlayerGuard(PendingJoinHandler pendingJoins) {
    this.pendingJoins = Preconditions.checkNotNull(pendingJoins, "pendingJoins");
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onMove(PlayerMoveEvent event) {
    if (!pending(event.getPlayer())) return;
    Location from = event.getFrom();
    Location to = event.getTo();
    if (to != null && (from.getX() != to.getX() || from.getY() != to.getY()
        || from.getZ() != to.getZ())) {
      event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(),
          to.getYaw(), to.getPitch()));
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onDamage(EntityDamageEvent event) {
    if (event.getEntity() instanceof Player player && pending(player)) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onAttack(EntityDamageByEntityEvent event) {
    Player attacker = attacker(event.getDamager());
    if (attacker != null && pending(attacker)) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onBreak(BlockBreakEvent event) {
    if (pending(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onPlace(BlockPlaceEvent event) {
    if (pending(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInteract(PlayerInteractEvent event) {
    if (pending(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInteractEntity(PlayerInteractEntityEvent event) {
    if (pending(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onDrop(PlayerDropItemEvent event) {
    if (pending(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onSwap(PlayerSwapHandItemsEvent event) {
    if (pending(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onConsume(PlayerItemConsumeEvent event) {
    if (pending(event.getPlayer())) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onPickup(EntityPickupItemEvent event) {
    if (event.getEntity() instanceof Player player && pending(player)) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInventoryClick(InventoryClickEvent event) {
    if (event.getWhoClicked() instanceof Player player && pending(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void onInventoryDrag(InventoryDragEvent event) {
    if (event.getWhoClicked() instanceof Player player && pending(player)) {
      event.setCancelled(true);
    }
  }

  boolean pending(Player player) {
    return pendingJoins.isPending(player.getUniqueId());
  }

  private static Player attacker(Entity entity) {
    if (entity instanceof Player player) return player;
    if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
      return player;
    }
    return null;
  }
}
