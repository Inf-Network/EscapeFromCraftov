package net.infnetwork.snowball.craftov.item;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.block.BlockPredicate;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.datacomponent.item.ItemAdventurePredicate;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.SwingAnimation;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.papermc.paper.datacomponent.item.UseCooldown;
import io.papermc.paper.datacomponent.item.UseEffects;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.infnetwork.snowball.craftov.combat.MedSpec;
import net.infnetwork.snowball.craftov.util.Text;
import net.infnetwork.snowball.craftov.weapon.FireMode;
import net.infnetwork.snowball.craftov.weapon.GunSpec;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/** Builds Craftov item stacks and converts them to and from {@link ItemRecord}s. */
@SuppressWarnings("UnstableApiUsage")
public final class ItemFactory {

    private static final Gson GSON = new Gson();
    private static final Type RECORD_LIST = new TypeToken<List<ItemRecord>>() { }.getType();
    private static final Key SILENT = Key.key("minecraft", "intentionally_empty");
    /** Bump when item components change; older stacks are rebuilt from their records on join. */
    public static final int VERSION = 3;
    /** Cooldown group of every gun; WeaponService runs it for the reload time to drive the reload animation. */
    public static final Key RELOAD_GROUP = Key.key("craftov", "reload");
    private static final NamespacedKey GUN_BREAK = new NamespacedKey("craftov", "gun_break");
    private static final NamespacedKey GUN_ATTACK = new NamespacedKey("craftov", "gun_attack");

    private final ItemRegistry registry;

    public ItemFactory(ItemRegistry registry) {
        this.registry = registry;
    }

    public ItemRegistry registry() {
        return registry;
    }

    // ------------------------------------------------------------------ creation

    public @Nullable ItemStack create(String id, int amount) {
        ItemDef def = registry.get(id);
        return def == null ? null : create(def, amount);
    }

    public ItemStack create(ItemDef def, int amount) {
        ItemRecord record = new ItemRecord(def.id(), amount);
        if (def.isGun()) {
            record.ammo = 0;
            record.uid = java.util.UUID.randomUUID().toString();
        }
        if (def.hasDurability()) {
            record.dura = def.durability();
        }
        return build(def, record);
    }

    /** A gun with a full magazine of the given ammo (used for AI and starter kits). */
    public ItemStack loadedGun(ItemDef gun, @Nullable String ammoId) {
        ItemRecord record = new ItemRecord(gun.id(), 1);
        record.uid = java.util.UUID.randomUUID().toString();
        record.ammo = ammoId == null ? 0 : gun.gun().magazine();
        record.ammoType = ammoId;
        return build(gun, record);
    }

    // ------------------------------------------------------------------ optics and range loaners

    public static boolean isRange(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(Keys.RANGE);
    }

    /** Marks a stack as a shooting-range loaner (lore says so; it can never be stored or sold). */
    public ItemStack markRange(ItemStack stack) {
        stack.editPersistentDataContainer(pdc -> pdc.set(Keys.RANGE, PersistentDataType.BYTE, (byte) 1));
        refreshLore(stack, defOf(stack));
        return stack;
    }

    public static @Nullable String opticId(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack.getPersistentDataContainer().get(Keys.OPTIC, PersistentDataType.STRING);
    }

    /** The gun with a different optic mounted (null removes it); magazine, mode and loaner flag are kept. */
    public ItemStack withOptic(ItemStack gun, @Nullable String opticId) {
        ItemRecord record = recordOf(gun);
        if (record == null) {
            return gun;
        }
        record.optic = opticId;
        int mode = mode(gun);
        boolean range = isRange(gun);
        ItemStack rebuilt = fromRecord(record);
        if (rebuilt == null) {
            return gun;
        }
        setMode(rebuilt, mode);
        return range ? markRange(rebuilt) : rebuilt;
    }

    /** Rebuilds a stack made by an older plugin version (components changed); null if it is current. */
    public @Nullable ItemStack upgrade(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty() || idOf(stack) == null || isUi(stack)) {
            return null;
        }
        Integer version = stack.getPersistentDataContainer().get(Keys.VERSION, PersistentDataType.INTEGER);
        if (version != null && version >= VERSION) {
            return null;
        }
        int mode = mode(stack);
        ItemStack rebuilt = fromRecord(toRecord(stack));
        if (rebuilt != null) {
            setMode(rebuilt, mode);
        }
        return rebuilt;
    }

    /** A ready-to-use sample of an item: guns come loaded, ammo as a full stack, everything else single. */
    public ItemStack sample(ItemDef def) {
        if (def.isGun()) {
            List<ItemDef> ammo = registry.ammoFor(def.gun().caliber());
            return loadedGun(def, ammo.isEmpty() ? null : ammo.get(0).id());
        }
        return create(def, def.category() == Category.AMMO ? def.maxStack() : 1);
    }

    public @Nullable ItemStack fromRecord(@Nullable ItemRecord record) {
        if (record == null) {
            return null;
        }
        ItemDef def = registry.get(record.id);
        if (def == null) {
            return null;
        }
        return build(def, record);
    }

    private ItemStack build(ItemDef def, ItemRecord record) {
        ItemStack stack = ItemStack.of(Material.PAPER, Math.max(1, Math.min(record.amount, def.maxStack())));
        stack.setData(DataComponentTypes.MAX_STACK_SIZE, def.maxStack());
        stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(def.model()));
        stack.setData(DataComponentTypes.ITEM_NAME, Text.mm(def.coloredName()));
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.EQUIPPABLE, DataComponentTypes.CAN_BREAK)
                .build());
        GunSpec gun = def.gun();
        MedSpec med = def.med();
        if (gun != null) {
            // Left mouse fires by "mining" an invisible capture block in front of the crosshair (see
            // TriggerService): guns may dig in adventure mode, but never break anything, never swing
            // and never show the attack-cooldown indicator.
            stack.setData(DataComponentTypes.CAN_BREAK, ItemAdventurePredicate.itemAdventurePredicate(
                    List.of(BlockPredicate.predicate().build())));
            // Never triggered by use (guns have no use action); only the reload sets this cooldown, and the
            // client plays the reload frames of the item model while it counts down.
            stack.setData(DataComponentTypes.USE_COOLDOWN, UseCooldown.useCooldown(0.05f).cooldownGroup(RELOAD_GROUP).build());
            stack.setData(DataComponentTypes.SWING_ANIMATION, SwingAnimation.swingAnimation()
                    .type(SwingAnimation.Animation.NONE).duration(1).build());
            stack.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes()
                    .addModifier(Attribute.BLOCK_BREAK_SPEED, new AttributeModifier(GUN_BREAK, -1.0,
                            AttributeModifier.Operation.MULTIPLY_SCALAR_1), EquipmentSlotGroup.MAINHAND)
                    .addModifier(Attribute.ATTACK_SPEED, new AttributeModifier(GUN_ATTACK, 100.0,
                            AttributeModifier.Operation.ADD_NUMBER), EquipmentSlotGroup.MAINHAND)
                    .build());
        } else if (med != null) {
            ItemUseAnimation animation;
            try {
                animation = ItemUseAnimation.valueOf(med.animation().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                animation = ItemUseAnimation.BRUSH;
            }
            stack.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                    .consumeSeconds((float) med.useSeconds())
                    .animation(animation)
                    .sound(Key.key("craftov", "med.loop"))
                    .hasConsumeParticles(false)
                    .build());
            stack.setData(DataComponentTypes.USE_EFFECTS, UseEffects.useEffects()
                    .canSprint(false)
                    .speedMultiplier(0.45f)
                    .interactVibrations(false)
                    .build());
        }
        switch (def.category()) {
            case HELMET -> stack.setData(DataComponentTypes.EQUIPPABLE, equippable(EquipmentSlot.HEAD, def, true));
            case ARMOR -> stack.setData(DataComponentTypes.EQUIPPABLE, equippable(EquipmentSlot.CHEST, def, true));
            case RIG -> stack.setData(DataComponentTypes.EQUIPPABLE, equippable(EquipmentSlot.LEGS, def, false));
            case BACKPACK -> stack.setData(DataComponentTypes.EQUIPPABLE, equippable(EquipmentSlot.FEET, def, false));
            default -> {
            }
        }
        stack.editPersistentDataContainer(pdc -> {
            pdc.set(Keys.ITEM_ID, PersistentDataType.STRING, def.id());
            pdc.set(Keys.VERSION, PersistentDataType.INTEGER, VERSION);
            String uid = record.uid;
            if (uid == null && def.isGun()) {
                uid = java.util.UUID.randomUUID().toString();
            }
            if (uid != null) {
                pdc.set(Keys.UID, PersistentDataType.STRING, uid);
            }
            if (record.ammo != null) {
                pdc.set(Keys.AMMO, PersistentDataType.INTEGER, record.ammo);
            }
            if (record.ammoType != null) {
                pdc.set(Keys.AMMO_TYPE, PersistentDataType.STRING, record.ammoType);
            }
            if (record.dura != null) {
                pdc.set(Keys.DURA, PersistentDataType.DOUBLE, record.dura);
            }
            if (record.hasContents()) {
                pdc.set(Keys.CONTENTS, PersistentDataType.STRING, GSON.toJson(record.contents, RECORD_LIST));
            }
            if (record.optic != null && def.isGun()) {
                pdc.set(Keys.OPTIC, PersistentDataType.STRING, record.optic);
            }
        });
        if (record.optic != null && def.isGun()) {
            // The item model picks the gun+optic model from custom_model_data string 0.
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, io.papermc.paper.datacomponent.item.CustomModelData
                    .customModelData().addString(record.optic).build());
        }
        refreshLore(stack, def);
        return stack;
    }

    private static Equippable equippable(EquipmentSlot slot, ItemDef def, boolean swappable) {
        Equippable.Builder builder = Equippable.equippable(slot)
                .swappable(swappable)
                .damageOnHurt(false)
                .dispensable(false)
                .equipSound(Key.key("minecraft", "item.armor.equip_leather"));
        if (def.equipmentAsset() != null) {
            builder.assetId(Key.key(def.equipmentAsset()));
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ reading

    public static @Nullable String idOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.getPersistentDataContainer().get(Keys.ITEM_ID, PersistentDataType.STRING);
    }

    public @Nullable ItemDef defOf(@Nullable ItemStack stack) {
        return registry.get(idOf(stack));
    }

    public static boolean isUi(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(Keys.UI);
    }

    public static @Nullable String uiTag(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.getPersistentDataContainer().get(Keys.UI, PersistentDataType.STRING);
    }

    /** Storable form of a stack, or null for non-Craftov stacks and shooting-range loaners (never persisted). */
    public @Nullable ItemRecord toRecord(@Nullable ItemStack stack) {
        return isRange(stack) ? null : recordOf(stack);
    }

    private @Nullable ItemRecord recordOf(@Nullable ItemStack stack) {
        ItemDef def = defOf(stack);
        if (def == null) {
            return null;
        }
        PersistentDataContainerView pdc = stack.getPersistentDataContainer();
        ItemRecord record = new ItemRecord(def.id(), stack.getAmount());
        record.uid = pdc.get(Keys.UID, PersistentDataType.STRING);
        record.ammo = pdc.get(Keys.AMMO, PersistentDataType.INTEGER);
        record.ammoType = pdc.get(Keys.AMMO_TYPE, PersistentDataType.STRING);
        record.dura = pdc.get(Keys.DURA, PersistentDataType.DOUBLE);
        String contents = pdc.get(Keys.CONTENTS, PersistentDataType.STRING);
        if (contents != null && !contents.isEmpty()) {
            record.contents = GSON.fromJson(contents, RECORD_LIST);
        }
        record.optic = pdc.get(Keys.OPTIC, PersistentDataType.STRING);
        return record;
    }

    public static @Nullable String uid(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.getPersistentDataContainer().get(Keys.UID, PersistentDataType.STRING);
    }

    public static int mode(ItemStack stack) {
        Integer value = stack.getPersistentDataContainer().get(Keys.MODE, PersistentDataType.INTEGER);
        return value == null ? 0 : value;
    }

    public static void setMode(ItemStack stack, int mode) {
        stack.editPersistentDataContainer(pdc -> pdc.set(Keys.MODE, PersistentDataType.INTEGER, mode));
    }

    public static int ammo(ItemStack stack) {
        Integer value = stack.getPersistentDataContainer().get(Keys.AMMO, PersistentDataType.INTEGER);
        return value == null ? 0 : value;
    }

    public static @Nullable String ammoType(ItemStack stack) {
        return stack.getPersistentDataContainer().get(Keys.AMMO_TYPE, PersistentDataType.STRING);
    }

    public static double dura(ItemStack stack, double fallback) {
        Double value = stack.getPersistentDataContainer().get(Keys.DURA, PersistentDataType.DOUBLE);
        return value == null ? fallback : value;
    }

    public List<ItemRecord> contents(ItemStack stack) {
        String contents = stack.getPersistentDataContainer().get(Keys.CONTENTS, PersistentDataType.STRING);
        if (contents == null || contents.isEmpty()) {
            return new ArrayList<>();
        }
        List<ItemRecord> list = GSON.fromJson(contents, RECORD_LIST);
        return list == null ? new ArrayList<>() : list;
    }

    public static boolean hasContents(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(Keys.CONTENTS);
    }

    // ------------------------------------------------------------------ mutation

    public void setAmmo(ItemStack stack, int ammo, @Nullable String ammoType) {
        stack.editPersistentDataContainer(pdc -> {
            pdc.set(Keys.AMMO, PersistentDataType.INTEGER, ammo);
            if (ammoType == null) {
                pdc.remove(Keys.AMMO_TYPE);
            } else {
                pdc.set(Keys.AMMO_TYPE, PersistentDataType.STRING, ammoType);
            }
        });
        refreshLore(stack, defOf(stack));
    }

    public void setDura(ItemStack stack, double dura) {
        stack.editPersistentDataContainer(pdc -> pdc.set(Keys.DURA, PersistentDataType.DOUBLE, dura));
        refreshLore(stack, defOf(stack));
    }

    public void setContents(ItemStack stack, List<ItemRecord> contents) {
        stack.editPersistentDataContainer(pdc -> {
            if (contents.isEmpty()) {
                pdc.remove(Keys.CONTENTS);
            } else {
                pdc.set(Keys.CONTENTS, PersistentDataType.STRING, GSON.toJson(contents, RECORD_LIST));
            }
        });
        refreshLore(stack, defOf(stack));
    }

    // ------------------------------------------------------------------ value

    public long value(@Nullable ItemStack stack) {
        ItemRecord record = toRecord(stack);
        return record == null ? 0 : value(record);
    }

    public long value(ItemRecord record) {
        ItemDef def = registry.get(record.id);
        if (def == null) {
            return 0;
        }
        double unit = def.value();
        if (def.hasDurability() && record.dura != null && def.durability() > 0) {
            double fraction = Math.max(0, Math.min(1, record.dura / def.durability()));
            unit *= def.category() == Category.MEDICAL || def.category() == Category.KEY
                    ? Math.max(0.1, fraction) : 0.25 + 0.75 * fraction;
        }
        long total = Math.round(unit) * Math.max(1, record.amount);
        if (record.ammo != null && record.ammo > 0 && record.ammoType != null) {
            ItemDef ammo = registry.get(record.ammoType);
            if (ammo != null) {
                total += ammo.value() * record.ammo;
            }
        }
        if (record.contents != null) {
            for (ItemRecord child : record.contents) {
                total += value(child);
            }
        }
        ItemDef optic = registry.get(record.optic);
        if (optic != null) {
            total += optic.value();
        }
        return total;
    }

    // ------------------------------------------------------------------ lore

    public void refreshLore(ItemStack stack, @Nullable ItemDef def) {
        if (def == null) {
            return;
        }
        List<Component> lore = new ArrayList<>();
        String type = def.category().displayName();
        if (def.gun() != null) {
            type += " · " + def.gun().slot().displayName();
        }
        lore.add(Text.mm(def.rarity().tag() + "▍" + def.rarity().displayName() + " <#8A8F98>" + type));
        for (String line : def.lore()) {
            lore.add(Text.mm("<#9AA0A6>" + line));
        }
        lore.add(Component.empty());
        GunSpec gun = def.gun();
        if (gun != null) {
            int ammo = ammo(stack);
            String ammoType = ammoType(stack);
            ItemDef ammoDef = registry.get(ammoType);
            lore.add(Text.mm("<#8A8F98>口径 <white>" + gun.caliber() + "  <#8A8F98>弹匣 <white>" + ammo + "/" + gun.magazine()
                    + (ammoDef != null ? " <#8A8F98>(" + Text.escape(ammoDef.name()) + ")" : "")));
            StringBuilder modes = new StringBuilder();
            for (FireMode mode : gun.modes()) {
                if (!modes.isEmpty()) {
                    modes.append('/');
                }
                modes.append(mode.displayName());
            }
            lore.add(Text.mm("<#8A8F98>伤害 <white>" + fmt(gun.damage()) + (gun.pellets() > 1 ? "×" + gun.pellets() : "")
                    + "  <#8A8F98>射速 <white>" + gun.rpm() + "  <#8A8F98>模式 <white>" + modes));
            ItemDef optic = registry.get(opticId(stack));
            ItemDef builtIn = registry.get(gun.scope());
            if (optic != null) {
                lore.add(Text.mm("<#8A8F98>瞄准镜 " + optic.coloredName() + " <#8A8F98>(" + optic.optic().magnification() + " 倍)"));
            } else if (builtIn != null) {
                lore.add(Text.mm("<#8A8F98>瞄准镜 <white>自带 " + builtIn.optic().magnification() + " 倍"));
            } else if (!"none".equals(gun.mount())) {
                lore.add(Text.mm("<#8A8F98>瞄准镜 <white>机械瞄具 <#6B7079>(可在改枪台安装瞄准镜)"));
            }
        }
        if (def.optic() != null) {
            lore.add(Text.mm("<#8A8F98>放大倍率 <white>" + def.optic().magnification() + " 倍  <#8A8F98>适用 <white>"
                    + (def.optic().mounts().contains("pistol") ? "步枪 / 冲锋枪 / 霰弹枪 / 手枪" : "步枪 / 冲锋枪 / 霰弹枪")));
        }
        if (def.ammo() != null) {
            lore.add(Text.mm("<#8A8F98>口径 <white>" + def.ammo().caliber() + "  <#8A8F98>穿透等级 <white>" + def.ammo().penetration()));
        }
        if (def.isArmor()) {
            double dura = dura(stack, def.durability());
            lore.add(Text.mm("<#8A8F98>防护等级 <white>" + def.armorClass() + "  <#8A8F98>耐久 <white>"
                    + fmt(dura) + "/" + fmt(def.durability())));
        }
        if (def.isContainer()) {
            int stored = hasContents(stack) ? contents(stack).size() : 0;
            lore.add(Text.mm("<#8A8F98>容量 <white>" + def.slots() + " 格" + (stored > 0 ? "  <#FFC23D>内含 " + stored + " 件物品" : "")));
        }
        MedSpec med = def.med();
        if (med != null) {
            if (med.pool() > 0) {
                lore.add(Text.mm("<#8A8F98>剩余治疗量 <white>" + fmt(dura(stack, med.pool())) + "/" + fmt(med.pool())));
            }
            lore.add(Text.mm("<#8A8F98>使用时间 <white>" + fmt(med.useSeconds()) + "s"));
        }
        if (def.category() == Category.KEY) {
            lore.add(Text.mm("<#8A8F98>剩余次数 <white>" + fmt(dura(stack, def.durability()))));
        }
        lore.add(Text.mm("<#8A8F98>重量 <white>" + fmt(def.weight() * stack.getAmount()) + "kg"));
        if (isRange(stack)) {
            lore.add(Text.mm("<#FFB74D>靶场专用 · 不能带出靶场，离开时自动收回"));
        } else {
            long value = value(stack);
            lore.add(Text.mm("<#8A8F98>价值 <#F2C94C>◈ " + Text.money(value)));
        }
        stack.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format("%.1f", value);
    }
}
