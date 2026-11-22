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
    /** Build revision of the item's components, so old stacks can be rebuilt after updates. */
    public static final NamespacedKey VERSION = key("v");
    /** Optic item id mounted on a gun. */
    public static final NamespacedKey OPTIC = key("optic");
    /** Fitted attachments other than the sight: "slot=itemId;slot=itemId". */
    public static final NamespacedKey ATTACH = key("attach");
    /** Insured for one raid: "ownerUuid:raidId" (InsuranceService). */
    public static final NamespacedKey INSURED = key("insured");
    /** An insured item as it was at deploy (ItemRecord JSON), so a claim returns no more than that. */
    public static final NamespacedKey INSURED_AS = key("insured_as");
    /** Shooting-range loaner: never stored, sold or carried out of the range. */
    public static final NamespacedKey RANGE = key("range");
    /** Handed out by the free kit: the trader pays nothing for it and it cannot be listed on the market. */
    public static final NamespacedKey KIT = key("kit");
    /** Proving-ground armory item: usable in its raid, never stored, sold or carried home. */
    public static final NamespacedKey TEST = key("test");
    /** Marks UI-only stacks (locked slots, menu buttons) that must never leave their slot. */
    public static final NamespacedKey UI = key("ui");
    /** Marks Craftov-managed entities (containers, corpses, AI) with their role. */
    public static final NamespacedKey ENTITY_ROLE = key("role");
    public static final NamespacedKey ENTITY_REF = key("ref");
    /**
     * A disconnected player's stand-in (RaidManager), holding the player's UUID. Deliberately not
     * an {@link #ENTITY_ROLE}: bullets and grenades pass over entities that have one.
     */
    public static final NamespacedKey STAND_IN = key("stand_in");

    private Keys() {
    }

    public static NamespacedKey key(String path) {
        return new NamespacedKey(NS, path);
    }
}
