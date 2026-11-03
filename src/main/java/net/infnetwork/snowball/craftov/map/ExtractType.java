package net.infnetwork.snowball.craftov.map;

public enum ExtractType {
    ALWAYS("常驻撤离"),
    TIMED("限时开放"),
    PAID("付费撤离"),
    SWITCH("需拉闸"),
    NO_BACKPACK("禁止背包"),
    LIMITED("限员撤离");

    private final String displayName;

    ExtractType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
