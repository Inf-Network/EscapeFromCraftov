package com.birditch.escapefromcraftov.bukkit;

import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.item.ItemRarity;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;
import org.jspecify.annotations.Nullable;

/// Single Bukkit representation for every durable and in-craftov EscapeFromCraftov item.
public final class CraftovItemStacks {

  public static final NamespacedKey ITEM_ID_KEY = key("escapefromcraftov:item_id");
  public static final NamespacedKey INSTANCE_ID_KEY = key("escapefromcraftov:instance_id");
  public static final NamespacedKey STORAGE_ID_KEY = key("escapefromcraftov:storage_id");

  private static final String PAYLOAD_PREFIX = "paper-itemstack:1:";
  private static final int MAX_PAYLOAD_CHARACTERS = 2_000_000;
  private static final String MODEL_NAMESPACE = "escapefromcraftov";
  private static final Set<String> MODELLED_ITEMS = Set.of("basic_rig", "patrol_backpack");

  private static final Map<String, Material> MATERIALS = Map.ofEntries(
      Map.entry("iron_sword", Material.IRON_SWORD),
      Map.entry("iron_axe", Material.IRON_AXE),
      Map.entry("bow", Material.BOW),
      Map.entry("crossbow", Material.CROSSBOW),
      Map.entry("arrow", Material.ARROW),
      Map.entry("shield", Material.SHIELD),
      Map.entry("iron_chestplate", Material.IRON_CHESTPLATE),
      Map.entry("basic_rig", Material.LEATHER_CHESTPLATE),
      Map.entry("patrol_backpack", Material.BUNDLE),
      Map.entry("healing_potion", Material.POTION),
      Map.entry("golden_apple", Material.GOLDEN_APPLE),
      Map.entry("emerald", Material.EMERALD),
      Map.entry("medical_sample", Material.FERMENTED_SPIDER_EYE),
      Map.entry("echo_shard", Material.ECHO_SHARD),
      Map.entry("encrypted_drive", Material.AMETHYST_SHARD),
      Map.entry("ancient_debris", Material.ANCIENT_DEBRIS),
      Map.entry("military_processor", Material.RECOVERY_COMPASS),
      Map.entry("golden_lion", Material.GOLD_BLOCK),
      Map.entry("classified_document", Material.NETHER_STAR),
      Map.entry("extraction_key", Material.TRIPWIRE_HOOK));

  private final ItemRegistry registry;

  public CraftovItemStacks(ItemRegistry registry) {
    this.registry = Preconditions.checkNotNull(registry, "registry");
  }

  public List<ItemStack> fromStored(StoredItem stored) {
    Preconditions.checkNotNull(stored, "stored");
    return build(stored.itemId(), stored.quantity(), stored.instanceId(),
        stored.opaquePayload(), stored.storageId());
  }

  public List<ItemStack> fromInstance(ItemInstance instance) {
    Preconditions.checkNotNull(instance, "instance");
    return build(instance.itemId(), instance.quantity(), instance.instanceId(),
        instance.opaquePayload(), null);
  }

  public List<ItemInstance> scan(ItemStack[] contents) {
    Preconditions.checkNotNull(contents, "contents");
    Map<UUID, ScannedStack> grouped = new LinkedHashMap<>();
    Set<UUID> identities = new HashSet<>();
    for (ItemStack stack : contents) {
      Optional<TaggedItem> found = read(stack);
      if (found.isEmpty()) continue;
      TaggedItem tagged = found.get();
      ScannedStack existing = grouped.get(tagged.instanceId());
      if (existing != null && existing.itemId().equals(tagged.itemId())
          && existing.comparison().isSimilar(stack)) {
        existing.add(stack.getAmount());
        continue;
      }
      UUID instanceId = tagged.instanceId();
      if (!identities.add(instanceId)) {
        // The same UUID with different concrete meta is not a normal vanilla split.
        // Preserve both physical states, but immediately separate their identities.
        instanceId = uniqueIdentity(UUID.randomUUID(), identities);
        applyIdentity(stack, tagged.itemId(), instanceId, tagged.storageId());
      }
      ItemStack comparison = stack.clone();
      comparison.setAmount(1);
      ItemStack template = comparison.clone();
      template.setAmount(1);
      applyIdentity(template, tagged.itemId(), instanceId, null);
      grouped.put(instanceId,
          new ScannedStack(tagged.itemId(), stack.getAmount(), comparison, template));
    }
    List<ItemInstance> result = new ArrayList<>();
    for (Map.Entry<UUID, ScannedStack> entry : grouped.entrySet()) {
      UUID instanceId = entry.getKey();
      ScannedStack scanned = entry.getValue();
      ItemDefinition definition = registry.require(scanned.itemId());
      int maximum = maximumStackSize(definition, scanned.template().getType());
      int remaining = scanned.quantity();
      int part = 0;
      while (remaining > 0) {
        int amount = Math.min(remaining, maximum);
        UUID partId = part == 0 ? instanceId : uniqueIdentity(UUID.randomUUID(), identities);
        ItemStack snapshot = scanned.template().clone();
        snapshot.setAmount(amount);
        applyIdentity(snapshot, scanned.itemId(), partId, null);
        result.add(new ItemInstance(partId, scanned.itemId(), amount, encode(snapshot)));
        remaining -= amount;
        part++;
      }
    }
    return List.copyOf(result);
  }

  public Optional<TaggedItem> read(ItemStack stack) {
    if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) return Optional.empty();
    ItemMeta meta = stack.getItemMeta();
    PersistentDataContainer data = meta.getPersistentDataContainer();
    String rawId = data.get(ITEM_ID_KEY, PersistentDataType.STRING);
    if (rawId == null) return Optional.empty();
    ItemId itemId;
    try {
      itemId = ItemId.of(rawId);
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
    ItemDefinition definition = registry.find(itemId).orElse(null);
    if (definition == null || canonicalMaterial(definition) != stack.getType()) {
      return Optional.empty();
    }
    UUID instanceId = uuid(data.get(INSTANCE_ID_KEY, PersistentDataType.STRING));
    UUID storageId = uuid(data.get(STORAGE_ID_KEY, PersistentDataType.STRING));
    if (instanceId == null) {
      // A pre-migration durable stack only knew its storage row id. Reuse it just
      // like the database fallback instead of inventing a different identity in-game.
      instanceId = storageId == null ? UUID.randomUUID() : storageId;
      applyIdentity(stack, itemId, instanceId, storageId);
    }
    return Optional.of(new TaggedItem(itemId, instanceId, storageId));
  }

  public boolean isCraftovItem(ItemStack stack) {
    return read(stack).isPresent();
  }

  /** Gives a separated or dropped stack a new physical identity. */
  public Optional<UUID> assignFreshIdentity(ItemStack stack) {
    Optional<TaggedItem> found = read(stack);
    if (found.isEmpty()) return Optional.empty();
    TaggedItem tagged = found.get();
    UUID fresh = UUID.randomUUID();
    applyIdentity(stack, tagged.itemId(), fresh, null);
    return Optional.of(fresh);
  }

  public int usedSlots(ItemStack[] contents) {
    int used = 0;
    for (ItemStack stack : contents) if (isCraftovItem(stack)) used++;
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

  private List<ItemStack> build(ItemId itemId, int quantity, UUID instanceId,
      @Nullable String opaquePayload, @Nullable UUID storageId) {
    Preconditions.checkArgument(quantity >= 1, "quantity must be positive");
    ItemDefinition definition = registry.require(itemId);
    Material material = canonicalMaterial(definition);
    int maximum = maximumStackSize(definition, material);
    ItemStack template = decode(opaquePayload).filter(stack -> stack.getType() == material)
        .orElseGet(() -> defaultStack(definition, material));
    List<ItemStack> result = new ArrayList<>();
    int remaining = quantity;
    int part = 0;
    while (remaining > 0) {
      int amount = Math.min(remaining, maximum);
      UUID partId = part == 0 ? instanceId : splitIdentity(instanceId, part);
      ItemStack stack = template.clone();
      stack.setAmount(amount);
      applyIdentity(stack, itemId, partId, storageId);
      result.add(stack);
      remaining -= amount;
      part++;
    }
    return List.copyOf(result);
  }

  private ItemStack defaultStack(ItemDefinition definition, Material material) {
    ItemStack stack = new ItemStack(material);
    stack.editMeta(meta -> {
      meta.itemName(Component.text(definition.displayName(), color(definition.rarity()))
          .decoration(TextDecoration.ITALIC, false));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.text("稀有度: " + rarityName(definition.rarity()), NamedTextColor.GRAY)
          .decoration(TextDecoration.ITALIC, false));
      lore.add(Component.text("价值: " + definition.basePrice().format() + " 金币",
              NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
      lore.add(Component.text("重量: " + definition.weightGrams() + "g", NamedTextColor.DARK_GRAY)
          .decoration(TextDecoration.ITALIC, false));
      if (definition.containerSlots() > 0) {
        lore.add(Component.text("容量: " + definition.containerSlots() + " 格",
                NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
      }
      meta.lore(lore);
      if (meta instanceof PotionMeta potion && definition.id().value().equals("healing_potion")) {
        potion.setBasePotionType(PotionType.HEALING);
      }
    });
    return stack;
  }

  private static void applyIdentity(ItemStack stack, ItemId itemId, UUID instanceId,
      @Nullable UUID storageId) {
    boolean edited = stack.editMeta(meta -> {
      itemModel(itemId).ifPresent(meta::setItemModel);
      PersistentDataContainer data = meta.getPersistentDataContainer();
      data.set(ITEM_ID_KEY, PersistentDataType.STRING, itemId.value());
      data.set(INSTANCE_ID_KEY, PersistentDataType.STRING, instanceId.toString());
      if (storageId == null) {
        data.remove(STORAGE_ID_KEY);
      } else {
        data.set(STORAGE_ID_KEY, PersistentDataType.STRING, storageId.toString());
      }
    });
    if (!edited) throw new IllegalArgumentException("物品类型不支持持久化 Meta: " + stack.getType());
  }

  private static String encode(ItemStack stack) {
    String payload = PAYLOAD_PREFIX
        + Base64.getEncoder().encodeToString(stack.serializeAsBytes());
    if (payload.length() > MAX_PAYLOAD_CHARACTERS) {
      throw new IllegalArgumentException("物品数据超过持久化安全上限: " + stack.getType());
    }
    return payload;
  }

  private static Optional<ItemStack> decode(@Nullable String payload) {
    if (payload == null || payload.length() > MAX_PAYLOAD_CHARACTERS
        || !payload.startsWith(PAYLOAD_PREFIX)) return Optional.empty();
    try {
      byte[] bytes = Base64.getDecoder().decode(payload.substring(PAYLOAD_PREFIX.length()));
      ItemStack decoded = ItemStack.deserializeBytes(bytes);
      return decoded.isEmpty() ? Optional.empty() : Optional.of(decoded);
    } catch (RuntimeException ignored) {
      // Old, damaged or foreign adapter data must never prevent the registered item
      // definition from being restored as a safe canonical stack.
      return Optional.empty();
    }
  }

  private static UUID uniqueIdentity(UUID preferred, Set<UUID> used) {
    UUID candidate = preferred;
    while (!used.add(candidate)) candidate = UUID.randomUUID();
    return candidate;
  }

  private static UUID splitIdentity(UUID parent, int part) {
    return UUID.nameUUIDFromBytes(("escapefromcraftov:split:" + parent + ":" + part)
        .getBytes(StandardCharsets.UTF_8));
  }

  private static int maximumStackSize(ItemDefinition definition, Material material) {
    return Math.max(1, Math.min(definition.stackSize(), material.getMaxStackSize()));
  }

  static Optional<NamespacedKey> itemModel(ItemId itemId) {
    Preconditions.checkNotNull(itemId, "itemId");
    return MODELLED_ITEMS.contains(itemId.value())
        ? Optional.of(new NamespacedKey(MODEL_NAMESPACE, itemId.value()))
        : Optional.empty();
  }

  private static Material canonicalMaterial(ItemDefinition definition) {
    return MATERIALS.getOrDefault(definition.id().value(), Material.PAPER);
  }

  private static NamespacedKey key(String value) {
    return Objects.requireNonNull(NamespacedKey.fromString(value));
  }

  private static @Nullable UUID uuid(@Nullable String value) {
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

  public record TaggedItem(ItemId itemId, UUID instanceId, @Nullable UUID storageId) {}

  private static final class ScannedStack {
    private final ItemId itemId;
    private final ItemStack comparison;
    private final ItemStack template;
    private int quantity;

    private ScannedStack(ItemId itemId, int quantity, ItemStack comparison, ItemStack template) {
      this.itemId = itemId;
      this.quantity = quantity;
      this.comparison = comparison;
      this.template = template;
    }

    private ItemId itemId() { return itemId; }
    private int quantity() { return quantity; }
    private ItemStack comparison() { return comparison; }
    private ItemStack template() { return template; }
    private void add(int amount) { quantity = Math.addExact(quantity, amount); }
  }
}
