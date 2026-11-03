package net.infnetwork.snowball.craftov.match;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.map.MapDef;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidMode;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.raid.Tier;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Parties, matchmaking queues and per-raid squad teams. */
public final class MatchService {

    private static final NamedTextColor[] SQUAD_COLORS = {NamedTextColor.AQUA, NamedTextColor.GOLD, NamedTextColor.LIGHT_PURPLE,
            NamedTextColor.GREEN, NamedTextColor.YELLOW, NamedTextColor.RED, NamedTextColor.BLUE, NamedTextColor.WHITE};

    private final CraftovPlugin plugin;
    private final Map<UUID, Party> parties = new HashMap<>();
    private final Map<UUID, UUID> invites = new HashMap<>();
    private final Map<UUID, Long> inviteExpiry = new HashMap<>();
    private final List<QueueEntry> queue = new ArrayList<>();

    public MatchService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    // ------------------------------------------------------------------ parties

    public @Nullable Party partyOf(UUID uuid) {
        return parties.get(uuid);
    }

    public List<UUID> group(Player player) {
        Party party = partyOf(player.getUniqueId());
        return party == null ? List.of(player.getUniqueId()) : List.copyOf(party.members);
    }

    /** The player's party, creating a one-person party led by them if needed. */
    public Party ensureParty(Player leader) {
        Party party = parties.get(leader.getUniqueId());
        if (party == null) {
            party = new Party(leader.getUniqueId());
            parties.put(leader.getUniqueId(), party);
        }
        return party;
    }

    public void invite(Player leader, Player target) {
        if (leader == target) {
            Text.send(leader, "<#FF8A80>不能邀请自己。");
            return;
        }
        Party party = parties.computeIfAbsent(leader.getUniqueId(), Party::new);
        if (!party.leader.equals(leader.getUniqueId())) {
            Text.send(leader, "<#FF8A80>只有队长可以邀请。");
            return;
        }
        if (party.size() >= 3) {
            Text.send(leader, "<#FF8A80>小队最多 3 人。");
            return;
        }
        if (partyOf(target.getUniqueId()) != null) {
            Text.send(leader, "<#FF8A80>对方已经在小队中。");
            return;
        }
        invites.put(target.getUniqueId(), leader.getUniqueId());
        inviteExpiry.put(target.getUniqueId(), System.currentTimeMillis() + 60_000);
        Text.send(leader, "<#9AA0A6>已邀请 <white>" + Text.escape(target.getName()) + "</white> 加入小队。");
        target.sendMessage(Text.mm(Text.PREFIX + "<white>" + Text.escape(leader.getName()) + "</white> <#9AA0A6>邀请你加入小队 ")
                .append(Text.mm("<#5BD16B><b>[接受]</b>").clickEvent(ClickEvent.runCommand("/cv party accept"))));
        target.playSound(target.getLocation(), "craftov:ui.invite", 1f, 1f);
    }

    public void accept(Player player) {
        UUID leaderId = invites.remove(player.getUniqueId());
        Long expiry = inviteExpiry.remove(player.getUniqueId());
        if (leaderId == null || expiry == null || expiry < System.currentTimeMillis()) {
            Text.send(player, "<#FF8A80>没有有效的邀请。");
            return;
        }
        Party party = parties.get(leaderId);
        if (party == null) {
            Player leader = Bukkit.getPlayer(leaderId);
            if (leader == null) {
                Text.send(player, "<#FF8A80>邀请已失效。");
                return;
            }
            party = new Party(leaderId);
            parties.put(leaderId, party);
        }
        join(player, party);
    }

    public void join(Player player, Party party) {
        if (party.size() >= 3) {
            Text.send(player, "<#FF8A80>小队已满。");
            return;
        }
        leave(player, false);
        cancelQueue(party.leader, "有新队员加入");
        party.members.add(player.getUniqueId());
        parties.put(player.getUniqueId(), party);
        broadcast(party, "<white>" + Text.escape(player.getName()) + "</white> <#5BD16B>加入了小队 (" + party.size() + "/3)");
    }

    public void leave(Player player, boolean announce) {
        Party party = parties.remove(player.getUniqueId());
        if (party == null) {
            return;
        }
        cancelQueue(party.leader, "队员离队");
        party.members.remove(player.getUniqueId());
        if (announce) {
            Text.send(player, "<#9AA0A6>你已离开小队。");
            broadcast(party, "<white>" + Text.escape(player.getName()) + "</white> <#9AA0A6>离开了小队");
        }
        if (party.members.isEmpty()) {
            return;
        }
        if (party.leader.equals(player.getUniqueId())) {
            party.leader = party.members.get(0);
            broadcast(party, "<#9AA0A6>新的队长：<white>" + nameOf(party.leader));
        }
        if (party.members.size() == 1) {
            parties.remove(party.members.get(0));
            Player last = Bukkit.getPlayer(party.members.get(0));
            if (last != null) {
                Text.send(last, "<#9AA0A6>小队已解散。");
            }
        }
    }

    public void kick(Player leader, String name) {
        Party party = partyOf(leader.getUniqueId());
        if (party == null || !party.leader.equals(leader.getUniqueId())) {
            Text.send(leader, "<#FF8A80>你不是队长。");
            return;
        }
        Player target = Bukkit.getPlayerExact(name);
        if (target == null || !party.members.contains(target.getUniqueId())) {
            Text.send(leader, "<#FF8A80>小队中没有这名玩家。");
            return;
        }
        leave(target, true);
    }

    private void broadcast(Party party, String message) {
        for (UUID uuid : party.members) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                Text.send(p, message);
            }
        }
    }

    private static String nameOf(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        return p == null ? "?" : p.getName();
    }

    // ------------------------------------------------------------------ queue

    public @Nullable QueueEntry queued(UUID uuid) {
        for (QueueEntry entry : queue) {
            if (entry.members().contains(uuid)) {
                return entry;
            }
        }
        return null;
    }

    public int queuedCount(String key) {
        int count = 0;
        for (QueueEntry entry : queue) {
            if (entry.key().equals(key)) {
                count += entry.members().size();
            }
        }
        return count;
    }

    /** Validates and enqueues the player's party. */
    public boolean enqueue(Player player, String mapId, String tierId, RaidMode mode, boolean ranked, boolean fill) {
        Party party = partyOf(player.getUniqueId());
        if (party != null && !party.leader.equals(player.getUniqueId())) {
            Text.send(player, "<#FF8A80>只有队长可以开始匹配。");
            return false;
        }
        List<UUID> members = group(player);
        if (mode == RaidMode.SOLO && members.size() > 1) {
            Text.send(player, "<#FF8A80>单人模式需要先离开小队。");
            return false;
        }
        MapDef map = plugin.maps().get(mapId);
        Tier tier = plugin.tier(tierId);
        if (map == null || tier == null) {
            Text.send(player, "<#FF8A80>地图或难度不存在。");
            return false;
        }
        int rating = 0;
        for (UUID uuid : members) {
            Player member = Bukkit.getPlayer(uuid);
            if (member == null || !plugin.lobby().isLobby(member.getWorld()) || plugin.raids().raidOf(member) != null) {
                Text.send(player, "<#FF8A80>小队成员 " + (member == null ? "" : Text.escape(member.getName())) + " 不在大厅。");
                return false;
            }
            plugin.lobby().captureLoadout(member);
            long value = plugin.carry().carriedValue(member);
            if (value < tier.minValue()) {
                Text.send(player, "<#FF8A80>" + Text.escape(member.getName()) + " 的战备值 ◈" + Text.money(value)
                        + " 不足 " + tier.name() + " 要求的 ◈" + Text.money(tier.minValue()));
                return false;
            }
            Profile profile = plugin.profiles().get(member);
            if (profile.money < tier.entryFee()) {
                Text.send(player, "<#FF8A80>" + Text.escape(member.getName()) + " 的块币不足以支付入场费。");
                return false;
            }
            if (ranked && profile.level < plugin.getConfig().getInt("rank.min-level", 3)) {
                Text.send(player, "<#FF8A80>排位赛需要账号等级 " + plugin.getConfig().getInt("rank.min-level", 3) + "。");
                return false;
            }
            rating += profile.rankPoints;
        }
        for (UUID uuid : members) {
            QueueEntry old = queued(uuid);
            if (old != null) {
                queue.remove(old);
            }
        }
        QueueEntry entry = new QueueEntry(members, mapId, tierId, mode, ranked, fill, System.currentTimeMillis(), rating / members.size());
        queue.add(entry);
        for (UUID uuid : members) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                Text.send(p, "<#F2C94C>开始匹配：<white>" + Text.escape(map.name()) + " " + tier.tag() + " <#9AA0A6>· " + mode.displayName()
                        + (ranked ? " · <#FFC23D>排位" : ""));
                p.playSound(p.getLocation(), "craftov:ui.queue", 1f, 1f);
            }
        }
        return true;
    }

    public void cancelQueue(UUID member, String reason) {
        QueueEntry entry = queued(member);
        if (entry == null) {
            return;
        }
        queue.remove(entry);
        for (UUID uuid : entry.members()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                Text.send(p, "<#9AA0A6>已取消匹配" + (reason.isEmpty() ? "" : "（" + reason + "）") + "。");
            }
        }
    }

    public void onQuit(Player player) {
        cancelQueue(player.getUniqueId(), Text.escape(player.getName()) + " 离线");
        invites.remove(player.getUniqueId());
        if (plugin.raids().raidOf(player) == null) {
            leave(player, true);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        int wait = plugin.getConfig().getInt("match.wait-seconds", 15);
        Map<String, List<QueueEntry>> byKey = new LinkedHashMap<>();
        for (QueueEntry entry : queue) {
            byKey.computeIfAbsent(entry.key(), k -> new ArrayList<>()).add(entry);
        }
        for (List<QueueEntry> entries : byKey.values()) {
            QueueEntry first = entries.get(0);
            int maxSquads = first.mode() == RaidMode.SOLO ? plugin.getConfig().getInt("match.max-solo-players", 8)
                    : plugin.getConfig().getInt("match.max-squads", 4);
            entries.sort(Comparator.comparingInt(QueueEntry::rating));
            List<List<UUID>> squads = formSquads(entries, first.mode());
            long oldest = entries.stream().mapToLong(QueueEntry::since).min().orElse(now);
            int waited = (int) ((now - oldest) / 1000);
            for (QueueEntry entry : entries) {
                for (UUID uuid : entry.members()) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) {
                        p.sendActionBar(Text.mm("<#F2C94C>匹配中 <white>" + Text.duration((now - entry.since()) / 1000)
                                + " <#8A8F98>· 已就绪 " + queuedCount(entry.key()) + " 人 · " + Math.max(0, wait - waited) + "s 后出发"));
                    }
                }
            }
            if (squads.size() >= maxSquads || waited >= wait) {
                List<List<UUID>> chosen = squads.subList(0, Math.min(maxSquads, squads.size()));
                launch(first, chosen, entries);
            }
        }
    }

    private List<List<UUID>> formSquads(List<QueueEntry> entries, RaidMode mode) {
        List<List<UUID>> squads = new ArrayList<>();
        if (mode == RaidMode.SOLO) {
            for (QueueEntry entry : entries) {
                for (UUID uuid : entry.members()) {
                    squads.add(new ArrayList<>(List.of(uuid)));
                }
            }
            return squads;
        }
        List<QueueEntry> fillable = new ArrayList<>();
        for (QueueEntry entry : entries) {
            if (entry.members().size() >= 3 || !entry.fill()) {
                squads.add(new ArrayList<>(entry.members()));
            } else {
                fillable.add(entry);
            }
        }
        fillable.sort((a, b) -> Integer.compare(b.members().size(), a.members().size()));
        List<List<UUID>> open = new ArrayList<>();
        for (QueueEntry entry : fillable) {
            List<UUID> target = null;
            for (List<UUID> squad : open) {
                if (squad.size() + entry.members().size() <= 3) {
                    target = squad;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                open.add(target);
            }
            target.addAll(entry.members());
        }
        squads.addAll(open);
        return squads;
    }

    private void launch(QueueEntry template, List<List<UUID>> squads, List<QueueEntry> entries) {
        MapDef map = plugin.maps().get(template.map());
        Tier tier = plugin.tier(template.tier());
        List<List<Player>> players = new ArrayList<>();
        java.util.Set<UUID> launched = new java.util.HashSet<>();
        for (List<UUID> squad : squads) {
            List<Player> online = new ArrayList<>();
            for (UUID uuid : squad) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && plugin.raids().raidOf(p) == null) {
                    online.add(p);
                    launched.add(uuid);
                }
            }
            if (!online.isEmpty()) {
                players.add(online);
            }
        }
        queue.removeIf(entry -> entry.members().stream().anyMatch(launched::contains));
        if (map == null || tier == null || players.isEmpty()) {
            return;
        }
        plugin.raids().start(map, tier, template.mode(), template.ranked(), players);
    }

    // ------------------------------------------------------------------ squad teams

    private Scoreboard board() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    private String teamName(Raid raid, int squad) {
        return ("cv" + raid.id + "s" + squad);
    }

    public void teamFor(Raid raid, RaidPlayer rp, Player player) {
        Scoreboard board = board();
        String name = teamName(raid, rp.squad);
        Team team = board.getTeam(name);
        if (team == null) {
            team = board.registerNewTeam(name);
            team.setAllowFriendlyFire(false);
            team.setCanSeeFriendlyInvisibles(true);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.FOR_OWN_TEAM);
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.FOR_OWN_TEAM);
            team.color(SQUAD_COLORS[(rp.squad - 1) % SQUAD_COLORS.length]);
        }
        team.addEntry(player.getName());
    }

    public void leaveTeam(Player player) {
        Team team = board().getEntryTeam(player.getName());
        if (team != null && team.getName().startsWith("cv")) {
            team.removeEntry(player.getName());
        }
    }

    public void disposeTeams(Raid raid) {
        for (Team team : new ArrayList<>(board().getTeams())) {
            if (team.getName().startsWith("cv" + raid.id + "s")) {
                team.unregister();
            }
        }
    }
}
