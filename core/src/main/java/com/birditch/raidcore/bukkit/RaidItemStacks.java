package com.birditch.raidcore.bukkit;

import com.birditch.raidcore.api.inventory.StoredItem;
import com.birditch.raidcore.api.item.ItemDefinition;
import com.birditch.raidcore.api.item.ItemId;
import com.birditch.raidcore.api.item.ItemInstance;
import com.birditch.raidcore.api.item.ItemRarity;
import com.birditch.raidcore.api.item.ItemRegistry;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

/// Single Bukkit representation for every durable and in-raid RaidCore item.
public final class RaidItemStacks {

  public static final NamespacedKey ITEM_ID_KEY = key("raidcore:item_id");
  public static final NamespacedKey INSTANCE_ID_KEY = key("raidcore:instance_id");
  public static final NamespacedKey STORAGE_ID_KEY = key("raidcore:storage_id");

  private static final Map<String, Material> MATERIALS = Map.ofEntries(
      Map.entry("iron_sword", Material.IRON_SWORD),
      Map.entry("iron_axe", Material.IRON_AXE),
      Map.entry("bow", Material.BOW),
      Map.entry("crossbow", Material.CROSSBOW),
      Map.entry("arrow", Material.ARROW),
      Map.entry("shield", Material.SHIELD),
      Map.entry("iron_chestplate", Material.IRON_CHESTPLATE),
      Map.entry("healing_potion", Material.POTION),
      Map.entry("golden_apple", Material.GOLDEN_APPLE),
      Map.entry("emerald", Material.EMERALD),
      Map.entry("echo_shard", Material.ECHO_SHARD),
      Map.entry("ancient_debris", Material.ANCIENT_DEBRIS),
      Map.entry("extraction_key", Material.TRIPWIRE_HOOK));

  private final ItemRegistry registry;

  public RaidItemStacks(ItemRegistry registry) {
    this.registry = Preconditions.checkNotNull(registry, "registry");
  }

  public List<ItemStack> fromStored(StoredItem stored) {
    Preconditions.checkNotNull(stored, "stored");
    return build(stored.itemId(), stored.quantity(), stored.storageId());
  }

  public List<ItemStack> fromInstance(ItemInstance instance) {
    Preconditions.checkNotNull(instance, "instance");
    return build(instance.itemId(), instance.quantity(), null);
  }

  public List<ItemInstance> scan(ItemStack[] contents) {
    Preconditions.checkNotNull(contents, "contents");
    List<ItemInstance> result = new ArrayList<>();
    for (ItemStack stack : contents) {
      read(stack).ifPresent(item -> result.add(new ItemInstance(
          item.instanceId(), item.itemId(), stack.getAmount())));
    }
    return List.copyOf(result);
  }

  public Optional<TaggedItem> read(ItemStack stack) {
    if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) return Optional.empty();
    PersistentDataContainer data = stack.getItemMeta().getPersistentDataContainer();
    String rawId = data.get(ITEM_ID_KEY, PersistentDataType.STRING);
    if (rawId == null) return Optional.empty();
    ItemId itemId;
    try {
      itemId = ItemId.of(rawId);
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
    if (registry.find(itemId).isEmpty()) return Optional.empty();
    UUID instanceId = uuid(data.get(INSTANCE_ID_KEY, PersistentDataType.STRING));
    UUID storageId = uuid(data.get(STORAGE_ID_KEY, PersistentDataType.STRING));
    return Optional.of(new TaggedItem(itemId,
        instanceId == null ? UUID.randomUUID() : instanceId, storageId));
  }

  public boolean isRaidItem(ItemStack stack) {
    return read(stack).isPresent();
  }

  public int usedSlots(ItemStack[] contents) {
    int used = 0;
    for (ItemStack stack : contents) if (isRaidItem(stack)) used++;
    return used;
  }

  public long totalValue(ItemStack[] contents) {
    long total = 0;
    for (ItemStack stack : contents) {
      Optional<TaggedItem> tagged = read(stack);
      if (tagged.isEmpty()) continue;
      long value = registry.require(tagged.get().itemId()).basePrice().units();
      try {
        total = Math.addExact(total, Math.multiplyExact(value, stack.getAmount()));
      } catch (ArithmeticException ignored) {
        return Long.MAX_VALUE;
      }
    }
    return total;
  }

  private List<ItemStack> build(ItemId itemId, int quantity, UUID storageId) {
    Preconditions.checkArgument(quantity >= 1, "quantity must be positive");
    ItemDefinition definition = registry.require(itemId);
    Material material = MATERIALS.getOrDefault(itemId.value(), Material.PAPER);
    int maximum = Math.max(1, Math.min(definition.stackSize(), material.getMaxStackSize()));
    List<ItemStack> result = new ArrayList<>();
    int remaining = quantity;
    while (remaining > 0) {
      int amount = Math.min(remaining, maximum);
      result.add(buildStack(definition, material, amount, storageId));
      remaining -= amount;
    }
    return List.copyOf(result);
  }

  private ItemStack buildStack(ItemDefinition definition, Material material, int amount,
      UUID storageId) {
    ItemStack stack = new ItemStack(material, amount);
    UUID instanceId = UUID.randomUUID();
    stack.editMeta(meta -> {
      meta.itemName(Component.text(definition.displayName(), color(definition.rarity()))
          .decoration(TextDecoration.ITALIC, false));
      meta.lore(List.of(
          Component.text("稀有度: " + rarityName(definition.rarity()), NamedTextColor.GRAY)
              .decoration(TextDecoration.ITALIC, false),
          Component.text("价值: " + definition.basePrice().format() + " 金币",
                  NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
          Component.text("重量: " + definition.weightGrams() + "g", NamedTextColor.DARK_GRAY)
              .decoration(TextDecoration.ITALIC, false)));
      PersistentDataContainer data = meta.getPersistentDataContainer();
      data.set(ITEM_ID_KEY, PersistentDataType.STRING, definition.id().value());
      data.set(INSTANCE_ID_KEY, PersistentDataType.STRING, instanceId.toString());
      if (storageId != null) {
        data.set(STORAGE_ID_KEY, PersistentDataType.STRING, storageId.toString());
      }
      if (meta instanceof PotionMeta potion && definition.id().value().equals("healing_potion")) {
        potion.setBasePotionType(PotionType.HEALING);
      }
    });
    return stack;
  }

  private static NamespacedKey key(String value) {
    return Objects.requireNonNull(NamespacedKey.fromString(value));
  }

  private static UUID uuid(String value) {
    if (value == null) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private static NamedTextColor color(ItemRarity rarity) {
    return switch (rarity) {
      case COMMON -> NamedTextColor.WHITE;
      case UNCOMMON -> NamedTextColor.GREEN;
      case RARE -> NamedTextColor.AQUA;
      case EPIC -> NamedTextColor.LIGHT_PURPLE;
      case LEGENDARY -> NamedTextColor.GOLD;
      case ARTIFACT -> NamedTextColor.RED;
    };
  }

  private static String rarityName(ItemRarity rarity) {
    return switch (rarity) {
      case COMMON -> "普通";
      case UNCOMMON -> "精良";
      case RARE -> "稀有";
      case EPIC -> "史诗";
      case LEGENDARY -> "传说";
      case ARTIFACT -> "遗物";
    };
  }

  public record TaggedItem(ItemId itemId, UUID instanceId, UUID storageId) {}
}
