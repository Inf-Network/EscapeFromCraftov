package net.infnetwork.snowball.craftov.combat;

import java.util.Set;

/**
 * Medical item behaviour.
 *
 * @param useSeconds time the use animation takes
 * @param pool       total HP the item can restore before it is used up (0 = no healing)
 * @param healPerUse cap on HP restored by a single use
 * @param cures      injury types this item treats
 * @param cureCount  how many wounds one use treats (most dangerous first)
 * @param effect     special effect: painkiller, adrenaline, revive or none
 * @param animation  vanilla use animation (brush, eat, trident, crossbow, bundle ...)
 * @param sound      craftov sound played when the use starts
 */
public record MedSpec(double useSeconds, double pool, double healPerUse, Set<Injury> cures, int cureCount, Effect effect,
                      int effectSeconds, String animation, String sound) {
    public enum Effect {
        NONE, PAINKILLER, ADRENALINE, SELF_REVIVE
    }
}
