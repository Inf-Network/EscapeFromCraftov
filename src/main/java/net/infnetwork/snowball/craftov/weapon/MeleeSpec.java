package net.infnetwork.snowball.craftov.weapon;

/**
 * A melee weapon. Against AI it is tuned in hits to kill per enemy class rather than raw damage,
 * so each knife reads the same regardless of enemy health pools.
 *
 * @param hitsNormal  hits to kill a regular soldier (patrol)
 * @param hitsSpecial hits to kill a special unit (guard, sniper, boss escort)
 * @param hitsBoss    hits to kill a boss
 * @param playerDamage damage per hit against players (armor does not apply)
 * @param cooldownTicks minimum ticks between hits
 * @param reach       hit distance in blocks
 */
public record MeleeSpec(int hitsNormal, int hitsSpecial, int hitsBoss, double playerDamage, int cooldownTicks, double reach) {
}
