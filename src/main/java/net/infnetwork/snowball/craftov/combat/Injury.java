package net.infnetwork.snowball.craftov.combat;

public enum Injury {
    LIGHT_BLEED("轻度出血", "#FF8A80"),
    HEAVY_BLEED("重度出血", "#FF3B30"),
    FRACTURE("骨折", "#FFB74D");

    private final String displayName;
    private final String hex;

    Injury(String displayName, String hex) {
        this.displayName = displayName;
        this.hex = hex;
    }

    public String displayName() {
        return displayName;
    }

    public String hex() {
        return hex;
    }
}
