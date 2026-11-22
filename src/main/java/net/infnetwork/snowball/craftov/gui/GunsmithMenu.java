package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.util.Text;
import net.infnetwork.snowball.craftov.weapon.Attachments;
import net.infnetwork.snowball.craftov.weapon.GunSpec;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gunsmith bench: pick one of the guns you carry, then fit a sight or attachment you carry, or take a
 * fitted one off. Works in the lobby, on the range (loaners take only loaner parts) and in a raid.
 */
final class GunsmithMenu extends Menu {

    private static final String SIGHT = "sight";
    /** Bench row: muzzle, grip, sight, tactical, magazine. */
    private static final String[] ROW = {"muzzle", "grip", SIGHT, "tactical", "mag"};

    private final CraftovPlugin plugin;
    private int selected = -1;

    GunsmithMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 6, "gunsmith", Text.mm("改枪台"));
        this.plugin = plugin;
    }

    private List<Integer> carriedSlots(boolean guns) {
        List<Integer> out = new ArrayList<>();
        PlayerInventory inv = viewer.getInventory();
        for (int slot : plugin.carry().usableSlots(viewer)) {
            ItemDef def = plugin.items().defOf(inv.getItem(slot));
            if (def != null && (guns ? def.isGun() : def.optic() != null || def.attach() != null)) {
                out.add(slot);
            }
        }
        return out;
    }

    private static String slotOf(ItemDef part) {
        return part.optic() != null ? SIGHT : part.attach().slot();
    }

    private static String slotTitle(String slot) {
        return SIGHT.equals(slot) ? "瞄准镜" : Attachments.slotName(slot);
    }

    private @Nullable String fittedId(ItemStack gun, String slot) {
        return SIGHT.equals(slot) ? ItemFactory.opticId(gun) : ItemFactory.attachments(gun).get(slot);
    }

    /** Null if the part fits the gun, otherwise why not. */
    private @Nullable String misfit(GunSpec gun, ItemDef part) {
        if (part.optic() != null) {
            return "none".equals(gun.mount()) ? "这把枪的瞄准镜是固定的" : gun.accepts(part.optic()) ? null : "装不上这把枪";
        }
        return Attachments.fits(gun, part.attach()) ? null : "装不上这把枪";
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 9; i++) {
            set(9 + i, UiItems.filler());
            set(36 + i, UiItems.filler());
        }
        PlayerInventory inv = viewer.getInventory();
        List<Integer> guns = carriedSlots(true);
        if (guns.isEmpty()) {
            set(22, UiItems.button("craftov:ui/icon_help", "<muted>身上没有枪", List.of("<muted>把枪放进物品栏再来")));
            return;
        }
        if (!guns.contains(selected)) {
            selected = guns.contains(inv.getHeldItemSlot()) ? inv.getHeldItemSlot() : guns.get(0);
        }
        for (int i = 0; i < guns.size() && i < 9; i++) {
            int slot = guns.get(i);
            ItemStack icon = inv.getItem(slot).clone();
            if (slot == selected) {
                icon.setData(io.papermc.paper.datacomponent.DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
            button(i, icon, e -> {
                selected = slot;
                redraw();
            });
        }
        ItemStack gun = inv.getItem(selected);
        GunSpec spec = plugin.items().defOf(gun).gun();
        set(22, gun.clone());
        for (int i = 0; i < ROW.length; i++) {
            String slot = ROW[i];
            int at = 29 + i;
            ItemDef fitted = plugin.registry().get(fittedId(gun, slot));
            if (SIGHT.equals(slot) && "none".equals(spec.mount())) {
                ItemDef builtIn = plugin.registry().get(spec.scope());
                set(at, UiItems.button("craftov:ui/locked", "<muted>瞄准镜（固定）",
                        List.of("<muted>自带 " + (builtIn == null ? "机械瞄具" : builtIn.name()))));
            } else if (fitted != null) {
                button(at, withNote(plugin.items().create(fitted, 1), List.of("", "<danger>点击拆下")), e -> remove(slot));
            } else {
                set(at, UiItems.button("craftov:ui/blank", "<muted>" + slotTitle(slot) + "：空",
                        List.of(SIGHT.equals(slot) ? "<muted>机械瞄具" : "<muted>从下方选择配件安装")));
            }
        }
        int col = 0;
        for (int slot : carriedSlots(false)) {
            ItemStack stack = inv.getItem(slot);
            ItemDef def = plugin.items().defOf(stack);
            String why = misfit(spec, def);
            boolean sameKind = kind(stack) == kind(gun);
            ItemStack icon = stack.clone();
            icon.setAmount(1);
            List<String> note = new ArrayList<>();
            note.add("<muted>" + slotTitle(slotOf(def)));
            if (why != null) {
                note.add("<danger>" + why);
            } else if (!sameKind) {
                note.add("<danger>" + mixNote(gun, stack));
            } else {
                note.add("<ok>点击安装");
            }
            button(45 + col, withNote(icon, note), e -> install(slot));
            if (++col >= 9) {
                break;
            }
        }
        if (col == 0) {
            set(49, UiItems.button("craftov:ui/icon_help", "<muted>身上没有配件", List.of(
                    "<muted>瞄准镜和配件可以在商人「配件」页购买，", "<muted>也会出现在武器箱和保险箱里")));
        }
    }

    private static ItemStack withNote(ItemStack stack, List<String> note) {
        ItemStack copy = stack.clone();
        io.papermc.paper.datacomponent.item.ItemLore lore = copy.getData(io.papermc.paper.datacomponent.DataComponentTypes.LORE);
        List<net.kyori.adventure.text.Component> lines = new ArrayList<>(lore == null ? List.of() : lore.lines());
        for (String line : note) {
            lines.add(Text.mm(line));
        }
        copy.setData(io.papermc.paper.datacomponent.DataComponentTypes.LORE, io.papermc.paper.datacomponent.item.ItemLore.lore(lines));
        return copy;
    }

    private void install(int partSlot) {
        PlayerInventory inv = viewer.getInventory();
        ItemStack gun = inv.getItem(selected);
        ItemStack part = inv.getItem(partSlot);
        ItemDef gunDef = plugin.items().defOf(gun);
        ItemDef partDef = plugin.items().defOf(part);
        if (gunDef == null || !gunDef.isGun() || partDef == null || (partDef.optic() == null && partDef.attach() == null)) {
            redraw();
            return;
        }
        String why = misfit(gunDef.gun(), partDef);
        if (why != null) {
            Notify.error(viewer, "" + Text.escape(partDef.name()) + " " + why);
            return;
        }
        if (kind(gun) != kind(part)) {
            Notify.error(viewer, "" + mixNote(gun, part));
            return;
        }
        plugin.weapons().reset(viewer);
        String slot = slotOf(partDef);
        String old = fittedId(gun, slot);
        part.setAmount(part.getAmount() - 1);
        inv.setItem(partSlot, part.getAmount() > 0 ? part : null);
        inv.setItem(selected, refit(gun, slot, partDef.id()));
        giveBack(old, gun);
        viewer.playSound(viewer.getLocation(), "craftov:gun.reload_done", 0.8f, 1.2f);
        Notify.ok(viewer, "已安装 " + partDef.coloredName());
        plugin.tutorial().done(viewer, net.infnetwork.snowball.craftov.lobby.TutorialService.Step.GUNSMITH);
        redraw();
    }

    private void remove(String slot) {
        PlayerInventory inv = viewer.getInventory();
        ItemStack gun = inv.getItem(selected);
        String old = fittedId(gun, slot);
        if (old == null) {
            return;
        }
        plugin.weapons().reset(viewer);
        inv.setItem(selected, refit(gun, slot, null));
        giveBack(old, gun);
        viewer.playSound(viewer.getLocation(), "craftov:gun.mode", 0.8f, 1f);
        redraw();
    }

    /** The gun with the part changed; rounds that no longer fit the magazine go back to the inventory. */
    private ItemStack refit(ItemStack gun, String slot, @Nullable String id) {
        ItemStack out = SIGHT.equals(slot) ? plugin.items().withOptic(gun, id) : plugin.items().withAttachment(gun, slot, id);
        ItemDef def = plugin.items().defOf(out);
        Map<String, net.infnetwork.snowball.craftov.weapon.AttachSpec> parts =
                Attachments.resolve(def.gun(), ItemFactory.attachments(out), plugin.registry());
        int capacity = Attachments.effective(def.gun(), parts).magazine();
        int ammo = ItemFactory.ammo(out);
        String type = ItemFactory.ammoType(out);
        if (ammo > capacity) {
            plugin.items().setAmmo(out, capacity, type);
            ItemStack spare = type == null || ItemFactory.isRange(out) ? null : plugin.items().create(type, ammo - capacity);
            if (spare != null) {
                plugin.carry().give(viewer, ItemFactory.isTest(out) ? plugin.items().markTest(spare) : spare, true);
            }
        }
        return out;
    }

    /** A part taken off a gun is of the gun's kind: kinds never mix, so that is what was fitted. */
    private void giveBack(@Nullable String partId, ItemStack gun) {
        ItemDef def = plugin.registry().get(partId);
        if (def == null) {
            return;
        }
        ItemStack stack = plugin.items().create(def, 1);
        if (ItemFactory.isRange(gun)) {
            plugin.items().markRange(stack);
        } else if (ItemFactory.isTest(gun)) {
            plugin.items().markTest(stack);
        }
        plugin.carry().give(viewer, stack, true);
    }

    /**
     * Range loaners, proving-ground armory items and the player's own items only take parts of their own
     * kind. Otherwise a part fitted to a loaner would be lost with it, and an armory part fitted to an own
     * gun would leave the raid inside it.
     */
    private static int kind(@Nullable ItemStack stack) {
        return ItemFactory.isRange(stack) ? 1 : ItemFactory.isTest(stack) ? 2 : 0;
    }

    private static String mixNote(ItemStack gun, ItemStack part) {
        if (ItemFactory.isRange(gun) || ItemFactory.isRange(part)) {
            return ItemFactory.isRange(gun) ? "靶场武器只能装靶场的配件" : "靶场配件只能装在靶场武器上";
        }
        return ItemFactory.isTest(gun) ? "试验场武器只能装试验场的配件" : "试验场配件只能装在试验场武器上";
    }
}
