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
 * <p>
 * Menus sound like one device ({@link UiSound}): opening plays "open", every button "click" (tabs
 * "tab"), and closing the last menu "close". Results of a click come back in the menu itself
 * ({@link #flash}), because the action bar is hidden behind the window.
 */
public abstract class Menu implements InventoryHolder {

    protected final Player viewer;
    private final int rows;
    private @Nullable String screen;
    private Component title;
    private @Nullable Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();
    private final Map<Integer, UiSound> sounds = new HashMap<>();
    /** The top slot of the last button clicked, or -1 for a click in the player's own inventory. */
    private int lastSlot = -1;
    /** A result line shown on {@link #flashSlot}'s tooltip (or after the title) until {@link #flashUntil}. */
    private @Nullable Component flashLine;
    private int flashSlot = -1;
    private long flashUntil;
    private boolean quietOpen;

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
        String screenBefore = screen;
        Component titleBefore = title;
        inventory = Bukkit.createInventory(this, rows * 9, shownTitle());
        redraw();
        if (!java.util.Objects.equals(screenBefore, screen) || !titleBefore.equals(title)) {
            // draw() picked another screen or title (restyle): a title is fixed once the window exists.
            Inventory built = inventory;
            inventory = Bukkit.createInventory(this, rows * 9, shownTitle());
            inventory.setContents(built.getContents());
        }
        viewer.openInventory(inventory);
        if (!quietOpen && openSound()) {
            UiSound.OPEN.play(viewer);
        }
        quietOpen = false;
    }

    /** False for screens that come with a sound of their own (a container being opened). */
    protected boolean openSound() {
        return true;
    }

    /** The title as drawn: background, text and, for two seconds after a result, that result. */
    private Component shownTitle() {
        Component text = title;
        if (flashLine != null && flashSlot < 0 && Bukkit.getCurrentTick() < flashUntil) {
            text = text.append(Component.text("  ")).append(flashLine);
        }
        return UiFont.title(screen, text);
    }

    public void redraw() {
        if (inventory == null) {
            return;
        }
        handlers.clear();
        sounds.clear();
        inventory.clear();
        draw();
    }

    /**
     * Changes the title in place (the window stays open, the cursor where it was); reopens the menu
     * only if that is not possible.
     */
    public void retitle(Component newTitle) {
        restyle(screen, newTitle);
    }

    /**
     * Switches screen background and title together (a tab, a page, a state such as queued). While
     * the menu is being built for opening, the new values simply go into the window it opens.
     */
    protected void restyle(@Nullable String newScreen, Component newTitle) {
        if (newTitle.equals(title) && java.util.Objects.equals(newScreen, screen)) {
            return;
        }
        this.title = newTitle;
        this.screen = newScreen;
        if (inventory == null || viewer.getOpenInventory().getTopInventory() != inventory) {
            return;
        }
        pushTitle();
    }

    private void pushTitle() {
        if (inventory == null || viewer.getOpenInventory().getTopInventory() != inventory) {
            return;
        }
        if (!net.infnetwork.snowball.craftov.util.Nms.retitle(viewer, shownTitle())) {
            quietOpen = true;
            open();
        }
    }

    /**
     * Shows the result of the last click inside the menu for two seconds: as an extra tooltip line
     * on the button that was clicked, or after the title when the click was in the player's own
     * inventory. Returns false when the menu is not open (the caller uses the action bar instead).
     */
    public boolean flash(String miniMessage, @Nullable Boolean good) {
        if (inventory == null || viewer.getOpenInventory().getTopInventory() != inventory) {
            return false;
        }
        Component line = net.infnetwork.snowball.craftov.util.Text.mm((good == null ? "" : good ? Style.MARK_OK : Style.MARK_FAIL) + miniMessage);
        flashLine = line;
        flashSlot = lastSlot >= 0 && inventory.getItem(lastSlot) != null ? lastSlot : -1;
        flashUntil = Bukkit.getCurrentTick() + 40;
        if (flashSlot >= 0) {
            ItemStack shown = inventory.getItem(flashSlot);
            if (shown != null) {
                inventory.setItem(flashSlot, withFlash(shown));
            }
        } else {
            pushTitle();
        }
        Bukkit.getScheduler().runTaskLater(net.infnetwork.snowball.craftov.CraftovPlugin.get(), () -> {
            if (flashLine != line) {
                return; // a newer result took its place
            }
            int slot = flashSlot;
            flashLine = null;
            flashSlot = -1;
            if (inventory != null && viewer.getOpenInventory().getTopInventory() == inventory) {
                if (slot >= 0) {
                    redraw();
                } else {
                    pushTitle();
                }
            }
        }, 41L);
        return true;
    }

    private ItemStack withFlash(ItemStack stack) {
        ItemStack copy = stack.clone();
        java.util.List<Component> lore = new java.util.ArrayList<>();
        var data = copy.getData(io.papermc.paper.datacomponent.DataComponentTypes.LORE);
        if (data != null) {
            lore.addAll(data.lines());
        }
        lore.add(flashLine);
        copy.setData(io.papermc.paper.datacomponent.DataComponentTypes.LORE, io.papermc.paper.datacomponent.item.ItemLore.lore(lore));
        return copy;
    }

    protected void set(int slot, ItemStack stack) {
        if (inventory != null && slot >= 0 && slot < inventory.getSize()) {
            // A redraw right after a failed click keeps the result on the button.
            boolean flashing = slot == flashSlot && flashLine != null && Bukkit.getCurrentTick() < flashUntil;
            inventory.setItem(slot, flashing ? withFlash(stack) : stack);
        }
    }

    protected void button(int slot, ItemStack stack, Consumer<InventoryClickEvent> handler) {
        set(slot, stack);
        handlers.put(slot, handler);
        sounds.put(slot, UiSound.CLICK);
    }

    /** A tab or page button: sounds like a tab switch instead of a click. */
    protected void tabButton(int slot, ItemStack stack, Consumer<InventoryClickEvent> handler) {
        button(slot, stack, handler);
        sounds.put(slot, UiSound.TAB);
    }

    /**
     * A button drawn by the screen background across several slots: each slot gets an invisible item
     * carrying the same name and lore, and all of them run the one action.
     */
    protected void bigButton(int[] slots, String name, java.util.List<String> lore, Consumer<InventoryClickEvent> handler) {
        for (int slot : slots) {
            button(slot, UiItems.button("craftov:ui/blank", name, lore), handler);
            sounds.put(slot, UiSound.CONFIRM); // the big buttons are the main action of their screen
        }
    }

    /** The fixed close button of a screen's bottom bar. */
    protected void closeButton(int slot) {
        button(slot, UiItems.button("craftov:ui/icon_cancel", "<text><b>关闭", java.util.List.of("<muted>也可以按 Esc")),
                e -> viewer.closeInventory());
    }

    /** The fixed back button of a screen's bottom bar: returns to the lobby terminal. */
    protected void backButton(int slot, Runnable back) {
        button(slot, UiItems.button("craftov:ui/arrow_left", "<text><b>返回", java.util.List.of("<muted>回到战术终端")),
                e -> back.run());
    }

    protected void fillRow(int row, ItemStack stack) {
        for (int i = 0; i < 9; i++) {
            set(row * 9 + i, stack);
        }
    }

    public void handleClick(InventoryClickEvent event) {
        if (event.getClickedInventory() == inventory) {
            lastSlot = event.getSlot();
            Consumer<InventoryClickEvent> handler = handlers.get(event.getSlot());
            if (handler != null) {
                sounds.getOrDefault(event.getSlot(), UiSound.CLICK).play(viewer);
                handler.accept(event);
            } else {
                onTopClick(event);
            }
        } else if (event.getClickedInventory() != null) {
            lastSlot = -1;
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
            inventory = Bukkit.createInventory(this, rows * 9, shownTitle());
        }
        return inventory;
    }

    protected @Nullable Inventory inventory() {
        return inventory;
    }
}
