package net.infnetwork.snowball.craftov.item;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Serializable item instance used by stash, market, presets and nested container contents.
 * Fields are public and mutable so Gson can map them directly.
 */
public final class ItemRecord {
    public String id;
    public int amount = 1;
    /** Unique instance id for guns, so the in-memory magazine can find its stack again. */
    public @Nullable String uid;
    /** Rounds loaded in a gun magazine. */
    public @Nullable Integer ammo;
    /** Ammo item id loaded in the magazine. */
    public @Nullable String ammoType;
    /** Remaining durability / med points / key uses. */
    public @Nullable Double dura;
    /** Items stored inside a rig or backpack. */
    public @Nullable List<ItemRecord> contents;

    public ItemRecord() {
    }

    public ItemRecord(String id, int amount) {
        this.id = id;
        this.amount = amount;
    }

    public ItemRecord copy() {
        ItemRecord copy = new ItemRecord(id, amount);
        copy.uid = uid;
        copy.ammo = ammo;
        copy.ammoType = ammoType;
        copy.dura = dura;
        if (contents != null) {
            copy.contents = new ArrayList<>(contents.size());
            for (ItemRecord child : contents) {
                copy.contents.add(child.copy());
            }
        }
        return copy;
    }

    public boolean hasContents() {
        return contents != null && !contents.isEmpty();
    }

    /** Two records stack when everything but the amount matches. */
    public boolean stacksWith(ItemRecord other) {
        return Objects.equals(id, other.id)
                && uid == null && other.uid == null
                && Objects.equals(ammo, other.ammo)
                && Objects.equals(ammoType, other.ammoType)
                && Objects.equals(dura, other.dura)
                && !hasContents() && !other.hasContents();
    }

    /** Number of storage cells this record occupies, counting nested contents. */
    public int footprint() {
        int total = 1;
        if (contents != null) {
            for (ItemRecord child : contents) {
                total += child.footprint();
            }
        }
        return total;
    }
}
