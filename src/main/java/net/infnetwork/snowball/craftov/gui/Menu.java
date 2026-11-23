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
    /** The same result broken into tooltip lines (a long reason would run off the screen as one). */
    private java.util.List<Component> flashLore = java.util.List.of();
    private int flashSlot = -1;
    /** What {@link #flashSlot} showed when the result came: a redraw that puts something else there drops it. */
    private @Nullable ItemStack flashFor;
    private long flashUntil;
    /** True while a click in this menu is being handled: only what that click caused shows in the menu. */
    private boolean clicking;
    /** A redraw moved the result from a button that is gone to the title. */
    private boolean flashMoved;
    private boolean quietOpen;
    /** Tooltip width of a result line, in Text.wrap units (about 22 Chinese characters). */
    private static final int FLASH_WIDTH = 44;

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
        flashMoved = false;
        draw();
        if (flashMoved) {
            flashMoved = false;
            pushTitle();
        }
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
     * Shows the result of the click being handled inside the menu for two seconds: as an extra
     * tooltip line on the button that was clicked, or after the title when the click was in the
     * player's own inventory. Returns false when the menu is not open, or when no click of the
     * viewer's is being handled (an injury, a drone sighting, a tutorial reminder): those are not
     * an answer to any button and stay on the action bar.
     */
    public boolean flash(String miniMessage, @Nullable Boolean good) {
        if (!clicking || inventory == null || viewer.getOpenInventory().getTopInventory() != inventory) {
            return false;
        }
        String text = (good == null ? "" : good ? Style.MARK_OK : Style.MARK_FAIL) + miniMessage;
        Component line = net.infnetwork.snowball.craftov.util.Text.mm(text);
        flashLine = line;
        flashLore = net.infnetwork.snowball.craftov.util.Text.mm(net.infnetwork.snowball.craftov.util.Text.wrap(text, FLASH_WIDTH));
        flashSlot = lastSlot >= 0 && inventory.getItem(lastSlot) != null ? lastSlot : -1;
        flashFor = flashSlot >= 0 ? inventory.getItem(flashSlot) : null;
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
            flashFor = null;
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
        lore.addAll(flashLore);
        copy.setData(io.papermc.paper.datacomponent.DataComponentTypes.LORE, io.papermc.paper.datacomponent.item.ItemLore.lore(lore));
        return copy;
    }

    protected void set(int slot, ItemStack stack) {
        if (inventory != null && slot >= 0 && slot < inventory.getSize()) {
            // A redraw right after a failed click keeps the result on the button. If the slot now
            // holds something else (the item just sold, the letter just deleted), the result goes to
            // the title instead of the next item's tooltip.
            boolean flashing = slot == flashSlot && flashLine != null && Bukkit.getCurrentTick() < flashUntil;
            if (flashing && !sameThing(flashFor, stack)) {
                flashSlot = -1;
                flashFor = null;
                flashMoved = true;
                flashing = false;
            }
            inventory.setItem(slot, flashing ? withFlash(stack) : stack);
        }
    }

    /**
     * The same button or item, whatever its lore and count now say: same kind, model and name, the
     * figures in the name aside ("邮箱 3" is still the mailbox once it reads "邮箱 0").
     */
    private static boolean sameThing(@Nullable ItemStack a, ItemStack b) {
        if (a == null || a.getType() != b.getType()) {
            return false;
        }
        var model = io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL;
        return java.util.Objects.equals(a.getData(model), b.getData(model)) && label(a).equals(label(b));
    }

    private static String label(ItemStack stack) {
        Component name = stack.getData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_NAME);
        if (name == null) {
            name = stack.getData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_NAME);
        }
        return name == null ? "" : net.infnetwork.snowball.craftov.util.Text.plain(name).replaceAll("[0-9,.◈+×/ -]", "");
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
        clicking = true;
        try {
            dispatch(event);
        } finally {
            clicking = false;
        }
    }

    private void dispatch(InventoryClickEvent event) {
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
