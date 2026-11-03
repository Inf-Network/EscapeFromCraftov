package net.infnetwork.snowball.craftov.raid;

public enum RaidMode {
    SOLO("单人", 1),
    TRIO("三人小队", 3);

    private final String displayName;
    private final int squadSize;

    RaidMode(String displayName, int squadSize) {
        this.displayName = displayName;
        this.squadSize = squadSize;
    }

    public String displayName() {
        return displayName;
    }

    public int squadSize() {
        return squadSize;
    }
}
