package net.infnetwork.snowball.craftov.escort;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.match.Party;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Escort contracts (护航): a client escrows a reward, escorts join the client's squad, and the
 * reward is paid out when the client extracts (a consolation share if the client dies). A player
 * escorts one contract at a time, leaving the client's party before deploying gives it up, and
 * only escorts who deployed in the client's squad are paid.
 *
 * <p>Contracts live in memory, but the escrowed reward is recorded on the client's profile
 * ({@link Profile#escortEscrow}) together with the deduction. Stopping the server refunds every
 * open contract; a reward whose contract was lost in a crash goes back to the client when they
 * next join ({@link #reclaim}).
 */
public final class EscortService {

    public enum State { OPEN, ACCEPTED, IN_RAID }

    public static final class Contract {
        public final String id;
        public final UUID client;
        public final String clientName;
        public final long reward;
        public final String note;
        public final List<UUID> escorts = new ArrayList<>();
        public State state = State.OPEN;
        public final long created = System.currentTimeMillis();

        Contract(String id, UUID client, String clientName, long reward, String note) {
            this.id = id;
            this.client = client;
            this.clientName = clientName;
            this.reward = reward;
            this.note = note;
        }
    }

    private final CraftovPlugin plugin;
    private final Map<String, Contract> contracts = new LinkedHashMap<>();

    public EscortService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public Collection<Contract> all() {
        return contracts.values();
    }

    public @Nullable Contract byClient(UUID client) {
        for (Contract c : contracts.values()) {
            if (c.client.equals(client)) {
                return c;
            }
        }
        return null;
    }

    public @Nullable Contract involving(UUID uuid) {
        for (Contract c : contracts.values()) {
            if (c.client.equals(uuid) || c.escorts.contains(uuid)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Called on deploy: the contract this player's raid counts for, if it has escorts. Only the
     * client heading out locks it (no cancelling, no new contract): an escort deploying on their
     * own must not leave the client stuck until the client has played a raid themselves.
     */
    public @Nullable String contractFor(UUID uuid) {
        Contract c = involving(uuid);
        if (c == null || c.state == State.OPEN) {
            return null;
        }
        if (c.client.equals(uuid)) {
            c.state = State.IN_RAID;
        }
        return c.id;
    }

    /**
     * The client's raid was voided without an outcome (they ended up outside the raid world): the
     * contract stands as before the deploy, so it can be played again or cancelled.
     */
    public void voided(RaidPlayer rp) {
        Contract c = rp.escortContract == null ? null : contracts.get(rp.escortContract);
        if (c != null && c.client.equals(rp.uuid) && c.state == State.IN_RAID) {
            c.state = c.escorts.isEmpty() ? State.OPEN : State.ACCEPTED;
        }
    }

    /**
     * The server stopped with the client still in the raid, which voids it: the contract is
     * called off like a cancel, the whole reward goes back to the client and no escort is paid.
     * (After a crash the contract is gone and {@link #reclaim} returns the reward instead.)
     */
    public void callOff(RaidPlayer rp) {
        Contract c = rp.escortContract == null ? null : contracts.get(rp.escortContract);
        if (c == null || !c.client.equals(rp.uuid)) {
            return;
        }
        contracts.remove(c.id);
        refund(c);
    }

    public void create(Player client, long reward, String note) {
        if (involving(client.getUniqueId()) != null) {
            Notify.error(client, "你已经有一份进行中的护航委托。");
            return;
        }
        Profile profile = plugin.profiles().get(client);
        if (profile.money < reward) {
            Notify.error(client, "块币不足以托管 ◈" + Text.money(reward));
            return;
        }
        profile.money -= reward;
        profile.escortEscrow += reward;
        plugin.profiles().saveNow(client.getUniqueId());
        String id = Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFL);
        contracts.put(id, new Contract(id, client.getUniqueId(), client.getName(), reward, note));
        Text.send(client, "<ok>护航委托已发布，赏金 ◈" + Text.money(reward) + " 已托管。");
        net.kyori.adventure.text.Component posted = Text.mm("<accent>" + Text.escape(client.getName()) + " 发布了护航委托，赏金 ◈"
                + Text.money(reward) + " <muted>· 前往护航大厅接取");
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!plugin.raids().isInRaid(online)) {
                online.sendMessage(posted);
            }
        }
    }

    public void accept(Player escort, String id) {
        Contract c = contracts.get(id);
        if (c == null || c.state == State.IN_RAID) {
            Notify.error(escort, "该委托已不可接取。");
            return;
        }
        if (c.client.equals(escort.getUniqueId())) {
            Notify.error(escort, "不能接取自己的委托。");
            return;
        }
        Contract held = involving(escort.getUniqueId());
        if (held != null) {
            Text.send(escort, held == c ? "<danger>你已经接取了这份委托。"
                    : "<danger>你已经在 " + Text.escape(held.clientName) + " 的护航委托中，离开其小队后才能接取别的委托。");
            return;
        }
        if (c.escorts.size() >= 2) {
            Notify.error(escort, "该委托护航名额已满。");
            return;
        }
        Player client = Bukkit.getPlayer(c.client);
        if (client == null) {
            Notify.error(escort, "委托人不在线。");
            return;
        }
        Party party = plugin.match().ensureParty(client);
        plugin.match().join(escort, party);
        if (!party.members.contains(escort.getUniqueId())) {
            return;
        }
        c.escorts.add(escort.getUniqueId());
        c.state = State.ACCEPTED;
        Text.send(escort, "<ok>已接取 " + Text.escape(c.clientName) + " 的护航委托。保护老板安全撤离即可获得 ◈" + Text.money(c.reward));
        Text.send(client, "<ok>" + Text.escape(escort.getName()) + " 接取了你的护航委托！");
    }

    public void cancel(Player client) {
        Contract c = byClient(client.getUniqueId());
        if (c == null) {
            Text.send(client, "<muted>你没有护航委托。");
            return;
        }
        if (c.state == State.IN_RAID) {
            Notify.error(client, "行动进行中，无法取消。");
            return;
        }
        contracts.remove(c.id);
        refund(c);
        Text.send(client, "<muted>委托已取消，赏金已退回。");
    }

    /**
     * A player left (or was kicked from, or dropped out of) a party. An escort who leaves the
     * client's squad gives up the contract, and a client who leaves it takes the contract away
     * from everyone who stayed; a contract with nobody left on it opens up again in the lobby.
     * Once the squad has deployed the escorts are fixed: settle pays whoever went in with the
     * client, so a client cannot kick them before extracting to keep the reward, and an escort
     * who is already out may leave the party or log off without losing their share.
     */
    public void onPartyLeave(UUID uuid) {
        for (Contract c : contracts.values()) {
            if (c.state == State.IN_RAID) {
                continue;
            }
            if (c.client.equals(uuid)) {
                for (UUID escort : new ArrayList<>(c.escorts)) {
                    dropEscort(c, escort);
                    Player p = Bukkit.getPlayer(escort);
                    if (p != null) {
                        Text.send(p, "<accent>委托人 " + Text.escape(c.clientName) + " 离开了小队，你的护航委托已取消。");
                    }
                }
            } else if (dropEscort(c, uuid)) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    Text.send(p, "<accent>你离开了 " + Text.escape(c.clientName) + " 的小队，护航委托已放弃。");
                }
            }
        }
    }

    /**
     * Takes an escort off a contract that has not deployed: the one way an escort gives one up,
     * whether they left the party or dropped out while the map loaded. An accepted contract with
     * nobody left on it opens up again. True if they were on it.
     */
    private static boolean dropEscort(Contract c, UUID escort) {
        boolean was = c.escorts.remove(escort);
        if (was && c.escorts.isEmpty() && c.state == State.ACCEPTED) {
            c.state = State.OPEN;
        }
        return was;
    }

    /**
     * A player dropped out of a raid before it deployed (they disconnected while the map loaded, or
     * could no longer pay the entry fee; {@code reason} says which, in Chinese).
     * Without their client the escorts' run does not count: an accepted contract is cancelled and
     * the reward refunded, since no client is left in that raid to settle it (an open one has no
     * escorts and simply stays open). A withdrawn escort leaves the contract and is not paid.
     */
    public void onWithdraw(UUID uuid, String name, String reason) {
        Contract c = involving(uuid);
        if (c == null || c.state == State.IN_RAID) {
            return;
        }
        if (c.client.equals(uuid)) {
            if (c.state != State.ACCEPTED) {
                return;
            }
            contracts.remove(c.id);
            // Through the escrow, like a cancel: a plain refund would leave the reward recorded as
            // held, and reclaim() would hand it back a second time on the client's next join.
            refund(c);
            for (UUID escort : c.escorts) {
                Player p = Bukkit.getPlayer(escort);
                if (p != null) {
                    Text.send(p, "<accent>老板 " + Text.escape(name) + " " + reason + "，护航委托已取消，本局不计护航报酬。");
                }
            }
            return;
        }
        dropEscort(c, uuid);
        Player client = Bukkit.getPlayer(c.client);
        if (client != null) {
            Text.send(client, "<accent>护航 " + Text.escape(name) + " " + reason + "，已退出你的护航委托。");
        }
    }

    /** Gives a contract's whole reward back to the client and releases it from escrow. */
    private void refund(Contract c) {
        release(c, c.reward);
    }

    /** Ends the client's escrow for {@code c} and hands {@code refund} of it back. */
    private void release(Contract c, long refund) {
        plugin.profiles().edit(c.client, () -> "escort contract " + c.id + " closed: escortEscrow -" + c.reward
                + " (never below 0), money +" + refund, p -> {
            p.escortEscrow = Math.max(0, p.escortEscrow - c.reward);
            p.money += refund;
        });
        // Saved now, not with the next flush up to ten seconds later: a crash before the escrow
        // reaches the disk returns all of it on the client's next join, on top of any pay already out.
        plugin.profiles().saveNow(c.client);
    }

    /** Called when any raid participant finishes. Only the client's outcome settles a contract. */
    public void settle(Raid raid, RaidPlayer rp, boolean extracted) {
        if (rp.escortContract == null) {
            return;
        }
        Contract c = contracts.get(rp.escortContract);
        if (c == null || !c.client.equals(rp.uuid)) {
            return;
        }
        contracts.remove(c.id);
        double share = extracted ? 1.0 : plugin.getConfig().getDouble("escort.failure-share", 0.3);
        long pool = Math.round(c.reward * share);
        // Only escorts who went into this raid on the contract, in the client's squad, earn a
        // share; the rest of the reward goes back to the client.
        List<UUID> paid = new ArrayList<>();
        for (UUID escort : c.escorts) {
            RaidPlayer erp = raid.player(escort);
            if (erp != null && erp.squad == rp.squad && c.id.equals(erp.escortContract) && !paid.contains(escort)) {
                paid.add(escort);
            }
        }
        long each = paid.isEmpty() ? 0 : pool / paid.size();
        // The escrow is closed and on its way to the disk before anyone is paid: the writes land
        // in this order, so a crash between them can cost an escort the pay but never pays twice.
        release(c, c.reward - each * paid.size());
        for (UUID escort : paid) {
            plugin.profiles().edit(escort, () -> "escort pay for " + c.clientName + "'s contract " + c.id + ": money +" + each
                    + ", stats.escort_income +" + each, p -> {
                p.money += each;
                p.addStat("escort_income", each);
            });
            plugin.profiles().saveNow(escort);
            Player p = Bukkit.getPlayer(escort);
            if (p != null) {
                Text.send(p, (extracted ? "<ok>护航成功！" : "<accent>老板阵亡，护航失败。") + "<muted>获得 ◈" + Text.money(each));
            }
        }
    }

    /**
     * Start-up report for the admin: contracts live in memory only, so a reward still held in
     * escrow belongs to a contract lost when the server went down without a clean shutdown (crash,
     * kill, power cut). Nothing is paid here; {@link #reclaim} hands it back on the client's next
     * join, which is also the only way it is ever returned.
     */
    public void start() {
        long total = 0;
        int count = 0;
        for (var summary : plugin.profiles().summaries(s -> s.escortEscrow() > 0)) {
            if (!plugin.profiles().canEdit(summary.uuid())) {
                // Its owner cannot log in, and changes waiting in its journal may close this very
                // contract (the client went missing while the file was broken).
                plugin.getLogger().severe("Escort escrow of " + summary.escortEscrow() + " held for " + summary.name() + " ("
                        + summary.uuid() + ") is NOT returned while that profile is locked (unreadable, or changes waiting in "
                        + summary.uuid() + ".json.unsaved). Repair it, apply and delete the journal; whatever escrow is left"
                        + " then goes back when they next log in.");
                continue;
            }
            total += summary.escortEscrow();
            count++;
        }
        if (count > 0) {
            plugin.getLogger().warning(count + " escort contracts lost when the server stopped hold " + total
                    + " money in escrow; each client gets theirs back on their next login.");
        }
    }

    /**
     * Join: a reward still held in escrow without a live contract belongs to a contract the server
     * lost when it went down without shutting down cleanly. It goes back to the client. A locked
     * account never gets here (its login is refused), and the stand-in of one that broke after
     * the login check is left alone: it is never saved, and its owner is sent away at once.
     */
    public void reclaim(Player player, Profile profile) {
        if (profile.escortEscrow <= 0 || byClient(player.getUniqueId()) != null
                || !plugin.profiles().canEdit(player.getUniqueId())) {
            return;
        }
        long held = profile.escortEscrow;
        profile.escortEscrow = 0;
        profile.money += held;
        plugin.profiles().markDirty(player.getUniqueId());
        Text.send(player, "<accent>你的护航委托因服务器中断已撤销，托管的赏金 ◈" + Text.money(held) + " 已退回。");
    }

    public void shutdown() {
        for (Contract c : contracts.values()) {
            refund(c);
        }
        contracts.clear();
    }
}
