package net.infnetwork.snowball.craftov.weapon;

/**
 * A melee weapon. A hit works like a bullet: it lands on a body part, a helmet or vest in the way
 * takes off what its class stops against the knife's penetration and wears down, and the rest is
 * dealt. Soldiers and players are cut the same way, so a knife is strong against bare targets and
 * weak against armour.
 *
 * @param damage         damage per hit before the body part and armour
 * @param penetration    armour class the blade overmatches, as a round's penetration (1..6)
 * @param armorDamage    multiplier on the durability a hit wears off armour
 * @param headMultiplier damage multiplier for a hit to the head
 * @param cooldownTicks  minimum ticks between hits
 * @param reach          hit distance in blocks
 */
public record MeleeSpec(double damage, int penetration, double armorDamage, double headMultiplier, int cooldownTicks, double reach) {
}
