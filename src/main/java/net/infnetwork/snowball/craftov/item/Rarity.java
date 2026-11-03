package net.infnetwork.snowball.craftov.item;

import net.kyori.adventure.text.format.TextColor;

/** Six loot tiers, from junk to legendary. Search time grows with tier. */
public enum Rarity {
    WHITE("普通", "#C8CCD2", 0.6),
    GREEN("优秀", "#5BD16B", 0.9),
    BLUE("精良", "#4FA3FF", 1.3),
    PURPLE("稀有", "#B66BFF", 1.9),
    GOLD("史诗", "#FFC23D", 2.6),
    RED("传说", "#FF4D4D", 3.6);

    private final String displayName;
    private final String hex;
    private final double searchSeconds;

    Rarity(String displayName, String hex, double searchSeconds) {
        this.displayName = displayName;
        this.hex = hex;
        this.searchSeconds = searchSeconds;
    }

    public String displayName() {
        return displayName;
    }

    public String hex() {
        return hex;
    }

    public TextColor color() {
        return TextColor.fromHexString(hex);
    }

    public double searchSeconds() {
        return searchSeconds;
    }

    public int tier() {
        return ordinal() + 1;
    }

    public String tag() {
        return "<" + hex + ">";
    }

    public static Rarity parse(String raw) {
        return valueOf(raw.trim().toUpperCase());
    }
}
