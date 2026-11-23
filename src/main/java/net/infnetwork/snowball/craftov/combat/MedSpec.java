package net.infnetwork.snowball.craftov.combat;

import java.util.List;
import java.util.Set;

/**
 * Medical item behaviour.
 *
 * @param useSeconds time the use animation takes
 * @param pool       total HP the item can restore before it is used up (0 = no healing); treating a
 *                   wound uses up some of it too (medical.wound-cost in config.yml)
 * @param healPerUse cap on HP restored by a single use
 * @param cures      injury types this item treats
 * @param cureCount  how many wounds one use treats (most dangerous first)
 * @param onePart    one use only treats wounds on one body part, the worst one (surgery kit)
 * @param effect     special effect: painkiller, adrenaline, revive or none
 * @param animation  vanilla use animation (none, eat ...): how the client moves the hand and arm;
 *                   the item model's own frames do the rest
 * @param cues       the sounds of a use, in the order they play (items.yml {@code cues})
 */
public record MedSpec(double useSeconds, double pool, double healPerUse, Set<Injury> cures, int cureCount, boolean onePart,
                      Effect effect, int effectSeconds, String animation, List<Cue> cues) {
    public enum Effect {
        NONE, PAINKILLER, ADRENALINE, SELF_REVIVE
    }

    /**
     * A sound (craftov:&lt;sound&gt;) heard by everyone nearby once {@code at} (0 to 1) of the use
     * time has passed: a wrapper torn open at the start, gauze wound on halfway, a strap pulled tight.
     */
    public record Cue(double at, String sound) {
    }
}
