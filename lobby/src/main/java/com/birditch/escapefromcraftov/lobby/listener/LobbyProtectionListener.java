package com.birditch.escapefromcraftov.lobby.listener;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.lobby.LobbyConfig;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.PlayerInventory;

/// Keeps the lobby world pristine while `lobby.protect` is enabled: no block
/// changes (admins in creative excepted), no player damage or hunger, no item
/// drops, and the managed player inventory (Game Menu compass) stays immovable.
/// TrMenu owns its virtual menu slots; this listener only protects the real
/// player inventory underneath them.
public final class LobbyProtectionListener implements Listener {

  private static final String ADMIN_PERMISSION = "craftov.admin";

  private final LobbyConfig config;

  public LobbyProtectionListener(LobbyConfig config) {
    this.config = Preconditions.checkNotNull(config, "config");
  }

  @EventHandler
  public void onBlockBreak(BlockBreakEvent event) {
    if (config.protectLobby() && !isBuildingAdmin(event.getPlayer())) {
      event.setCancelled(true);
    }
  }

  @EventHandler
  public void onBlockPlace(BlockPlaceEvent event) {
    if (config.protectLobby() && !isBuildingAdmin(event.getPlayer())) {
      event.setCancelled(true);
    }
  }

  @EventHandler
  public void onEntityDamage(EntityDamageEvent event) {
    if (config.protectLobby() && event.getEntity() instanceof Player) {
      event.setCancelled(true);
    }
  }

  @EventHandler
  public void onFoodLevelChange(FoodLevelChangeEvent event) {
    if (config.protectLobby() && event.getEntity() instanceof Player) {
      event.setCancelled(true);
    }
  }

  @EventHandler
  public void onItemDrop(PlayerDropItemEvent event) {
    if (config.protectLobby()) {
      event.setCancelled(true);
    }
  }

  @EventHandler
  public void onInventoryClick(InventoryClickEvent event) {
    if (config.protectLobby() && event.getClickedInventory() instanceof PlayerInventory) {
      event.setCancelled(true);
    }
  }

  private static boolean isBuildingAdmin(Player player) {
    return player.hasPermission(ADMIN_PERMISSION) && player.getGameMode() == GameMode.CREATIVE;
  }
}
