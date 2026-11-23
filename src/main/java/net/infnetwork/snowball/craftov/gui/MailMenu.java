package net.infnetwork.snowball.craftov.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.economy.InsuranceService;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.profile.MailService;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Notify;
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
        super(viewer, 6, Text.mm("<text>邮箱"));
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
            set(22, UiItems.button("craftov:ui/icon_mail", "<muted>没有系统邮件", List.of("<muted>补偿、奖励等系统邮件会出现在这里")));
        }
        fillRow(5, UiItems.filler());
        tabButton(45, UiItems.button("craftov:ui/arrow_left", "<white>上一页", List.of("<muted>" + (page + 1) + "/" + pages)), e -> {
            page = Math.max(0, page - 1);
            redraw();
        });
        tabButton(53, UiItems.button("craftov:ui/arrow_right", "<white>下一页", List.of("<muted>" + (page + 1) + "/" + pages)), e -> {
            page = Math.min(pages - 1, page + 1);
            redraw();
        });
        button(46, UiItems.button("craftov:ui/icon_stash", "<white>返回仓库", List.of()), e -> plugin.menus().openStash(viewer));
        int waiting = MailService.unclaimed(p);
        button(48, UiItems.button("craftov:ui/icon_fill", "<white>全部领取 <accent>" + waiting,
                List.of("<muted>依次领取所有未读邮件的附件")), e -> {
            claimAll();
            redraw();
        });
        List<String> itemsLore = new ArrayList<>(List.of("<muted>交易行退回、保险理赔、藏身处产物", "<muted>以及大厅里身上放不下的物品",
                "<muted>点击全部领取到仓库"));
        long nextClaim = InsuranceService.nextClaim(p);
        if (nextClaim > 0) {
            long minutes = Math.max(1, (nextClaim - System.currentTimeMillis() + 59_999) / 60_000);
            itemsLore.add("<accent>理赔途中 " + p.claims.size() + " 件，最早约 " + minutes + " 分钟后到账");
        }
        button(49, UiItems.button("craftov:ui/icon_mail", "<white>物品邮箱 <accent>" + p.mailbox.size() + " 件", itemsLore), e -> {
            claimItems(p);
            redraw();
        });
        button(50, UiItems.button("craftov:ui/icon_cancel", "<white>删除已读邮件", List.of("<muted>未领取附件的邮件不会删除")), e -> {
            int removed = 0;
            for (Profile.Mail mail : new ArrayList<>(plugin.profiles().get(viewer).mail)) {
                if (mail.claimed && plugin.mail().delete(viewer, mail.id)) {
                    removed++;
                }
            }
            if (removed > 0) {
                Notify.ok(viewer, "已删除 " + removed + " 封邮件");
            } else {
                Notify.info(viewer, "<muted>没有已读邮件");
            }
            redraw();
        });
    }

    private ItemStack icon(Profile.Mail mail) {
        List<String> lore = new ArrayList<>();
        lore.add("<dim>" + WHEN.format(Instant.ofEpochMilli(mail.time)));
        lore.add("");
        for (String line : mail.body.split("\n")) {
            lore.add("<text>" + Text.escape(line));
        }
        if (mail.hasAttachments()) {
            lore.add("");
            lore.add((mail.claimed ? "<dim>" : "<text>") + "附件" + (mail.claimed ? "（已领取）" : "") + "：");
            if (mail.money > 0) {
                lore.add("<accent>  ◈" + Text.money(mail.money));
            }
            for (ItemRecord record : mail.items) {
                ItemDef def = plugin.registry().get(record.id);
                lore.add("  " + (def == null ? "<muted>" + Text.escape(record.id) : def.coloredName())
                        + (record.amount > 1 ? " <muted>×" + record.amount : ""));
            }
        }
        lore.add("");
        if (!mail.claimed) {
            lore.add(mail.hasAttachments() ? "<ok>点击领取附件" : "<ok>点击标为已读");
        } else {
            lore.add("<dim>已读 · Shift+点击删除");
        }
        ItemStack stack = UiItems.button("craftov:ui/icon_mail", (mail.claimed ? "<muted>" : "<accent>") + Text.escape(mail.title), lore);
        if (!mail.claimed) {
            stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    private void click(Profile.Mail mail, InventoryClickEvent event) {
        if (mail.claimed) {
            if (event.isShiftClick() && plugin.mail().delete(viewer, mail.id)) {
                Notify.ok(viewer, "邮件已删除");
            }
            redraw();
            return;
        }
        String why = plugin.mail().claim(viewer, mail.id);
        if (why != null) {
            Notify.error(viewer, why);
        } else if (mail.hasAttachments()) {
            Notify.ok(viewer, "已领取「" + Text.escape(mail.title) + "」的附件：<accent>" + attachments(mail.money, mail.items.size()));
            viewer.playSound(viewer.getLocation(), "craftov:ui.sell", 0.8f, 1.1f);
        } else {
            Notify.ok(viewer, "已读");
        }
        redraw();
    }

    private void claimAll() {
        int done = 0;
        long money = 0;
        int items = 0;
        String failed = null;
        for (Profile.Mail mail : new ArrayList<>(plugin.profiles().get(viewer).mail)) {
            if (mail.claimed) {
                continue;
            }
            failed = plugin.mail().claim(viewer, mail.id);
            if (failed != null) {
                break;
            }
            done++;
            money += mail.money;
            items += mail.items.size();
        }
        // One result for the whole button, so the reason a letter stopped it is not lost among the rest.
        if (failed != null) {
            Notify.error(viewer, (done > 0 ? "领取了 " + done + " 封后停下：" : "") + failed);
        } else if (done == 0) {
            Notify.info(viewer, "<muted>没有未读邮件");
        } else {
            Notify.ok(viewer, "已领取 " + done + " 封邮件" + (money > 0 || items > 0 ? "：<accent>" + attachments(money, items) : ""));
        }
        if (money > 0 || items > 0) {
            viewer.playSound(viewer.getLocation(), "craftov:ui.sell", 0.8f, 1.1f);
        }
    }

    private static String attachments(long money, int items) {
        StringBuilder got = new StringBuilder();
        if (money > 0) {
            got.append("◈").append(Text.money(money));
        }
        if (items > 0) {
            got.append(got.isEmpty() ? "" : "、").append(items).append(" 件物品（已放入仓库）");
        }
        return got.toString();
    }

    /** The item mailbox, as the stash's mailbox button used to claim it: into the stash while it fits. */
    private void claimItems(Profile p) {
        int claimed = 0;
        while (!p.mailbox.isEmpty()) {
            ItemRecord record = p.mailbox.get(0);
            if (p.stashUsed() + record.footprint() > p.stashCapacity) {
                Notify.error(viewer, "仓库空间不足");
                break;
            }
            p.mailbox.remove(0);
            StashMenu.deposit(plugin, p, record);
            claimed++;
        }
        if (claimed > 0) {
            Notify.ok(viewer, "已领取 " + claimed + " 件物品");
        }
        plugin.profiles().markDirty(viewer.getUniqueId());
    }
}
