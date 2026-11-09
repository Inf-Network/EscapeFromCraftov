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
        net.infnetwork.snowball.craftov.weapon.@Nullable OpticSpec optic
) {

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
