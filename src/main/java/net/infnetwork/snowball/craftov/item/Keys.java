package net.infnetwork.snowball.craftov.item;

import org.bukkit.NamespacedKey;

/** Persistent data keys stored on Craftov item stacks and entities. */
public final class Keys {
    public static final String NS = "craftov";

    public static final NamespacedKey ITEM_ID = key("id");
    public static final NamespacedKey UID = key("uid");
    public static final NamespacedKey MODE = key("mode");
    public static final NamespacedKey AMMO = key("ammo");
    public static final NamespacedKey AMMO_TYPE = key("ammo_type");
    public static final NamespacedKey DURA = key("dura");
    public static final NamespacedKey CONTENTS = key("contents");
    /** Marks UI-only stacks (locked slots, menu buttons) that must never leave their slot. */
    public static final NamespacedKey UI = key("ui");
    /** Marks Craftov-managed entities (containers, corpses, AI) with their role. */
    public static final NamespacedKey ENTITY_ROLE = key("role");
    public static final NamespacedKey ENTITY_REF = key("ref");

    private Keys() {
    }

    public static NamespacedKey key(String path) {
        return new NamespacedKey(NS, path);
    }
}
