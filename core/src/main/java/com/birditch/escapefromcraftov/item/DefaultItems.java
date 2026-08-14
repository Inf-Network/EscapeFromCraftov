package com.birditch.escapefromcraftov.item;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.item.ItemCategory;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemFootprint;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemRarity;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/// Minecraft-native demo item catalog registered during bootstrap.
///
/// Grid footprints below are EscapeFromCraftov server-development tuning values.
/// They are not claimed to be official dimensions from another game.
public final class DefaultItems {

  private DefaultItems() {
  }

  public static void registerAll(ItemRegistry registry) {
    Preconditions.checkNotNull(registry, "registry");
    registry.register(ItemDefinition
        .builder(ItemId.of("iron_sword"), "铁剑", ItemCategory.MELEE_WEAPON,
            ItemRarity.COMMON)
        .basePrice(Money.of(1200L))
        .weightGrams(1500)
        .footprint(ItemFootprint.of(1, 3))
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("iron_axe"), "铁斧", ItemCategory.MELEE_WEAPON,
            ItemRarity.COMMON)
        .basePrice(Money.of(1300L))
        .weightGrams(1800)
        .footprint(ItemFootprint.of(2, 3))
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("bow"), "弓", ItemCategory.RANGED_WEAPON, ItemRarity.COMMON)
        .basePrice(Money.of(1000L))
        .weightGrams(800)
        .footprint(ItemFootprint.of(2, 3))
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("crossbow"), "弩", ItemCategory.RANGED_WEAPON,
            ItemRarity.UNCOMMON)
        .basePrice(Money.of(2400L))
        .weightGrams(1200)
        .footprint(ItemFootprint.of(2, 2))
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("arrow"), "箭矢", ItemCategory.PROJECTILE, ItemRarity.COMMON)
        .basePrice(Money.of(40L))
        .weightGrams(30)
        .stackSize(64)
        .secureAllowed(false)
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("shield"), "盾牌", ItemCategory.SHIELD, ItemRarity.UNCOMMON)
        .basePrice(Money.of(1800L))
        .weightGrams(2000)
        .footprint(ItemFootprint.of(2, 3))
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("iron_chestplate"), "铁胸甲", ItemCategory.ARMOR,
            ItemRarity.UNCOMMON)
        .basePrice(Money.of(3200L))
        .weightGrams(3500)
        .footprint(ItemFootprint.of(2, 3))
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("basic_rig"), "基础胸挂", ItemCategory.CHEST_RIG,
            ItemRarity.COMMON)
        .basePrice(Money.of(4500L))
        .weightGrams(1200)
        .containerSlots(6)
        .secureAllowed(false)
        .footprint(ItemFootprint.of(2, 2))
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("patrol_backpack"), "巡逻背包", ItemCategory.BACKPACK,
            ItemRarity.UNCOMMON)
        .basePrice(Money.of(6500L))
        .weightGrams(1800)
        .containerSlots(12)
        .secureAllowed(false)
        .footprint(ItemFootprint.of(2, 3))
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("healing_potion"), "治疗药水", ItemCategory.POTION,
            ItemRarity.UNCOMMON)
        .basePrice(Money.of(1600L))
        .weightGrams(500)
        .stackSize(4)
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("golden_apple"), "金苹果", ItemCategory.HEALING,
            ItemRarity.RARE)
        .basePrice(Money.of(5000L))
        .weightGrams(250)
        .stackSize(16)
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("emerald"), "绿宝石", ItemCategory.VALUABLE, ItemRarity.COMMON)
        .basePrice(Money.of(900L))
        .weightGrams(100)
        .stackSize(64)
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("medical_sample"), "密封医疗样本", ItemCategory.VALUABLE,
            ItemRarity.UNCOMMON)
        .basePrice(Money.of(3_800L))
        .weightGrams(180)
        .stackSize(16)
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("echo_shard"), "回响碎片", ItemCategory.VALUABLE,
            ItemRarity.RARE)
        .basePrice(Money.of(6500L))
        .weightGrams(80)
        .stackSize(16)
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("encrypted_drive"), "加密数据盘", ItemCategory.VALUABLE,
            ItemRarity.RARE)
        .basePrice(Money.of(12_500L))
        .weightGrams(120)
        .stackSize(8)
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("ancient_debris"), "远古残骸", ItemCategory.MATERIAL,
            ItemRarity.EPIC)
        .basePrice(Money.of(30000L))
        .weightGrams(2000)
        .stackSize(16)
        .footprint(ItemFootprint.of(2, 2))
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("military_processor"), "军用运算核心", ItemCategory.VALUABLE,
            ItemRarity.EPIC)
        .basePrice(Money.of(42_000L))
        .weightGrams(600)
        .stackSize(4)
        .footprint(ItemFootprint.of(2, 2))
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("golden_lion"), "鎏金雄狮", ItemCategory.VALUABLE,
            ItemRarity.LEGENDARY)
        .basePrice(Money.of(120_000L))
        .weightGrams(3_200)
        .footprint(ItemFootprint.of(2, 3))
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("classified_document"), "绝密行动档案", ItemCategory.VALUABLE,
            ItemRarity.ARTIFACT)
        .basePrice(Money.of(350_000L))
        .weightGrams(300)
        .footprint(ItemFootprint.of(2, 1))
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("extraction_key"), "撤离钥匙", ItemCategory.KEY,
            ItemRarity.RARE)
        .basePrice(Money.of(12000L))
        .weightGrams(50)
        .build());
  }
}
