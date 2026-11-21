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
 * reward is paid out when the client extracts (a consolation share if the client dies). A player
 * escorts one contract at a time, leaving the client's party gives it up, and only escorts who
 * deployed in the client's squad are paid.
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

    public @Nullable String contractFor(UUID uuid) {
        Contract c = involving(uuid);
        if (c != null && c.state != State.OPEN) {
            c.state = State.IN_RAID;
            return c.id;
        }
        return null;
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
        plugin.profiles().markDirty(client.getUniqueId());
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
        Contract held = involving(escort.getUniqueId());
        if (held != null) {
            Text.send(escort, held == c ? "<#FF8A80>你已经接取了这份委托。"
                    : "<#FF8A80>你已经在 " + Text.escape(held.clientName) + " 的护航委托中，离开其小队后才能接取别的委托。");
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
        plugin.profiles().edit(c.client, p -> p.money += c.reward);
        Text.send(client, "<#9AA0A6>委托已取消，赏金已退回。");
    }

    /**
     * A player left (or was kicked from, or dropped out of) a party. An escort who leaves the
     * client's squad gives up the contract, and a client who leaves it takes the contract away
     * from everyone who stayed; a contract with nobody left on it opens up again in the lobby.
     */
    public void onPartyLeave(UUID uuid) {
        for (Contract c : contracts.values()) {
            boolean changed;
            if (c.client.equals(uuid)) {
                changed = !c.escorts.isEmpty();
                for (UUID escort : c.escorts) {
                    Player p = Bukkit.getPlayer(escort);
                    if (p != null) {
                        Text.send(p, "<#FFC23D>委托人 " + Text.escape(c.clientName) + " 离开了小队，你的护航委托已取消。");
                    }
                }
                c.escorts.clear();
            } else {
                changed = c.escorts.remove(uuid);
                Player p = changed ? Bukkit.getPlayer(uuid) : null;
                if (p != null) {
                    Text.send(p, "<#FFC23D>你离开了 " + Text.escape(c.clientName) + " 的小队，护航委托已放弃。");
                }
            }
            if (changed && c.escorts.isEmpty() && c.state == State.ACCEPTED) {
                c.state = State.OPEN;
            }
        }
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
        for (UUID escort : paid) {
            plugin.profiles().edit(escort, p -> {
                p.money += each;
                p.addStat("escort_income", each);
            });
            Player p = Bukkit.getPlayer(escort);
            if (p != null) {
                Text.send(p, (extracted ? "<#5BD16B>护航成功！" : "<#FFC23D>老板阵亡，护航失败。") + "<#9AA0A6>获得 ◈" + Text.money(each));
            }
        }
        long refund = c.reward - each * paid.size();
        if (refund > 0) {
            plugin.profiles().edit(c.client, p -> p.money += refund);
        }
    }

    public void shutdown() {
        for (Contract c : contracts.values()) {
            plugin.profiles().edit(c.client, p -> p.money += c.reward);
        }
        contracts.clear();
    }
}
