package net.infnetwork.snowball.craftov.weapon;

/**
 * @param penetration armor level this round defeats (1..6)
 * @param damageMult  multiplier on the gun's base damage
 * @param armorDamage multiplier on durability lost by armor that is hit
 */
public record AmmoSpec(String caliber, int penetration, double damageMult, double armorDamage) {
}
