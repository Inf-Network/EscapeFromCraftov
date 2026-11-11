package net.infnetwork.snowball.craftov.item;

/** Item categories drive inventory rules, loot tables and trader menus. */
public enum Category {
    WEAPON("武器"),
    AMMO("弹药"),
    HELMET("头盔"),
    ARMOR("护甲"),
    RIG("胸挂"),
    BACKPACK("背包"),
    MEDICAL("医疗"),
    THROWABLE("投掷物"),
    KEY("钥匙"),
    VALUABLE("物资"),
    COLLECTIBLE("收藏品"),
    SPECIAL("特殊"),
    ATTACHMENT("配件"),
    MELEE("近战");

    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isGear() {
        return this == HELMET || this == ARMOR || this == RIG || this == BACKPACK;
    }
}
