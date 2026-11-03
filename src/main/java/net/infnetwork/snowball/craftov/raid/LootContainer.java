package net.infnetwork.snowball.craftov.raid;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.loot.ContainerType;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** A searchable container in a raid: crates, safes, corpses, AI drops. Contents are shared. */
@SuppressWarnings("UnstableApiUsage")
public final class LootContainer {

    public final String id;
    public final ContainerType type;
    public final Location location;
    public final int shift;
    public final String title;
    private final ItemStack[] slots;
    private final boolean[] revealed;
    private boolean generated;
    private double progressMs;
    private long lastSearchTick = -1;
    private @Nullable ItemDisplay display;
    private @Nullable Interaction hitbox;
    private @Nullable Entity corpse;

    public LootContainer(String id, ContainerType type, Location location, int shift, String title) {
        this.id = id;
        this.type = type;
        this.location = location;
        this.shift = shift;
        this.title = title;
        this.slots = new ItemStack[type.rows() * 9];
        this.revealed = new boolean[slots.length];
    }

    public int size() {
        return slots.length;
    }

    public @Nullable ItemStack get(int slot) {
        return slots[slot];
    }

    public void set(int slot, @Nullable ItemStack stack) {
        slots[slot] = stack == null || stack.isEmpty() ? null : stack;
        if (slots[slot] != null) {
            revealed[slot] = true;
        }
    }

    public boolean isRevealed(int slot) {
        return revealed[slot];
    }

    public boolean generated() {
        return generated;
    }

    /** Fills the container with pre-made items (corpses) that still need searching. */
    public void fill(List<ItemStack> items, boolean shuffle) {
        generated = true;
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            free.add(i);
        }
        if (shuffle) {
            Collections.shuffle(free);
        }
        int index = 0;
        for (ItemStack stack : items) {
            if (index >= free.size()) {
                break;
            }
            int slot = shuffle ? free.get(index) : index;
            slots[slot] = stack;
            revealed[slot] = false;
            index++;
        }
    }

    public void generate(List<ItemStack> items) {
        fill(items, true);
    }

    public int hiddenCount() {
        int count = 0;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && !revealed[i]) {
                count++;
            }
        }
        return count;
    }

    public int nextHidden() {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && !revealed[i]) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Advances the shared search by one tick. Returns the slot that was revealed this tick, or -1.
     */
    public int searchTick(double speed, long tick) {
        if (lastSearchTick == tick) {
            return -1;
        }
        lastSearchTick = tick;
        int next = nextHidden();
        if (next < 0) {
            progressMs = 0;
            return -1;
        }
        progressMs += 50.0 * speed * type.searchSpeed();
        double need = revealTimeMs(next);
        if (progressMs >= need) {
            progressMs = 0;
            revealed[next] = true;
            return next;
        }
        return -1;
    }

    public double progress() {
        int next = nextHidden();
        return next < 0 ? 1.0 : Math.min(1.0, progressMs / revealTimeMs(next));
    }

    private double revealTimeMs(int slot) {
        ItemStack stack = slots[slot];
        net.infnetwork.snowball.craftov.item.ItemDef def = net.infnetwork.snowball.craftov.CraftovPlugin.get().items().defOf(stack);
        double seconds = def == null ? 0.6 : def.rarity().searchSeconds();
        return seconds * 1000.0;
    }

    public boolean isEmpty() {
        for (ItemStack s : slots) {
            if (s != null) {
                return false;
            }
        }
        return true;
    }

    public List<ItemStack> allItems() {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : slots) {
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    public int firstEmpty() {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ world entities

    public void spawn(float yaw) {
        Location base = location.clone();
        base.setYaw(0);
        base.setPitch(0);
        if (type.model() != null && !type.model().isEmpty() && !"none".equals(type.model())) {
            ItemStack visual = ItemStack.of(Material.PAPER);
            visual.setData(DataComponentTypes.ITEM_MODEL, Key.key(type.model()));
            display = base.getWorld().spawn(base.clone().add(0, 0.5, 0), ItemDisplay.class, d -> {
                d.setItemStack(visual);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setPersistent(false);
                d.setBrightness(null);
                d.setViewRange(1.2f);
                d.setShadowRadius(0.4f);
                d.setShadowStrength(0.6f);
                float s = type.scale();
                d.setTransformation(new Transformation(new Vector3f(),
                        new Quaternionf(new AxisAngle4f((float) Math.toRadians(-yaw), 0, 1, 0)),
                        new Vector3f(s, s, s), new Quaternionf()));
                tag(d);
            });
        }
        hitbox = base.getWorld().spawn(base, Interaction.class, i -> {
            i.setInteractionWidth((float) type.hitboxWidth());
            i.setInteractionHeight((float) type.hitboxHeight());
            i.setResponsive(true);
            i.setPersistent(false);
            tag(i);
        });
    }

    public void attachCorpse(Entity corpse) {
        this.corpse = corpse;
        tag(corpse);
    }

    private void tag(Entity entity) {
        entity.getPersistentDataContainer().set(Keys.ENTITY_ROLE, PersistentDataType.STRING, "container");
        entity.getPersistentDataContainer().set(Keys.ENTITY_REF, PersistentDataType.STRING, id);
    }

    public void remove() {
        if (display != null) {
            display.remove();
        }
        if (hitbox != null) {
            hitbox.remove();
        }
        if (corpse != null) {
            corpse.remove();
        }
    }

    public static String newId() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFFFL) + Bukkit.getCurrentTick();
    }
}
