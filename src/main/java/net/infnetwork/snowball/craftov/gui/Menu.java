package net.infnetwork.snowball.craftov.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Chest-style menu. Every click inside an open menu is cancelled and routed here; subclasses
 * implement {@link #draw()} and optionally {@link #onBottomClick} for clicks in the player inventory.
 */
public abstract class Menu implements InventoryHolder {

    protected final Player viewer;
    private final int rows;
    private Component title;
    private @Nullable Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();

    protected Menu(Player viewer, int rows, Component title) {
        this.viewer = viewer;
        this.rows = rows;
        this.title = title;
    }

    protected abstract void draw();

    public void open() {
        inventory = Bukkit.createInventory(this, rows * 9, title);
        redraw();
        viewer.openInventory(inventory);
    }

    public void redraw() {
        if (inventory == null) {
            return;
        }
        handlers.clear();
        inventory.clear();
        draw();
    }

    /** Re-creates the inventory with a new title (titles cannot change in place). */
    public void retitle(Component newTitle) {
        this.title = newTitle;
        open();
    }

    protected void set(int slot, ItemStack stack) {
        if (inventory != null && slot >= 0 && slot < inventory.getSize()) {
            inventory.setItem(slot, stack);
        }
    }

    protected void button(int slot, ItemStack stack, Consumer<InventoryClickEvent> handler) {
        set(slot, stack);
        handlers.put(slot, handler);
    }

    protected void fillRow(int row, ItemStack stack) {
        for (int i = 0; i < 9; i++) {
            set(row * 9 + i, stack);
        }
    }

    public void handleClick(InventoryClickEvent event) {
        if (event.getClickedInventory() == inventory) {
            Consumer<InventoryClickEvent> handler = handlers.get(event.getSlot());
            if (handler != null) {
                handler.accept(event);
            } else {
                onTopClick(event);
            }
        } else if (event.getClickedInventory() != null) {
            onBottomClick(event);
        }
    }

    protected void onTopClick(InventoryClickEvent event) {
    }

    protected void onBottomClick(InventoryClickEvent event) {
    }

    public void onClose() {
    }

    /** Called every tick while open (only for menus that return true from {@link #ticking()}). */
    public void tick() {
    }

    public boolean ticking() {
        return false;
    }

    public Player viewer() {
        return viewer;
    }

    public int rows() {
        return rows;
    }

    @Override
    public Inventory getInventory() {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, rows * 9, title);
        }
        return inventory;
    }

    protected @Nullable Inventory inventory() {
        return inventory;
    }
}
