package net.infnetwork.snowball.craftov.profile;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * System mail (系统邮件). The server writes letters to players: a title, a few lines saying why,
 * and money or items attached (compensation, rewards). A letter is saved with the profile the
 * moment it is sent, whether its addressee is online or not, and waits there until they open the
 * mailbox at the stash and claim it: the money goes onto the balance and the items into the stash,
 * all at once or not at all. Players hear about unclaimed letters when they log in.
 *
 * <p>The item mailbox next to it ({@link Profile#mailbox}: expired listings, insurance claims,
 * what did not fit on the player) works as before.
 */
public final class MailService {

    /** Letters kept per player. Past that the oldest claimed ones go; an unclaimed one never does. */
    private static final int KEEP = 30;

    private final CraftovPlugin plugin;

    public MailService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    /** A new letter dated now. {@code body} is plain text (not MiniMessage), one line per paragraph. */
    public static Profile.Mail letter(String title, String body, long money, List<ItemRecord> items) {
        Profile.Mail mail = new Profile.Mail();
        mail.id = Long.toString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFL, 36);
        mail.time = System.currentTimeMillis();
        mail.title = title;
        mail.body = body;
        mail.money = Math.max(0, money);
        mail.items = new ArrayList<>();
        for (ItemRecord record : items) {
            ItemRecord copy = record.copy();
            copy.uninsure();
            mail.items.add(copy);
        }
        return mail;
    }

    /**
     * Files a letter on a profile. The one place a letter is added, for use inside a profile
     * mutation (ProfileService.edit) that changes more than the mail, so both land together.
     */
    public static void deliver(Profile profile, Profile.Mail mail) {
        profile.mail.add(mail);
        int extra = profile.mail.size() - KEEP;
        for (Iterator<Profile.Mail> it = profile.mail.iterator(); extra > 0 && it.hasNext(); ) {
            if (it.next().claimed) {
                it.remove();
                extra--;
            }
        }
    }

    /** Sends a letter on its own, saved at once; {@code why} goes into the journal should the profile be locked. */
    public void send(UUID to, Profile.Mail mail, String why) {
        plugin.profiles().edit(to, () -> why + ": add to mail " + ProfileService.json(mail), p -> deliver(p, mail));
        plugin.profiles().saveNow(to);
        Player player = Bukkit.getPlayer(to);
        if (player != null) {
            announce(player, mail);
        }
    }

    /** Tells an online player a letter has just arrived. */
    public void announce(Player player, Profile.Mail mail) {
        Text.send(player, "<#F2C94C>新邮件：<white>" + Text.escape(mail.title) + " <#9AA0A6>· 打开仓库 → 邮箱查看"
                + (mail.hasAttachments() ? "并领取附件" : ""));
        player.playSound(player.getLocation(), "craftov:ui.queue", SoundCategory.MASTER, 0.6f, 1.5f);
    }

    public static int unclaimed(Profile profile) {
        int count = 0;
        for (Profile.Mail mail : profile.mail) {
            if (!mail.claimed) {
                count++;
            }
        }
        return count;
    }

    public static Profile.@Nullable Mail find(Profile profile, String id) {
        for (Profile.Mail mail : profile.mail) {
            if (mail.id.equals(id)) {
                return mail;
            }
        }
        return null;
    }

    /**
     * Opens a letter and pays out what is attached: the money onto the balance, the items into the
     * stash. Nothing is paid unless all of it fits. Null on success, otherwise why not.
     */
    public @Nullable String claim(Player player, String id) {
        Profile profile = plugin.profiles().get(player);
        Profile.Mail mail = find(profile, id);
        if (mail == null) {
            return "邮件不存在";
        }
        if (mail.claimed) {
            return "已经领取过了";
        }
        int cells = 0;
        for (ItemRecord record : mail.items) {
            cells += record.footprint();
        }
        if (cells > 0 && profile.stashUsed() + cells > profile.stashCapacity) {
            return "仓库空间不足，附件需要 " + cells + " 格";
        }
        for (ItemRecord record : mail.items) {
            profile.stash.add(record.copy());
        }
        profile.money += mail.money;
        mail.claimed = true;
        // Written at once, like any payout: a crash must not hand the letter out a second time.
        plugin.profiles().saveNow(player.getUniqueId());
        return null;
    }

    /** Deletes a claimed letter; an unclaimed one stays. True if it was deleted. */
    public boolean delete(Player player, String id) {
        Profile profile = plugin.profiles().get(player);
        Profile.Mail mail = find(profile, id);
        if (mail == null || !mail.claimed) {
            return false;
        }
        profile.mail.remove(mail);
        plugin.profiles().markDirty(player.getUniqueId());
        return true;
    }

    /** On login: tells the player about letters still waiting for them. */
    public void remind(Player player) {
        int count = unclaimed(plugin.profiles().get(player));
        if (count > 0) {
            Text.send(player, "<#F2C94C>你有 " + count + " 封未读邮件，<#9AA0A6>打开仓库 → 邮箱查看（也可以输入 <white>/cv mail</white>）。");
        }
    }
}
