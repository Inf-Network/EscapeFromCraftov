package net.infnetwork.snowball.craftov.hud;

/**
 * The newer HUD values (#6), kept apart from {@link HudSnapshot}'s long positional list: colour
 * states, wound flags, the squad rows, the kill feed, the alert banner and the interaction prompt.
 * Strings are BetterHud text (its own colour tags, see {@code Style.tag}); empty means "not shown".
 *
 * @param hpState      0 healthy, 1 hurt (60 or less), 2 critical (30 or less)
 * @param timeState    0 more than five minutes left, 1 five minutes or less, 2 a minute or less
 * @param injuryFlags  bits: 1 bleeding, 2 heavy bleeding, 4 fracture, 8 painkiller, 16 overloaded
 */
public record HudExtra(int hpState, int timeState, int injuryFlags, boolean ammoEmpty, String[] mates, String[] feed,
                       String alert, String prompt) {

    public static final int BLEED = 1;
    public static final int HEAVY = 2;
    public static final int FRACTURE = 4;
    public static final int PAIN = 8;
    public static final int OVERLOADED = 16;

    public static final HudExtra EMPTY = new HudExtra(0, 0, 0, false, new String[]{"", ""}, new String[]{"", "", "", ""}, "", "");

    public boolean flag(int bit) {
        return (injuryFlags & bit) != 0;
    }

    public String mate(int index) {
        return index < mates.length ? mates[index] : "";
    }

    public String feed(int index) {
        return index < feed.length ? feed[index] : "";
    }
}
