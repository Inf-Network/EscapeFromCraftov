package com.birditch.escapefromcraftov.game.plugin.extraction;

import com.birditch.escapefromcraftov.api.item.ItemCategory;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Bukkit adapter that counts storage, armor and off-hand exactly once. */
public final class BukkitCarriedInventoryLookup implements CarriedInventoryLookup {

  private final CraftovItemStacks itemStacks;
  private final ItemRegistry items;
  private final Function<UUID, List<ItemInstance>> protectedItems;

  public BukkitCarriedInventoryLookup(CraftovItemStacks itemStacks, ItemRegistry items) {
    this(itemStacks, items, ignored -> List.of());
  }

  public BukkitCarriedInventoryLookup(CraftovItemStacks itemStacks, ItemRegistry items,
      Function<UUID, List<ItemInstance>> protectedItems) {
    this.itemStacks = Preconditions.checkNotNull(itemStacks, "itemStacks");
    this.items = Preconditions.checkNotNull(items, "items");
    this.protectedItems = Preconditions.checkNotNull(protectedItems, "protectedItems");
  }

  @Override
  public Optional<CarriedInventoryState> find(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) return Optional.empty();
    List<ItemStack> carried = new ArrayList<>();
    java.util.Collections.addAll(carried, player.getInventory().getStorageContents());
    java.util.Collections.addAll(carried, player.getInventory().getArmorContents());
    carried.add(player.getInventory().getItemInOffHand());
    long weight = 0;
    boolean backpack = false;
    for (ItemStack stack : carried) {
      var tagged = itemStacks.read(stack);
      if (tagged.isEmpty()) continue;
      ItemDefinition definition = items.require(tagged.get().itemId());
      backpack |= definition.category() == ItemCategory.BACKPACK;
      try {
        weight = Math.addExact(weight,
            Math.multiplyExact((long) definition.weightGrams(), stack.getAmount()));
      } catch (ArithmeticException overflow) {
        weight = Long.MAX_VALUE;
      }
    }
    for (ItemInstance item : protectedItems.apply(playerId)) {
      ItemDefinition definition = items.require(item.itemId());
      backpack |= definition.category() == ItemCategory.BACKPACK;
      try {
        weight = Math.addExact(weight,
            Math.multiplyExact((long) definition.weightGrams(), item.quantity()));
      } catch (ArithmeticException overflow) {
        weight = Long.MAX_VALUE;
      }
    }
    return Optional.of(new CarriedInventoryState(weight, backpack));
  }
}
