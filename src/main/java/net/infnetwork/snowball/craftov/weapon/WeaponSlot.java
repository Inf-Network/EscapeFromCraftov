package net.infnetwork.snowball.craftov.weapon;

public enum WeaponSlot {
    PRIMARY("主武器"),
    SECONDARY("副武器");

    private final String displayName;

    WeaponSlot(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
