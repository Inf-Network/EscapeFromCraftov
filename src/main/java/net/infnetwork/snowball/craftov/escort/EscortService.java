package net.infnetwork.snowball.craftov.escort;

import net.infnetwork.snowball.craftov.CraftovPlugin;
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
 * reward is paid out when the client extracts (a consolation share if the client dies).
 *
 * <p>Contracts live in memory, but the escrowed reward is recorded on the client's profile
 * ({@link Profile#escortEscrow}) together with the deduction, so a reward whose contract was lost
 * in a crash goes back to the client on the next start.
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

    public void create(Player client, long reward, String note) {
        if (involving(client.getUniqueId()) != null) {
            Text.send(client, "<#FF8A80>你已经有一份进行中的护航委托。");
            return;
        }
        Profile profile = plugin.profiles().get(client);
        if (profile.money < reward) {
            Text.send(client, "<#FF8A80>块币不足以托管 ◈" + Text.money(reward));
            return;
        }
        profile.money -= reward;
        profile.escortEscrow += reward;
        plugin.profiles().saveNow(client.getUniqueId());
        String id = Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFL);
        contracts.put(id, new Contract(id, client.getUniqueId(), client.getName(), reward, note));
        Text.send(client, "<#5BD16B>护航委托已发布，赏金 ◈" + Text.money(reward) + " 已托管。");
        Bukkit.broadcast(Text.mm(Text.PREFIX + "<#FFC23D>" + Text.escape(client.getName()) + " 发布了护航委托，赏金 ◈"
                + Text.money(reward) + " <#9AA0A6>· 前往护航大厅接取"));
    }

    public void accept(Player escort, String id) {
        Contract c = contracts.get(id);
        if (c == null || c.state == State.IN_RAID) {
            Text.send(escort, "<#FF8A80>该委托已不可接取。");
            return;
        }
        if (c.client.equals(escort.getUniqueId())) {
            Text.send(escort, "<#FF8A80>不能接取自己的委托。");
            return;
        }
        if (c.escorts.size() >= 2) {
            Text.send(escort, "<#FF8A80>该委托护航名额已满。");
            return;
        }
        Player client = Bukkit.getPlayer(c.client);
        if (client == null) {
            Text.send(escort, "<#FF8A80>委托人不在线。");
            return;
        }
        Party party = plugin.match().ensureParty(client);
        plugin.match().join(escort, party);
        if (!party.members.contains(escort.getUniqueId())) {
            return;
        }
        c.escorts.add(escort.getUniqueId());
        c.state = State.ACCEPTED;
        Text.send(escort, "<#5BD16B>已接取 " + Text.escape(c.clientName) + " 的护航委托。保护老板安全撤离即可获得 ◈" + Text.money(c.reward));
        Text.send(client, "<#5BD16B>" + Text.escape(escort.getName()) + " 接取了你的护航委托！");
    }

    public void cancel(Player client) {
        Contract c = byClient(client.getUniqueId());
        if (c == null) {
            Text.send(client, "<#9AA0A6>你没有护航委托。");
            return;
        }
        if (c.state == State.IN_RAID) {
            Text.send(client, "<#FF8A80>行动进行中，无法取消。");
            return;
        }
        contracts.remove(c.id);
        release(c, c.reward);
        Text.send(client, "<#9AA0A6>委托已取消，赏金已退回。");
    }

    /** Ends the client's escrow for {@code c} and hands {@code refund} of it back. */
    private void release(Contract c, long refund) {
        plugin.profiles().edit(c.client, p -> {
            p.escortEscrow = Math.max(0, p.escortEscrow - c.reward);
            p.money += refund;
        });
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
        long each = c.escorts.isEmpty() ? 0 : pool / c.escorts.size();
        for (UUID escort : c.escorts) {
            plugin.profiles().edit(escort, p -> {
                p.money += each;
                p.addStat("escort_income", each);
            });
            Player p = Bukkit.getPlayer(escort);
            if (p != null) {
                Text.send(p, (extracted ? "<#5BD16B>护航成功！" : "<#FFC23D>老板阵亡，护航失败。") + "<#9AA0A6>获得 ◈" + Text.money(each));
            }
        }
        release(c, c.reward - each * c.escorts.size());
    }

    /**
     * Start-up: contracts live in memory only, so any reward still held in escrow belongs to a
     * contract lost when the server went down without a clean shutdown (crash, kill, power cut).
     */
    public void start() {
        long total = 0;
        int count = 0;
        for (var summary : plugin.profiles().summaries(s -> s.escortEscrow() > 0)) {
            total += summary.escortEscrow();
            count++;
            plugin.profiles().edit(summary.uuid(), p -> {
                p.money += p.escortEscrow;
                p.escortEscrow = 0;
            });
        }
        if (count > 0) {
            plugin.getLogger().warning("Returned " + total + " money held for " + count + " escort contracts lost when the server stopped.");
        }
    }

    public void shutdown() {
        for (Contract c : contracts.values()) {
            release(c, c.reward);
        }
        contracts.clear();
    }
}
