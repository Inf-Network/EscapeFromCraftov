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
 * <p>
 * The title is drawn in the craftov:ui pixel font; a menu with a screen id also gets that screen's
 * designed background behind the window ({@link UiFont}).
 */
public abstract class Menu implements InventoryHolder {

    protected final Player viewer;
    private final int rows;
    private final @Nullable String screen;
    private Component title;
    private @Nullable Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();

    protected Menu(Player viewer, int rows, Component title) {
        this(viewer, rows, null, title);
    }

    /** A menu drawn on a designed screen background (tools/art/screens.py, SCREENS). */
    protected Menu(Player viewer, int rows, @Nullable String screen, Component title) {
        this.viewer = viewer;
        this.rows = rows;
        this.screen = screen;
        this.title = title;
    }

    protected abstract void draw();

    public void open() {
        inventory = Bukkit.createInventory(this, rows * 9, UiFont.title(screen, title));
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

    /**
     * Changes the title in place (the window stays open, the cursor where it was); reopens the menu
     * only if that is not possible.
     */
    public void retitle(Component newTitle) {
        if (newTitle.equals(title)) {
            return;
        }
        this.title = newTitle;
        if (inventory != null && viewer.getOpenInventory().getTopInventory() == inventory
                && net.infnetwork.snowball.craftov.util.Nms.retitle(viewer, UiFont.title(screen, title))) {
            return;
        }
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
            inventory = Bukkit.createInventory(this, rows * 9, UiFont.title(screen, title));
        }
        return inventory;
    }

    protected @Nullable Inventory inventory() {
        return inventory;
    }
}
