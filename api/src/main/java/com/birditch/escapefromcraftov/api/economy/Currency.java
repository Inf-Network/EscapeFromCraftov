package com.birditch.escapefromcraftov.api.economy;

public enum Currency {
    COINS("金币");

    private final String displayName;

    Currency(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
