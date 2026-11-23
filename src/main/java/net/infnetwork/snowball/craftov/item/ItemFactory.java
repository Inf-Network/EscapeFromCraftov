package net.infnetwork.snowball.craftov.item;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
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
import net.infnetwork.snowball.craftov.weapon.MeleeSpec;
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
import java.util.Map;

/** Builds Craftov item stacks and converts them to and from {@link ItemRecord}s. */
@SuppressWarnings("UnstableApiUsage")
public final class ItemFactory {

    private static final Gson GSON = new Gson();
    private static final Type RECORD_LIST = new TypeToken<List<ItemRecord>>() { }.getType();
    private static final Key SILENT = Key.key("minecraft", "intentionally_empty");
    /**
     * Bump when item components change; older stacks are rebuilt from their records on join. 4: free-kit
     * mark. 5: med lore, and armour lore says it weakens when worn. 6: gun prices, and knife lore gives
     * damage and penetration. 7: weights, and the self-revive injector's lore no longer calls it instant.
     * 8: both lines of changes above together (#22 economy and the #26-#29 combat fixes each used 5-7).
     * 9: the weapon-handling line (#32-#34, which used 5-6) on top: gun lore shows the new rates of the
     * V57, KL7 and SCR, and guns carry max_damage and unbreakable for the draw mark (see
     * {@link #setDrawing}).
     * 10: knives carry the inspect cooldown group (#82).
     * 11: both of the above together (the #82 line used 10 while main moved to 9).
     * 12: the gun-feel line (#8, which used 10) on top: max_damage 2 for the reload mark, and no "dry"
     * model string (the flash follows the shots the server fires, see {@link #setHandMark}), and gun
     * lore with stat bars (GunStats) in place of the damage and rate line.
     * 12 (#6 line): the tooltip is framed in the item's rarity colour (tooltip_style craftov:rarity_*;
     * #6 used 10).
     * 13: both 12s together (the gun-feel line and the rarity tooltip frames).
     * 14: stackables show one unit's weight and price, so stacks of different sizes merge (#43, which
     * used 13).
     * 15: the ads line (#5, which used 13-15) on top: optic lore gives the true power (the red dot
     * reads 1.5x, not the old 0.74 field of view), max_damage 4 for the aiming marks (the sights are
     * the server's, see {@link #setHandMark}), and guns always carry a damage component (0 without a
     * mark).
     * 17: grenades carry the throw cooldown group, and a long use so they can be held to cook (#42).
     * The grenade line used 16 to stay clear of the ads line's 15; it landed second, so it goes one
     * past both, or stacks already rebuilt at 15 or 16 would keep missing one side's changes.
     * 18: the weight line says how many secure-container cells the item takes (#37).
     * 19: gun models (#4, which used 16): guns carry max_damage 5 and, out of the hand, the holster
     * mark (the item model shows the gun low, as the draw starts).
     */
    public static final int VERSION = 20;
    /**
     * Cooldown group of every gun. WeaponService runs it for a few ticks on every shot (the item model
     * kicks the gun back and flashes the muzzle meanwhile) and for the reload time while the gun carries
     * the reload mark (the model plays the reload frames instead).
     */
    public static final Key RELOAD_GROUP = Key.key("craftov", "reload");
    /** Cooldown group of every knife; InspectService runs it while the player inspects the knife. */
    public static final Key INSPECT_GROUP = Key.key("craftov", "inspect");
    /**
     * Cooldown group of every grenade; ThrowableService runs it after a throw. Without a group of
     * their own grenades share minecraft:paper with every other Craftov item but guns and knives, and
     * the cooldown would lock meds, keys and ammo too.
     */
    public static final Key THROW_GROUP = Key.key("craftov", "throw");
    /**
     * Hand marks (see {@link #setHandMark}): none, coming up after a switch, reloading, the sights
     * coming up, aimed, put away.
     */
    public static final int MARK_NONE = 0;
    public static final int MARK_DRAW = 1;
    public static final int MARK_RELOAD = 2;
    public static final int MARK_AIM = 3;
    public static final int MARK_AIMED = 4;
    public static final int MARK_HOLSTER = 5;
    private static final NamespacedKey GUN_BREAK = new NamespacedKey("craftov", "gun_break");
    private static final NamespacedKey GUN_ATTACK = new NamespacedKey("craftov", "gun_attack");
    private static final NamespacedKey KNIFE_ATTACK = new NamespacedKey("craftov", "knife_attack");
    /** The attack speed a player has with nothing in hand (vanilla's base): a knife's modifier is measured from it. */
    private static final double BASE_ATTACK_SPEED = 4.0;

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

    // ------------------------------------------------------------------ optics, range loaners, test items

    public static boolean isTest(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(Keys.TEST);
    }

    /**
     * Marks a stack as a proving-ground armory item: it works like any other in its raid, but has no
     * value and is never stored, sold, collected or carried back to the lobby.
     */
    public ItemStack markTest(ItemStack stack) {
        stack.editPersistentDataContainer(pdc -> pdc.set(Keys.TEST, PersistentDataType.BYTE, (byte) 1));
        refreshLore(stack, defOf(stack));
        return stack;
    }

    public static boolean isRange(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(Keys.RANGE);
    }

    /** Marks a stack as a shooting-range loaner (lore says so; it can never be stored or sold). */
    public ItemStack markRange(ItemStack stack) {
        stack.editPersistentDataContainer(pdc -> pdc.set(Keys.RANGE, PersistentDataType.BYTE, (byte) 1));
        refreshLore(stack, defOf(stack));
        return stack;
    }

    public static boolean isKit(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(Keys.KIT);
    }

    /** Marks a stack as handed out by the free kit (lore says so; the trader pays nothing for it). */
    public ItemStack markKit(ItemStack stack) {
        stack.editPersistentDataContainer(pdc -> pdc.set(Keys.KIT, PersistentDataType.BYTE, (byte) 1));
        refreshLore(stack, defOf(stack));
        return stack;
    }

    public static @Nullable String opticId(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack.getPersistentDataContainer().get(Keys.OPTIC, PersistentDataType.STRING);
    }

    /** Fitted attachments other than the sight, slot -> item id. */
    public static Map<String, String> attachments(@Nullable ItemStack stack) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        String raw = stack == null || stack.isEmpty() ? null : stack.getPersistentDataContainer().get(Keys.ATTACH, PersistentDataType.STRING);
        if (raw != null) {
            for (String pair : raw.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && eq < pair.length() - 1) {
                    out.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        }
        return out;
    }

    /** The gun with an attachment fitted in a slot (null empties it); magazine, mode, loaner and test flags are kept. */
    public ItemStack withAttachment(ItemStack gun, String slot, @Nullable String id) {
        ItemRecord record = recordOf(gun);
        if (record == null) {
            return gun;
        }
        Map<String, String> attach = record.attach == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(record.attach);
        if (id == null) {
            attach.remove(slot);
        } else {
            attach.put(slot, id);
        }
        record.attach = attach.isEmpty() ? null : attach;
        int mode = mode(gun);
        boolean range = isRange(gun);
        ItemStack rebuilt = fromRecord(record);
        if (rebuilt == null) {
            return gun;
        }
        setMode(rebuilt, mode);
        return range ? markRange(rebuilt) : rebuilt;
    }

    /** The gun with a different optic mounted (null removes it); magazine, mode, loaner and test flags are kept. */
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
        ItemStack rebuilt = fromRecord(packRecord(stack));
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
        // The tooltip's frame takes the rarity colour (tools/art/build.py, build_tooltips).
        stack.setData(DataComponentTypes.TOOLTIP_STYLE, Key.key("craftov", "rarity_" + def.rarity().name().toLowerCase(java.util.Locale.ROOT)));
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.EQUIPPABLE, DataComponentTypes.CAN_BREAK,
                        DataComponentTypes.UNBREAKABLE)
                .build());
        GunSpec gun = def.gun();
        MedSpec med = def.med();
        if (gun != null) {
            // Left mouse fires by "mining" an invisible capture block in front of the crosshair (see
            // TriggerService): guns may dig in adventure mode, but never break anything, never swing
            // and never show the attack-cooldown indicator.
            stack.setData(DataComponentTypes.CAN_BREAK, ItemAdventurePredicate.itemAdventurePredicate(
                    List.of(BlockPredicate.predicate().build())));
            // Never triggered by use (guns have no use action); the server sets this cooldown on every shot
            // and for a reload, and the client plays the kick or the reload frames of the item model while
            // it counts down.
            stack.setData(DataComponentTypes.USE_COOLDOWN, UseCooldown.useCooldown(0.05f).cooldownGroup(RELOAD_GROUP).build());
            stack.setData(DataComponentTypes.SWING_ANIMATION, SwingAnimation.swingAnimation()
                    .type(SwingAnimation.Animation.NONE).duration(1).build());
            // Room for the hand marks (damage 1 to 5, see setHandMark). Unbreakable, so it is never worn
            // down, shows no durability bar and no tooltip line; a damageable item may not stack. A gun
            // starts put away: switched to, it shows low at once and comes up as it is drawn.
            stack.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
            stack.setData(DataComponentTypes.MAX_DAMAGE, MARK_HOLSTER);
            stack.setData(DataComponentTypes.DAMAGE, MARK_HOLSTER);
            stack.setData(DataComponentTypes.UNBREAKABLE);
            stack.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes()
                    .addModifier(Attribute.BLOCK_BREAK_SPEED, new AttributeModifier(GUN_BREAK, -1.0,
                            AttributeModifier.Operation.MULTIPLY_SCALAR_1), EquipmentSlotGroup.MAINHAND)
                    .addModifier(Attribute.ATTACK_SPEED, new AttributeModifier(GUN_ATTACK, 100.0,
                            AttributeModifier.Operation.ADD_NUMBER), EquipmentSlotGroup.MAINHAND)
                    .build());
        } else if (def.isMelee()) {
            // Knives have no use action either: only F (InspectService) sets this cooldown, and the
            // client plays the inspect frames of the item model while it counts down.
            stack.setData(DataComponentTypes.USE_COOLDOWN, UseCooldown.useCooldown(0.05f).cooldownGroup(INSPECT_GROUP).build());
            MeleeSpec melee = def.melee();
            if (melee != null) {
                knife(stack, melee);
            }
        } else if (def.throwable() != null) {
            // Only the server sets this cooldown, after a throw; the client shades grenades alone meanwhile.
            stack.setData(DataComponentTypes.USE_COOLDOWN, UseCooldown.useCooldown(0.05f).cooldownGroup(THROW_GROUP).build());
            // Holding right mouse "uses" the grenade, which is how the server learns when the button
            // comes up again (ThrowableService cooks it meanwhile). The use never finishes: a grenade
            // goes off long before ten minutes, and the eating sound only starts after a fifth of
            // the use. No use pose (the trident's turns the flat sprite into a slab across the top
            // of the screen), and walking and sprinting stay as they are.
            stack.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                    .consumeSeconds(600f)
                    .animation(ItemUseAnimation.NONE)
                    .sound(Key.key("craftov", "throw.pin"))
                    .hasConsumeParticles(false)
                    .build());
            stack.setData(DataComponentTypes.USE_EFFECTS, UseEffects.useEffects()
                    .canSprint(true)
                    .speedMultiplier(1.0f)
                    .interactVibrations(false)
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
            if (record.attach != null && !record.attach.isEmpty() && def.isGun()) {
                StringBuilder fitted = new StringBuilder();
                record.attach.forEach((slot, id) -> fitted.append(fitted.isEmpty() ? "" : ";").append(slot).append('=').append(id));
                pdc.set(Keys.ATTACH, PersistentDataType.STRING, fitted.toString());
            }
            if (record.isKit()) {
                pdc.set(Keys.KIT, PersistentDataType.BYTE, (byte) 1);
            }
            if (record.insured != null) {
                pdc.set(Keys.INSURED, PersistentDataType.STRING, record.insured);
            }
            if (record.insuredAs != null) {
                pdc.set(Keys.INSURED_AS, PersistentDataType.STRING, GSON.toJson(record.insuredAs));
            }
            if (record.isTest()) {
                pdc.set(Keys.TEST, PersistentDataType.BYTE, (byte) 1);
            }
        });
        if (def.isGun()) {
            applyModelData(stack);
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

    /**
     * Storable form of a stack, or null for non-Craftov stacks, shooting-range loaners and proving-ground
     * armory items (never persisted). Armory items packed inside a rig or backpack are left out of it.
     * Stash, traders, the market, the secure container, collection, raid value and the lobby loadout all
     * read items through this, so test items count for nothing anywhere.
     */
    public @Nullable ItemRecord toRecord(@Nullable ItemStack stack) {
        return isRange(stack) ? null : persistent(recordOf(stack));
    }

    /**
     * In-raid form of a stack: like {@link #toRecord} but keeps proving-ground armory items, flagged, so
     * they survive being packed into a rig or backpack (or a disconnect) and come back as test items.
     * Never store it; {@link #persistent} turns it into something that may be stored.
     */
    public @Nullable ItemRecord packRecord(@Nullable ItemStack stack) {
        return isRange(stack) ? null : recordOf(stack);
    }

    /** The record without proving-ground armory items: null if it is one, otherwise minus any packed inside. */
    public static @Nullable ItemRecord persistent(@Nullable ItemRecord record) {
        if (record == null || record.isTest()) {
            return null;
        }
        if (record.contents == null || record.contents.stream().noneMatch(ItemRecord::isTest)) {
            return record;
        }
        ItemRecord copy = record.copy();
        copy.contents.removeIf(ItemRecord::isTest);
        if (copy.contents.isEmpty()) {
            copy.contents = null;
        }
        return copy;
    }

    private @Nullable ItemRecord recordOf(@Nullable ItemStack stack) {
        String id = idOf(stack);
        if (id == null) {
            return null;
        }
        // An id that items.yml no longer has (removed, renamed, a broken file) still gets its record:
        // the stack stays in the loadout and comes back once the definition does.
        PersistentDataContainerView pdc = stack.getPersistentDataContainer();
        ItemRecord record = new ItemRecord(id, stack.getAmount());
        record.uid = pdc.get(Keys.UID, PersistentDataType.STRING);
        record.ammo = pdc.get(Keys.AMMO, PersistentDataType.INTEGER);
        record.ammoType = pdc.get(Keys.AMMO_TYPE, PersistentDataType.STRING);
        record.dura = pdc.get(Keys.DURA, PersistentDataType.DOUBLE);
        String contents = pdc.get(Keys.CONTENTS, PersistentDataType.STRING);
        if (contents != null && !contents.isEmpty()) {
            record.contents = GSON.fromJson(contents, RECORD_LIST);
        }
        record.optic = pdc.get(Keys.OPTIC, PersistentDataType.STRING);
        Map<String, String> attach = attachments(stack);
        record.attach = attach.isEmpty() ? null : attach;
        record.kit = pdc.has(Keys.KIT) ? Boolean.TRUE : null;
        record.insured = pdc.get(Keys.INSURED, PersistentDataType.STRING);
        record.insuredAs = insuredAs(pdc.get(Keys.INSURED_AS, PersistentDataType.STRING));
        record.test = isTest(stack) ? Boolean.TRUE : null;
        return record;
    }

    /** The deploy snapshot InsuranceService stored on an insured item, or null (none, or unreadable). */
    private static @Nullable ItemRecord insuredAs(@Nullable String json) {
        if (json == null) {
            return null;
        }
        try {
            return GSON.fromJson(json, ItemRecord.class);
        } catch (JsonParseException ex) {
            return null;
        }
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
        ItemDef def = defOf(stack);
        if (def != null && def.isGun()) {
            applyModelData(stack);
        }
        refreshLore(stack, def);
    }

    /**
     * A knife stabs rather than swings, and the client knows its rhythm and reach: the swing is a
     * thrust (first person, and the arm stab others see), the attack indicator fills over exactly the
     * knife's cooldown (the server takes one hit per cooldown, items.yml melee.cooldown, so a cut made
     * before it is full does nothing, as the indicator now shows), and the client picks targets out
     * to the knife's reach and no further.
     */
    private static void knife(ItemStack stack, MeleeSpec melee) {
        int cooldown = Math.max(1, melee.cooldownTicks());
        stack.setData(DataComponentTypes.SWING_ANIMATION, SwingAnimation.swingAnimation()
                .type(SwingAnimation.Animation.STAB).duration(Math.max(4, Math.min(8, cooldown - 2))).build());
        float reach = (float) melee.reach();
        stack.setData(DataComponentTypes.ATTACK_RANGE, io.papermc.paper.datacomponent.item.AttackRange.attackRange()
                .minReach(0f).maxReach(reach).minCreativeReach(0f).maxCreativeReach(reach).hitboxMargin(0.3f).mobFactor(1f).build());
        stack.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes()
                .addModifier(Attribute.ATTACK_SPEED, new AttributeModifier(KNIFE_ATTACK, 20.0 / cooldown - BASE_ATTACK_SPEED,
                        AttributeModifier.Operation.ADD_NUMBER), EquipmentSlotGroup.MAINHAND)
                .build());
    }

    /**
     * What the gun's item model reads from custom_model_data strings: 0 the sight, 1..4 the other
     * attachments (Attachments.SLOTS order). An empty string means nothing there. (Until item version
     * 10, string 5 said "dry" so the model would not flash on an empty magazine; the flash now follows
     * the shots the server fires.)
     */
    private static void applyModelData(ItemStack stack) {
        var cmd = io.papermc.paper.datacomponent.item.CustomModelData.customModelData();
        String optic = opticId(stack);
        cmd.addString(optic == null ? "" : optic);
        Map<String, String> fitted = attachments(stack);
        for (String slot : net.infnetwork.snowball.craftov.weapon.Attachments.SLOTS) {
            cmd.addString(fitted.getOrDefault(slot, ""));
        }
        stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, cmd.build());
    }

    /** The hand mark a gun's stack carries (see {@link #setHandMark}). */
    public static int handMark(@Nullable ItemStack stack) {
        if (stack == null || !stack.hasData(DataComponentTypes.DAMAGE)) {
            return MARK_NONE;
        }
        Integer damage = stack.getData(DataComponentTypes.DAMAGE);
        return damage == null ? MARK_NONE : damage;
    }

    /** Marks a gun as still coming up after a switch, or clears every mark (see {@link #setHandMark}). */
    public static void setDrawing(ItemStack stack, boolean drawing) {
        setHandMark(stack, drawing ? MARK_DRAW : MARK_NONE);
    }

    /** Marks a gun as put away: out of the hand (dropped, stowed, in a container), see {@link #setHandMark}. */
    public static void setHolstered(ItemStack stack) {
        setHandMark(stack, MARK_HOLSTER);
    }

    /**
     * Tells the gun's item model what the gun is doing besides being held: {@link #MARK_DRAW} while it
     * comes up after a switch (the model ignores the shot cooldown, so the client shows no kick while
     * the server allows no shot), {@link #MARK_RELOAD} while it reloads (the gun's cooldown then runs
     * the reload frames instead of a shot's kick), {@link #MARK_AIM} while the sights come up (the
     * cooldown runs those frames) and {@link #MARK_AIMED} once they are up (aimed pose, or a scope's
     * mask; the cooldown is a shot's kick again). Right mouse itself changes nothing on the client:
     * the sights show when the server aims, so the view, the spread and the pose always agree.
     * {@link #MARK_HOLSTER} is every gun out of the hand: the model shows the first frame of the draw
     * (the gun low and canted), so a switch to it shows it low at once, while the server's draw mark
     * and the cooldown that runs the draw frames are still on their way. The mark is the stack's
     * damage, against max_damage 5, because damage is the only component the vanilla 1.21.11 client
     * swaps in on a held item without lowering and raising it again (DataComponentType.
     * ignoreSwapAnimation; the gun's item definitions also say hand_animation_on_swap false, so no
     * other change dips it either). "No mark" is a
     * damage of 0, never a missing component: ItemStack.matchesIgnoringComponents first compares how
     * many components the two stacks have, so a damage that came and went dipped the gun anyway (at
     * the start and end of every reload, and of the sights coming up).
     */
    public static void setHandMark(ItemStack stack, int mark) {
        stack.setData(DataComponentTypes.DAMAGE, Math.max(MARK_NONE, Math.min(MARK_HOLSTER, mark)));
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
        return value(record, false, false);
    }

    /** What one unit of a stack is worth: its value as though it held a single item. */
    public long unitValue(@Nullable ItemStack stack) {
        ItemRecord record = toRecord(stack);
        if (record == null) {
            return 0;
        }
        record.amount = 1; // a fresh record (recordOf), so the stack is not touched
        return value(record);
    }

    /**
     * What the trader pays against: {@link #value(ItemRecord)}, except that free-kit items count
     * nothing for themselves. Attachments, rounds in the magazine and packed contents still count,
     * since they need not have come from the kit.
     */
    public long tradeValue(ItemRecord record) {
        return value(record, true, false);
    }

    /**
     * What an item adds to gear value (战备值, see CarryService#gearValue): {@link #value(ItemRecord)},
     * except that melee weapons count nothing, packed ones included. A knife is never lost, so it
     * puts nothing at stake, and a ◈95,000 karambit alone must not open the higher tiers.
     */
    public long gearValue(ItemRecord record) {
        return value(record, false, true);
    }

    private long value(ItemRecord record, boolean trade, boolean gear) {
        ItemDef def = registry.get(record.id);
        if (def == null || record.isTest() || gear && (def.isMelee() || def.category() == Category.MELEE)) {
            return 0;
        }
        double unit = trade && record.isKit() ? 0 : def.value();
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
                total += value(child, trade, gear);
            }
        }
        ItemDef optic = registry.get(record.optic);
        if (optic != null) {
            total += optic.value();
        }
        if (record.attach != null) {
            for (String id : record.attach.values()) {
                ItemDef part = registry.get(id);
                if (part != null) {
                    total += part.value();
                }
            }
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
        lore.add(Text.mm(def.rarity().tag() + "▍" + def.rarity().displayName() + " <muted>" + type));
        for (String line : def.lore()) {
            lore.add(Text.mm("<muted>" + line));
        }
        lore.add(Component.empty());
        GunSpec gun = def.gun();
        if (gun != null) {
            int ammo = ammo(stack);
            String ammoType = ammoType(stack);
            ItemDef ammoDef = registry.get(ammoType);
            Map<String, net.infnetwork.snowball.craftov.weapon.AttachSpec> parts =
                    net.infnetwork.snowball.craftov.weapon.Attachments.resolve(gun, attachments(stack), registry);
            GunSpec withParts = net.infnetwork.snowball.craftov.weapon.Attachments.effective(gun, parts);
            int capacity = withParts.magazine();
            lore.add(Text.mm("<muted>口径 <white>" + gun.caliber() + "  <muted>弹匣 <white>" + ammo + "/" + capacity
                    + (ammoDef != null ? " <muted>(" + Text.escape(ammoDef.name()) + ")" : "")));
            StringBuilder modes = new StringBuilder();
            for (FireMode mode : gun.modes()) {
                if (!modes.isEmpty()) {
                    modes.append('/');
                }
                modes.append(mode.displayName());
            }
            lore.add(Text.mm("<muted>模式 <white>" + modes));
            // Penetration is the loaded round's; an empty gun shows its caliber's first round.
            List<ItemDef> rounds = registry.ammoFor(gun.caliber());
            ItemDef shown = ammoDef != null ? ammoDef : rounds.isEmpty() ? null : rounds.get(0);
            for (String line : GunStats.bars(registry, gun, withParts, shown)) {
                lore.add(Text.mm(line));
            }
            lore.add(Text.mm(GunStats.HINT));
            ItemDef optic = registry.get(opticId(stack));
            ItemDef builtIn = registry.get(gun.scope());
            if (optic != null) {
                lore.add(Text.mm("<muted>瞄准镜 " + optic.coloredName() + " <muted>(" + optic.optic().magnificationLabel() + " 倍)"));
            } else if (builtIn != null) {
                lore.add(Text.mm("<muted>瞄准镜 <white>自带 " + builtIn.optic().magnificationLabel() + " 倍"));
            } else if (!"none".equals(gun.mount())) {
                lore.add(Text.mm("<muted>瞄准镜 <white>机械瞄具 <dim>(可在改枪台安装瞄准镜)"));
            }
            for (Map.Entry<String, String> fitted : attachments(stack).entrySet()) {
                ItemDef part = registry.get(fitted.getValue());
                if (part != null) {
                    lore.add(Text.mm("<muted>" + net.infnetwork.snowball.craftov.weapon.Attachments.slotName(fitted.getKey())
                            + " " + part.coloredName()));
                }
            }
        }
        if (stack.getPersistentDataContainer().has(Keys.INSURED)) {
            lore.add(Text.mm("<ok>✔ 本局已投保"));
        }
        if (def.attach() != null) {
            var part = def.attach();
            List<String> fits = new ArrayList<>();
            if (part.mounts().contains("rifle")) {
                fits.add("步枪 / 冲锋枪");
            }
            if (part.mounts().contains("shotgun")) {
                fits.add("霰弹枪");
            }
            if (part.mounts().contains("pistol")) {
                fits.add("手枪");
            }
            lore.add(Text.mm("<muted>配件位 <white>" + net.infnetwork.snowball.craftov.weapon.Attachments.slotName(part.slot())
                    + "  <muted>适用 <white>" + String.join(" / ", fits)));
            String effects = net.infnetwork.snowball.craftov.weapon.Attachments.effects(part);
            if (!effects.isEmpty()) {
                lore.add(Text.mm(effects));
            }
        }
        if (def.optic() != null) {
            lore.add(Text.mm("<muted>放大倍率 <white>" + def.optic().magnificationLabel() + " 倍  <muted>适用 <white>"
                    + (def.optic().mounts().contains("pistol") ? "步枪 / 冲锋枪 / 霰弹枪 / 手枪" : "步枪 / 冲锋枪 / 霰弹枪")));
        }
        if (def.ammo() != null) {
            lore.add(Text.mm("<muted>口径 <white>" + def.ammo().caliber() + "  <muted>穿透等级 <white>" + def.ammo().penetration()));
        }
        MeleeSpec melee = def.melee();
        if (melee != null) {
            lore.add(Text.mm("<muted>伤害 <white>" + fmt(melee.damage()) + "  <muted>穿透等级 <white>" + melee.penetration()
                    + (melee.headMultiplier() != 1.0 ? "  <muted>头部 <white>×" + fmt(melee.headMultiplier()) : "")));
            lore.add(Text.mm("<muted>出手间隔 <white>" + fmt(melee.cooldownTicks() / 20.0) + "s  <muted>距离 <white>"
                    + fmt(melee.reach()) + " 格"));
        }
        if (def.isArmor()) {
            double dura = dura(stack, def.durability());
            lore.add(Text.mm("<muted>防护等级 <white>" + def.armorClass() + "  <muted>耐久 <white>"
                    + fmt(dura) + "/" + fmt(def.durability())));
            double fullAbove = net.infnetwork.snowball.craftov.CraftovPlugin.get().getConfig()
                    .getDouble("combat.armor.full-protection-above", net.infnetwork.snowball.craftov.combat.DamageService.FULL_PROTECTION_ABOVE);
            if (fullAbove > 0) {
                lore.add(Text.mm("<muted>耐久低于 " + Math.round(Math.min(1, fullAbove) * 100) + "% 时防护下降"));
            }
        }
        if (def.isContainer()) {
            int stored = hasContents(stack) ? contents(stack).size() : 0;
            lore.add(Text.mm("<muted>容量 <white>" + def.slots() + " 格" + (stored > 0 ? "  <accent>内含 " + stored + " 件物品" : "")));
        }
        MedSpec med = def.med();
        if (med != null) {
            if (med.pool() > 0) {
                lore.add(Text.mm("<muted>剩余治疗量 <white>" + fmt(dura(stack, med.pool())) + "/" + fmt(med.pool())));
            }
            lore.add(Text.mm("<muted>使用时间 <white>" + fmt(med.useSeconds()) + "s"));
        }
        if (def.category() == Category.KEY) {
            lore.add(Text.mm("<muted>剩余次数 <white>" + fmt(dura(stack, def.durability()))));
        }
        // A stackable item shows one unit's weight and price: lore that follows the amount would make
        // two stacks of different sizes different items, which neither the client's click merge nor
        // CarryService#give (isSimilar) would put together, and would go stale whenever the amount
        // changes without a rebuild (a reload, a partial merge). The trader shows the whole stack.
        boolean stackable = def.maxStack() > 1;
        String unit = def.category() == Category.AMMO ? "发" : "个";
        // The secure container counts cells by size (SecureMenu): what a find costs there is worth
        // knowing before picking it up. Armory and range loaners never go in, so they say nothing.
        String secure = !def.fitsSecure() || isTest(stack) || isRange(stack) ? ""
                : "  <muted>安全箱 <white>" + def.size() + " 格" + (stackable ? "<muted>/" + def.secureStack() + " " + unit : "");
        lore.add(Text.mm("<muted>重量 <white>" + fmtWeight(def.weight()) + "kg" + (stackable ? "<muted>/" + unit : "") + secure));
        if (isKit(stack)) {
            lore.add(Text.mm("<accent>免费补给 · 商人按 ◈0 回收，不能上架交易行"));
        }
        if (isRange(stack)) {
            lore.add(Text.mm("<accent>靶场专用 · 不能带出靶场，离开时自动收回"));
        } else if (isTest(stack)) {
            lore.add(Text.mm("<accent>试验场物资 · 没有价值，不能带出对局，撤离时收回"));
        } else {
            lore.add(stackable
                    ? Text.mm("<muted>单价 <accent>◈ " + Text.money(unitValue(stack)) + "<muted>/" + unit)
                    : Text.mm("<muted>价值 <accent>◈ " + Text.money(value(stack))));
        }
        stack.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format("%.1f", value);
    }

    /** A weight in kg to the gram, trailing zeros dropped: a round of 0.012kg must not read 0.0. */
    private static String fmtWeight(double kg) {
        return java.math.BigDecimal.valueOf(kg).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
