package net.infnetwork.snowball.craftov.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.profile.MailService;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The stash mailbox: system letters, newest first, each opened and paid out with a click, and the
 * item mailbox (expired listings, insurance claims, overflow), taken into the stash all at once.
 */
@SuppressWarnings("UnstableApiUsage")
final class MailMenu extends Menu {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final CraftovPlugin plugin;
    private int page;

    MailMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 6, Text.mm("<#F2E6C8>邮箱"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        Profile p = plugin.profiles().get(viewer);
        List<Profile.Mail> letters = new ArrayList<>(p.mail);
        java.util.Collections.reverse(letters);
        int pages = Math.max(1, (letters.size() + 44) / 45);
        page = Math.min(page, pages - 1);
        for (int slot = 0; slot < 45; slot++) {
            int index = page * 45 + slot;
            if (index >= letters.size()) {
                break;
            }
            Profile.Mail mail = letters.get(index);
            button(slot, icon(mail), e -> click(mail, e));
        }
        if (letters.isEmpty()) {
            set(22, UiItems.button("craftov:ui/icon_mail", "<#9AA0A6>没有系统邮件", List.of("<#8A8F98>补偿、奖励等系统邮件会出现在这里")));
        }
        fillRow(5, UiItems.filler());
        button(45, UiItems.button("craftov:ui/arrow_left", "<white>上一页", List.of("<#8A8F98>" + (page + 1) + "/" + pages)), e -> {
            page = Math.max(0, page - 1);
            redraw();
        });
        button(53, UiItems.button("craftov:ui/arrow_right", "<white>下一页", List.of("<#8A8F98>" + (page + 1) + "/" + pages)), e -> {
            page = Math.min(pages - 1, page + 1);
            redraw();
        });
        button(46, UiItems.button("craftov:ui/icon_stash", "<white>返回仓库", List.of()), e -> plugin.menus().openStash(viewer));
        int waiting = MailService.unclaimed(p);
        button(48, UiItems.button("craftov:ui/icon_fill", "<white>全部领取 <#FFC23D>" + waiting,
                List.of("<#9AA0A6>依次领取所有未读邮件的附件")), e -> {
            claimAll();
            redraw();
        });
        button(49, UiItems.button("craftov:ui/icon_mail", "<white>物品邮箱 <#FFC23D>" + p.mailbox.size() + " 件",
                List.of("<#9AA0A6>交易行退回、保险理赔、藏身处产物", "<#9AA0A6>以及大厅里身上放不下的物品", "<#9AA0A6>点击全部领取到仓库")), e -> {
            claimItems(p);
            redraw();
        });
        button(50, UiItems.button("craftov:ui/icon_cancel", "<white>删除已读邮件", List.of("<#9AA0A6>未领取附件的邮件不会删除")), e -> {
            int removed = 0;
            for (Profile.Mail mail : new ArrayList<>(plugin.profiles().get(viewer).mail)) {
                if (mail.claimed && plugin.mail().delete(viewer, mail.id)) {
                    removed++;
                }
            }
            viewer.sendActionBar(Text.mm(removed > 0 ? "<#9AA0A6>已删除 " + removed + " 封邮件" : "<#9AA0A6>没有已读邮件"));
            redraw();
        });
    }

    private ItemStack icon(Profile.Mail mail) {
        List<String> lore = new ArrayList<>();
        lore.add("<#6B7079>" + WHEN.format(Instant.ofEpochMilli(mail.time)));
        lore.add("");
        for (String line : mail.body.split("\n")) {
            lore.add("<#C8CCD2>" + Text.escape(line));
        }
        if (mail.hasAttachments()) {
            lore.add("");
            lore.add((mail.claimed ? "<#6B7079>" : "<#F2E6C8>") + "附件" + (mail.claimed ? "（已领取）" : "") + "：");
            if (mail.money > 0) {
                lore.add("<#F2C94C>  ◈" + Text.money(mail.money));
            }
            for (ItemRecord record : mail.items) {
                ItemDef def = plugin.registry().get(record.id);
                lore.add("  " + (def == null ? "<#9AA0A6>" + Text.escape(record.id) : def.coloredName())
                        + (record.amount > 1 ? " <#9AA0A6>×" + record.amount : ""));
            }
        }
        lore.add("");
        if (!mail.claimed) {
            lore.add(mail.hasAttachments() ? "<#5BD16B>点击领取附件" : "<#5BD16B>点击标为已读");
        } else {
            lore.add("<#6B7079>已读 · Shift+点击删除");
        }
        ItemStack stack = UiItems.button("craftov:ui/icon_mail", (mail.claimed ? "<#9AA0A6>" : "<#F2C94C>") + Text.escape(mail.title), lore);
        if (!mail.claimed) {
            stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    private void click(Profile.Mail mail, InventoryClickEvent event) {
        if (mail.claimed) {
            if (event.isShiftClick() && plugin.mail().delete(viewer, mail.id)) {
                viewer.sendActionBar(Text.mm("<#9AA0A6>邮件已删除"));
            }
            redraw();
            return;
        }
        String why = plugin.mail().claim(viewer, mail.id);
        if (why != null) {
            viewer.sendActionBar(Text.mm("<#FF8A80>" + why));
        } else {
            claimed(mail);
        }
        redraw();
    }

    private void claimAll() {
        int done = 0;
        for (Profile.Mail mail : new ArrayList<>(plugin.profiles().get(viewer).mail)) {
            if (mail.claimed) {
                continue;
            }
            String why = plugin.mail().claim(viewer, mail.id);
            if (why != null) {
                viewer.sendActionBar(Text.mm("<#FF8A80>" + why));
                break;
            }
            claimed(mail);
            done++;
        }
        if (done == 0) {
            viewer.sendActionBar(Text.mm("<#9AA0A6>没有未读邮件"));
        }
    }

    private void claimed(Profile.Mail mail) {
        if (!mail.hasAttachments()) {
            return;
        }
        StringBuilder got = new StringBuilder();
        if (mail.money > 0) {
            got.append("◈").append(Text.money(mail.money));
        }
        if (!mail.items.isEmpty()) {
            got.append(got.isEmpty() ? "" : "、").append(mail.items.size()).append(" 件物品（已放入仓库）");
        }
        Text.send(viewer, "<#5BD16B>已领取「" + Text.escape(mail.title) + "」的附件：<#F2C94C>" + got);
        viewer.playSound(viewer.getLocation(), "craftov:ui.sell", 0.8f, 1.1f);
    }

    /** The item mailbox, as the stash's mailbox button used to claim it: into the stash while it fits. */
    private void claimItems(Profile p) {
        int claimed = 0;
        while (!p.mailbox.isEmpty()) {
            ItemRecord record = p.mailbox.get(0);
            if (p.stashUsed() + record.footprint() > p.stashCapacity) {
                viewer.sendActionBar(Text.mm("<#FF8A80>仓库空间不足"));
                break;
            }
            p.mailbox.remove(0);
            StashMenu.deposit(plugin, p, record);
            claimed++;
        }
        if (claimed > 0) {
            viewer.sendActionBar(Text.mm("<#5BD16B>已领取 " + claimed + " 件物品"));
        }
        plugin.profiles().markDirty(viewer.getUniqueId());
    }
}
