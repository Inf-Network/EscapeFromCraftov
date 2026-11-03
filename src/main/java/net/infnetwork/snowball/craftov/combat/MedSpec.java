package net.infnetwork.snowball.craftov.combat;

import java.util.Set;

/**
 * Medical item behaviour.
 *
 * @param useSeconds time the use animation takes
 * @param pool       total HP the item can restore before it is used up (0 = no healing)
 * @param healPerUse cap on HP restored by a single use
 * @param cures      injuries cured on completion
 * @param effect     special effect: painkiller, adrenaline, revive or none
 */
public record MedSpec(double useSeconds, double pool, double healPerUse, Set<Injury> cures, Effect effect, int effectSeconds) {
    public enum Effect {
        NONE, PAINKILLER, ADRENALINE, SELF_REVIVE
    }
}
