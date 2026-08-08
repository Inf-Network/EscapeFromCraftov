package com.birditch.raidcore.lobby.menu;

import com.birditch.raidcore.api.cosmetics.Cosmetic;
import com.birditch.raidcore.api.cosmetics.CosmeticId;
import com.birditch.raidcore.api.cosmetics.CosmeticsService;
import com.birditch.raidcore.api.settings.PlayerSettingsService;
import com.birditch.raidcore.api.settings.SettingKey;
import com.birditch.raidcore.bukkit.Messages;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/// Hypixel-style lobby menus: 27-slot chests with a black glass border, gold
/// item names, gray lore and a yellow action hint. Each menu is an
/// [InventoryHolder] so [MenuListener] can route clicks without title matching.
public final class LobbyMenus {

  private static final int SIZE = 27;

  private LobbyMenus() {}

  /// Marker for every inventory owned by the lobby menu system.
  public sealed interface Menu extends InventoryHolder permits MainMenu, SettingsMenu, CosmeticsMenu {}

  public static final class MainMenu implements Menu {

    public static final int PLAY_SLOT = 11;
    public static final int SETTINGS_SLOT = 13;
    public static final int COSMETICS_SLOT = 15;
    public static final int CLOSE_SLOT = 22;

    private @Nullable Inventory inventory;

    private MainMenu() {}

    public static Inventory build(Messages messages) {
      Preconditions.checkNotNull(messages, "messages");
      MainMenu holder = new MainMenu();
      Inventory inventory = Bukkit.createInventory(holder, SIZE, messages.raw("menu.main.title"));
      holder.inventory = inventory;
      fillBorder(inventory);
      inventory.setItem(PLAY_SLOT, item(Material.COMPASS,
          messages.raw("menu.main.play.name"),
          loreWithHint(messages, "menu.main.play.lore", "menu.main.play.hint"), false));
      inventory.setItem(SETTINGS_SLOT, item(Material.COMPARATOR,
          messages.raw("menu.main.settings.name"),
          loreWithHint(messages, "menu.main.settings.lore", "menu.main.settings.hint"), false));
      inventory.setItem(COSMETICS_SLOT, item(Material.GOLDEN_HELMET,
          messages.raw("menu.main.cosmetics.name"),
          loreWithHint(messages, "menu.main.cosmetics.lore", "menu.main.cosmetics.hint"), false));
      inventory.setItem(CLOSE_SLOT, item(Material.BARRIER, messages.raw("menu.close"), List.of(), false));
      return inventory;
    }

    @Override
    public Inventory getInventory() {
      return Preconditions.checkNotNull(inventory, "inventory");
    }
  }

  public static final class SettingsMenu implements Menu {

    public static final int VISIBILITY_SLOT = 11;
    public static final int QUEUE_MESSAGES_SLOT = 15;
    public static final int BACK_SLOT = 22;

    private @Nullable Inventory inventory;

    private SettingsMenu() {}

    public static Inventory build(Messages messages, PlayerSettingsService settings, UUID playerId) {
      Preconditions.checkNotNull(messages, "messages");
      Preconditions.checkNotNull(settings, "settings");
      Preconditions.checkNotNull(playerId, "playerId");
      SettingsMenu holder = new SettingsMenu();
      Inventory inventory = Bukkit.createInventory(holder, SIZE, messages.raw("menu.settings.title"));
      holder.inventory = inventory;
      fillBorder(inventory);
      inventory.setItem(VISIBILITY_SLOT,
          toggleItem(messages, SettingKey.LOBBY_PLAYER_VISIBILITY,
              settings.get(playerId, SettingKey.LOBBY_PLAYER_VISIBILITY)));
      inventory.setItem(QUEUE_MESSAGES_SLOT,
          toggleItem(messages, SettingKey.QUEUE_STATUS_MESSAGES,
              settings.get(playerId, SettingKey.QUEUE_STATUS_MESSAGES)));
      inventory.setItem(BACK_SLOT, item(Material.ARROW, messages.raw("menu.back"), List.of(), false));
      return inventory;
    }

    /// The setting toggled by a content slot of this menu, if any.
    public static Optional<SettingKey> settingAt(int slot) {
      return switch (slot) {
        case VISIBILITY_SLOT -> Optional.of(SettingKey.LOBBY_PLAYER_VISIBILITY);
        case QUEUE_MESSAGES_SLOT -> Optional.of(SettingKey.QUEUE_STATUS_MESSAGES);
        default -> Optional.empty();
      };
    }

    private static ItemStack toggleItem(Messages messages, SettingKey key, boolean enabled) {
      String base = "menu.settings." + key.name().toLowerCase(Locale.ROOT);
      List<Component> lore = new ArrayList<>(messages.lines(base + ".lore"));
      lore.add(messages.raw(enabled ? "menu.settings.state-on" : "menu.settings.state-off"));
      lore.add(messages.raw("menu.settings.hint"));
      return item(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
          messages.raw(base + ".name"), lore, enabled);
    }

    @Override
    public Inventory getInventory() {
      return Preconditions.checkNotNull(inventory, "inventory");
    }
  }

  public static final class CosmeticsMenu implements Menu {

    public static final int UNEQUIP_SLOT = 15;
    public static final int BACK_SLOT = 22;
    private static final int FIRST_CATALOG_SLOT = 10;

    private final Map<Integer, CosmeticId> cosmeticSlots = new HashMap<>();
    private @Nullable Inventory inventory;

    private CosmeticsMenu() {}

    public static Inventory build(Messages messages, CosmeticsService cosmetics, UUID playerId) {
      Preconditions.checkNotNull(messages, "messages");
      Preconditions.checkNotNull(cosmetics, "cosmetics");
      Preconditions.checkNotNull(playerId, "playerId");
      CosmeticsMenu holder = new CosmeticsMenu();
      Inventory inventory = Bukkit.createInventory(holder, SIZE, messages.raw("menu.cosmetics.title"));
      holder.inventory = inventory;
      fillBorder(inventory);
      Optional<CosmeticId> equipped = cosmetics.equipped(playerId).map(Cosmetic::id);
      int slot = FIRST_CATALOG_SLOT;
      for (Cosmetic cosmetic : cosmetics.catalog()) {
        if (slot >= UNEQUIP_SLOT) {
          break;
        }
        boolean isEquipped = equipped.filter(cosmetic.id()::equals).isPresent();
        List<Component> lore = new ArrayList<>();
        if (isEquipped) {
          lore.add(messages.raw("menu.cosmetics.equipped"));
        }
        lore.add(messages.raw("menu.cosmetics.hint"));
        Material material = Optional.ofNullable(Material.matchMaterial(cosmetic.materialKey()))
            .orElse(Material.LEATHER_HELMET);
        inventory.setItem(slot, item(material,
            Component.text(cosmetic.displayName()).decoration(TextDecoration.ITALIC, false),
            lore, isEquipped));
        holder.cosmeticSlots.put(slot, cosmetic.id());
        slot++;
      }
      inventory.setItem(UNEQUIP_SLOT, item(Material.BARRIER,
          messages.raw("menu.cosmetics.unequip.name"),
          loreWithHint(messages, "menu.cosmetics.unequip.lore", "menu.cosmetics.unequip.hint"), false));
      inventory.setItem(BACK_SLOT, item(Material.ARROW, messages.raw("menu.back"), List.of(), false));
      return inventory;
    }

    public Optional<CosmeticId> cosmeticAt(int slot) {
      return Optional.ofNullable(cosmeticSlots.get(slot));
    }

    @Override
    public Inventory getInventory() {
      return Preconditions.checkNotNull(inventory, "inventory");
    }
  }

  private static void fillBorder(Inventory inventory) {
    ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE,
        Component.text(" "), List.of(), false);
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      inventory.setItem(slot, pane);
    }
  }

  private static List<Component> loreWithHint(Messages messages, String loreKey, String hintKey) {
    List<Component> lore = new ArrayList<>(messages.lines(loreKey));
    lore.add(messages.raw(hintKey));
    return lore;
  }

  private static ItemStack item(Material material, Component name, List<Component> lore, boolean glint) {
    ItemStack stack = new ItemStack(material);
    stack.editMeta(meta -> {
      meta.itemName(name);
      if (!lore.isEmpty()) {
        meta.lore(lore.stream()
            .map(line -> line.decoration(TextDecoration.ITALIC, false))
            .toList());
      }
      if (glint) {
        meta.setEnchantmentGlintOverride(true);
      }
    });
    return stack;
  }
}
