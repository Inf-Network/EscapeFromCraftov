package net.infnetwork.snowball.craftov.item;

import net.infnetwork.snowball.craftov.combat.MedSpec;
import net.infnetwork.snowball.craftov.weapon.AmmoSpec;
import net.infnetwork.snowball.craftov.weapon.GunSpec;
import net.infnetwork.snowball.craftov.weapon.ThrowableSpec;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Immutable definition loaded from items.yml.
 *
 * @param value   trader reference value (per unit for stackables)
 * @param slots   storage slots for rigs/backpacks
 * @param armorClass protection level 1..6 for helmets/armor
 * @param durability max durability for armor, uses for keys
 * @param lockId  door lock opened by a key
 * @param size    cells the item takes in the secure container
 * @param secureStack how many of a stacking item share those cells (30 rounds of ammo)
 */
public record ItemDef(
        String id,
        String name,
        Category category,
        Rarity rarity,
        long value,
        int maxStack,
        String model,
        List<String> lore,
        @Nullable GunSpec gun,
        @Nullable AmmoSpec ammo,
        @Nullable MedSpec med,
        @Nullable ThrowableSpec throwable,
        int slots,
        int armorClass,
        double durability,
        double speedPenalty,
        @Nullable String equipmentAsset,
        @Nullable String lockId,
        boolean tradeable,
        boolean lootable,
        double weight,
        net.infnetwork.snowball.craftov.weapon.@Nullable OpticSpec optic,
        net.infnetwork.snowball.craftov.weapon.@Nullable MeleeSpec melee,
        net.infnetwork.snowball.craftov.weapon.@Nullable AttachSpec attach,
        int size,
        int secureStack
) {

    /** True for what the secure container takes: anything but guns, armour, rigs and backpacks. */
    public boolean fitsSecure() {
        return !isGun() && !isArmor() && !isContainer() && category != Category.WEAPON;
    }

    /**
     * Secure-container cells {@code amount} of this item take: its size for every
     * {@link #secureStack} begun, so a box of 60 rounds takes two cells and a statue several.
     */
    public int secureCells(int amount) {
        return size * Math.max(1, (amount + secureStack - 1) / secureStack);
    }

    public boolean isMelee() {
        return melee != null;
    }

    public boolean isGun() {
        return gun != null;
    }

    public boolean isContainer() {
        return category == Category.RIG || category == Category.BACKPACK;
    }

    public boolean isArmor() {
        return category == Category.HELMET || category == Category.ARMOR;
    }

    public boolean hasDurability() {
        return durability > 0;
    }

    public String coloredName() {
        return rarity.tag() + net.infnetwork.snowball.craftov.util.Text.escape(name);
    }
}
