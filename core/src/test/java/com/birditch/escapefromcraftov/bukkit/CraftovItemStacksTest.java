package com.birditch.escapefromcraftov.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.item.DefaultItems;
import com.birditch.escapefromcraftov.item.SimpleItemRegistry;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class CraftovItemStacksTest {

  private static final NamespacedKey CUSTOM_KEY = Objects.requireNonNull(
      NamespacedKey.fromString("test:custom_meta"));

  private CraftovItemStacks itemStacks;

  @BeforeEach
  void setUp() {
    MockBukkit.mock();
    SimpleItemRegistry registry = new SimpleItemRegistry();
    DefaultItems.registerAll(registry);
    itemStacks = new CraftovItemStacks(registry);
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  void durabilityEnchantLoreAndPdcSurvivePayloadRoundTrip() {
    UUID instanceId = UUID.randomUUID();
    ItemStack source = itemStacks.fromInstance(
        new ItemInstance(instanceId, ItemId.of("iron_sword"), 1)).getFirst();
    source.editMeta(Damageable.class, meta -> {
      meta.setDamage(37);
      meta.addEnchant(Enchantment.SHARPNESS, 3, true);
      meta.lore(List.of(Component.text("玩家改装状态")));
      meta.getPersistentDataContainer().set(CUSTOM_KEY, PersistentDataType.STRING, "kept");
    });

    ItemInstance carried = itemStacks.scan(new ItemStack[] {source}).getFirst();
    UUID storageId = UUID.randomUUID();
    StoredItem stored = new StoredItem(storageId, carried.instanceId(), carried.itemId(),
        carried.quantity(), InventoryLocation.WAREHOUSE, null, carried.opaquePayload());
    ItemStack restored = itemStacks.fromStored(stored).getFirst();

    Damageable meta = (Damageable) restored.getItemMeta();
    assertEquals(37, meta.getDamage());
    assertEquals(3, meta.getEnchantLevel(Enchantment.SHARPNESS));
    assertEquals(List.of(Component.text("玩家改装状态")), meta.lore());
    assertEquals("kept", meta.getPersistentDataContainer()
        .get(CUSTOM_KEY, PersistentDataType.STRING));
    CraftovItemStacks.TaggedItem tag = itemStacks.read(restored).orElseThrow();
    assertEquals(ItemId.of("iron_sword"), tag.itemId());
    assertEquals(instanceId, tag.instanceId());
    assertEquals(storageId, tag.storageId());
    assertTrue(carried.payload().isPresent());
  }

  @Test
  void payloadIdentityAndDefinitionTagsCannotOverrideDurableRecord() {
    UUID hostileInstance = UUID.randomUUID();
    UUID hostileStorage = UUID.randomUUID();
    ItemStack hostile = itemStacks.fromInstance(
        new ItemInstance(UUID.randomUUID(), ItemId.of("iron_sword"), 1)).getFirst();
    hostile.editMeta(meta -> {
      meta.lore(List.of(Component.text("合法的自定义 Meta")));
      meta.getPersistentDataContainer().set(CraftovItemStacks.ITEM_ID_KEY,
          PersistentDataType.STRING, "emerald");
      meta.getPersistentDataContainer().set(CraftovItemStacks.INSTANCE_ID_KEY,
          PersistentDataType.STRING, hostileInstance.toString());
      meta.getPersistentDataContainer().set(CraftovItemStacks.STORAGE_ID_KEY,
          PersistentDataType.STRING, hostileStorage.toString());
    });
    String payload = payload(hostile);
    UUID expectedInstance = UUID.randomUUID();
    UUID expectedStorage = UUID.randomUUID();

    ItemStack restored = itemStacks.fromStored(new StoredItem(expectedStorage,
        expectedInstance, ItemId.of("iron_sword"), 1, InventoryLocation.WAREHOUSE, null,
        payload)).getFirst();

    CraftovItemStacks.TaggedItem tag = itemStacks.read(restored).orElseThrow();
    assertEquals(Material.IRON_SWORD, restored.getType());
    assertEquals(ItemId.of("iron_sword"), tag.itemId());
    assertEquals(expectedInstance, tag.instanceId());
    assertEquals(expectedStorage, tag.storageId());
    assertNotEquals(hostileInstance, tag.instanceId());
    assertNotEquals(hostileStorage, tag.storageId());
    assertEquals(List.of(Component.text("合法的自定义 Meta")), restored.getItemMeta().lore());
  }

  @Test
  void potionMetaSurvivesInstancePayloadRoundTrip() {
    UUID instanceId = UUID.randomUUID();
    ItemStack source = itemStacks.fromInstance(
        new ItemInstance(instanceId, ItemId.of("healing_potion"), 1)).getFirst();
    source.editMeta(PotionMeta.class, meta -> {
      meta.setBasePotionType(PotionType.STRONG_HEALING);
    });

    ItemInstance snapshot = itemStacks.scan(new ItemStack[] {source}).getFirst();
    ItemStack restored = itemStacks.fromInstance(snapshot).getFirst();
    PotionMeta meta = (PotionMeta) restored.getItemMeta();

    assertEquals(PotionType.STRONG_HEALING, meta.getBasePotionType());
    assertEquals(instanceId, itemStacks.read(restored).orElseThrow().instanceId());
  }

  @Test
  void damagedOrWrongMaterialPayloadFallsBackToCanonicalRegisteredItem() {
    UUID instanceId = UUID.randomUUID();
    UUID storageId = UUID.randomUUID();
    ItemStack wrongMaterial = new ItemStack(Material.DIAMOND);
    wrongMaterial.editMeta(meta -> meta.lore(List.of(Component.text("不得走私的物料"))));

    ItemStack wrongRestored = itemStacks.fromStored(new StoredItem(storageId, instanceId,
        ItemId.of("iron_sword"), 1, InventoryLocation.WAREHOUSE, null,
        payload(wrongMaterial))).getFirst();
    ItemStack damagedRestored = itemStacks.fromStored(new StoredItem(storageId, instanceId,
        ItemId.of("iron_sword"), 1, InventoryLocation.WAREHOUSE, null,
        "paper-itemstack:1:not-base64!!")).getFirst();

    assertEquals(Material.IRON_SWORD, wrongRestored.getType());
    assertEquals(Material.IRON_SWORD, damagedRestored.getType());
    assertFalse(Objects.requireNonNullElse(wrongRestored.getItemMeta().lore(), List.of())
        .contains(Component.text("不得走私的物料")));
    assertEquals(instanceId, itemStacks.read(damagedRestored).orElseThrow().instanceId());
    assertEquals(storageId, itemStacks.read(damagedRestored).orElseThrow().storageId());
  }

  @Test
  void vanillaSplitCopiesAreAggregatedWithoutInflatingQuantityWhenScanned() {
    UUID original = UUID.randomUUID();
    ItemStack first = itemStacks.fromStored(new StoredItem(UUID.randomUUID(), original,
        ItemId.of("emerald"), 4, InventoryLocation.CRAFTOV,
        com.birditch.escapefromcraftov.api.game.CraftovGameId.of("game-1"), null)).getFirst();
    ItemStack second = first.clone();
    first.setAmount(2);
    second.setAmount(2);

    List<ItemInstance> instances = itemStacks.scan(new ItemStack[] {first, second});

    assertEquals(1, instances.size());
    assertEquals(original, instances.get(0).instanceId());
    assertEquals(4, instances.getFirst().quantity());
  }

  @Test
  void separatedStackCanReceiveAFreshIdentityBeforeAnotherPlayerTakesIt() {
    UUID original = UUID.randomUUID();
    ItemStack separated = itemStacks.fromInstance(
        new ItemInstance(original, ItemId.of("emerald"), 2)).getFirst();

    UUID fresh = itemStacks.assignFreshIdentity(separated).orElseThrow();

    assertNotEquals(original, fresh);
    assertEquals(fresh, itemStacks.read(separated).orElseThrow().instanceId());
    assertNull(itemStacks.read(separated).orElseThrow().storageId());
  }

  @Test
  void legacyTaggedStackWithoutInstanceUsesItsStorageIdentity() {
    UUID storageId = UUID.randomUUID();
    ItemStack legacy = itemStacks.fromStored(new StoredItem(storageId,
        ItemId.of("emerald"), 1, InventoryLocation.WAREHOUSE, null)).getFirst();
    legacy.editMeta(meta -> meta.getPersistentDataContainer()
        .remove(CraftovItemStacks.INSTANCE_ID_KEY));

    CraftovItemStacks.TaggedItem repaired = itemStacks.read(legacy).orElseThrow();

    assertEquals(storageId, repaired.instanceId());
    assertEquals(storageId, repaired.storageId());
  }

  @Test
  void containersUseDistinctMaterialsAndShowTheirUnlockedCapacity() {
    ItemStack rig = itemStacks.fromInstance(new ItemInstance(
        UUID.randomUUID(), ItemId.of("basic_rig"), 1)).getFirst();
    ItemStack backpack = itemStacks.fromInstance(new ItemInstance(
        UUID.randomUUID(), ItemId.of("patrol_backpack"), 1)).getFirst();

    assertEquals(Material.LEATHER_CHESTPLATE, rig.getType());
    assertEquals(Material.BUNDLE, backpack.getType());
    assertTrue(Objects.requireNonNull(rig.lore()).stream()
        .anyMatch(line -> line.equals(Component.text("容量: 6 格",
            net.kyori.adventure.text.format.NamedTextColor.AQUA)
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))));
    assertTrue(Objects.requireNonNull(backpack.lore()).stream()
        .anyMatch(line -> line.equals(Component.text("容量: 12 格",
            net.kyori.adventure.text.format.NamedTextColor.AQUA)
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))));
  }

  private static String payload(ItemStack stack) {
    return "paper-itemstack:1:" + Base64.getEncoder().encodeToString(stack.serializeAsBytes());
  }
}
