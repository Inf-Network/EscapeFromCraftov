package com.birditch.raidcore.lobby.menu;

import com.birditch.raidcore.api.cosmetics.Cosmetic;
import com.birditch.raidcore.api.cosmetics.CosmeticId;
import com.birditch.raidcore.api.settings.SettingKey;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.lobby.CosmeticApplier;
import com.birditch.raidcore.lobby.LobbyVisibility;
import com.birditch.raidcore.lobby.PlayAction;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.jspecify.annotations.Nullable;

/// Routes clicks inside the lobby menus. Every interaction with a menu
/// inventory is cancelled (items can never be taken out); async persistence
/// hops back to the main thread before touching the player.
public final class MenuListener implements Listener {

  private final MenuDeps deps;

  public MenuListener(MenuDeps deps) {
    this.deps = Preconditions.checkNotNull(deps, "deps");
  }

  @EventHandler
  public void onClick(InventoryClickEvent event) {
    Inventory top = event.getInventory();
    if (!(top.getHolder() instanceof LobbyMenus.Menu menu)) {
      return;
    }
    event.setCancelled(true);
    if (event.getClickedInventory() != top || !(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    switch (menu) {
      case LobbyMenus.MainMenu _ -> handleMain(player, event.getSlot());
      case LobbyMenus.SettingsMenu _ -> handleSettings(player, event.getSlot());
      case LobbyMenus.CosmeticsMenu cosmeticsMenu -> handleCosmetics(player, cosmeticsMenu, event.getSlot());
    }
  }

  @EventHandler
  public void onDrag(InventoryDragEvent event) {
    if (event.getInventory().getHolder() instanceof LobbyMenus.Menu) {
      event.setCancelled(true);
    }
  }

  private void handleMain(Player player, int slot) {
    switch (slot) {
      case LobbyMenus.MainMenu.PLAY_SLOT -> {
        player.closeInventory();
        PlayAction.queue(deps.matchmaking(), deps.messages(), player, deps.config().defaultMap());
      }
      case LobbyMenus.MainMenu.SETTINGS_SLOT -> openSettings(player);
      case LobbyMenus.MainMenu.COSMETICS_SLOT -> openCosmetics(player);
      case LobbyMenus.MainMenu.CLOSE_SLOT -> player.closeInventory();
      default -> {}
    }
  }

  private void handleSettings(Player player, int slot) {
    if (slot == LobbyMenus.SettingsMenu.BACK_SLOT) {
      openMain(player);
      return;
    }
    LobbyMenus.SettingsMenu.settingAt(slot)
        .ifPresent(key -> toggleSetting(player, key));
  }

  private void toggleSetting(Player player, SettingKey key) {
    UUID playerId = player.getUniqueId();
    boolean newValue = !deps.settings().get(playerId, key);
    deps.settings().set(playerId, key, newValue).whenComplete((_, error) ->
        deps.scheduler().runSync(() -> afterToggle(player, key, newValue, error)));
  }

  private void afterToggle(Player player, SettingKey key, boolean newValue,
      @Nullable Throwable error) {
    if (error != null) {
      deps.logger().log(Level.SEVERE, "保存玩家设置失败:" + player.getName(), error);
      return;
    }
    if (!player.isOnline()) {
      return;
    }
    if (key == SettingKey.LOBBY_PLAYER_VISIBILITY) {
      LobbyVisibility.apply(deps.plugin(), deps.settings(), player);
    }
    String nameKey = "menu.settings." + key.name().toLowerCase(Locale.ROOT) + ".name";
    player.sendMessage(deps.messages().raw(newValue ? "settings.toggled-on" : "settings.toggled-off",
        Placeholder.component("setting", deps.messages().raw(nameKey))));
    openSettings(player);
  }

  private void handleCosmetics(Player player, LobbyMenus.CosmeticsMenu menu, int slot) {
    switch (slot) {
      case LobbyMenus.CosmeticsMenu.BACK_SLOT -> openMain(player);
      case LobbyMenus.CosmeticsMenu.UNEQUIP_SLOT -> unequip(player);
      default -> menu.cosmeticAt(slot).ifPresent(id -> equip(player, id));
    }
  }

  private void equip(Player player, CosmeticId id) {
    UUID playerId = player.getUniqueId();
    deps.cosmetics().equip(playerId, id).whenComplete((_, error) ->
        deps.scheduler().runSync(() -> afterEquip(player, error)));
  }

  private void afterEquip(Player player, @Nullable Throwable error) {
    if (error != null) {
      deps.logger().log(Level.SEVERE, "保存装扮失败:" + player.getName(), error);
      return;
    }
    if (!player.isOnline()) {
      return;
    }
    Cosmetic equipped = deps.cosmetics().equipped(player.getUniqueId()).orElse(null);
    CosmeticApplier.apply(player, equipped);
    if (equipped != null) {
      player.sendMessage(deps.messages().raw("cosmetics.equipped",
          Placeholder.unparsed("cosmetic", equipped.displayName())));
    }
    openCosmetics(player);
  }

  private void unequip(Player player) {
    deps.cosmetics().unequip(player.getUniqueId()).whenComplete((_, error) ->
        deps.scheduler().runSync(() -> afterUnequip(player, error)));
  }

  private void afterUnequip(Player player, @Nullable Throwable error) {
    if (error != null) {
      deps.logger().log(Level.SEVERE, "保存装扮失败:" + player.getName(), error);
      return;
    }
    if (!player.isOnline()) {
      return;
    }
    CosmeticApplier.apply(player, null);
    player.sendMessage(deps.messages().raw("cosmetics.unequipped"));
    openCosmetics(player);
  }

  private void openMain(Player player) {
    player.openInventory(LobbyMenus.MainMenu.build(deps.messages()));
  }

  private void openSettings(Player player) {
    player.openInventory(
        LobbyMenus.SettingsMenu.build(deps.messages(), deps.settings(), player.getUniqueId()));
  }

  private void openCosmetics(Player player) {
    player.openInventory(
        LobbyMenus.CosmeticsMenu.build(deps.messages(), deps.cosmetics(), player.getUniqueId()));
  }
}
