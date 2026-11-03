package net.infnetwork.snowball.craftov.weapon;

public enum FireMode {
    SEMI("单发"),
    BURST("三连发"),
    AUTO("全自动"),
    BOLT("栓动"),
    PUMP("泵动");

    private final String displayName;

    FireMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Modes that need the trigger released between shots. */
    public boolean singleAction() {
        return this == SEMI || this == BOLT || this == PUMP;
    }
}
