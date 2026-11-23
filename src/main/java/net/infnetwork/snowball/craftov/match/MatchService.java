package net.infnetwork.snowball.craftov.match;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            Text.send(leader, "<danger>不能邀请自己。");
            return;
        }
        Party party = parties.computeIfAbsent(leader.getUniqueId(), Party::new);
        if (!party.leader.equals(leader.getUniqueId())) {
            Text.send(leader, "<danger>只有队长可以邀请。");
            return;
        }
        if (party.size() >= 3) {
            Text.send(leader, "<danger>小队最多 3 人。");
            return;
        }
        if (partyOf(target.getUniqueId()) != null) {
            Text.send(leader, "<danger>对方已经在小队中。");
            return;
        }
        invites.put(target.getUniqueId(), leader.getUniqueId());
        inviteExpiry.put(target.getUniqueId(), System.currentTimeMillis() + 60_000);
        Text.send(leader, "<muted>已邀请 <white>" + Text.escape(target.getName()) + "</white> 加入小队。");
        target.sendMessage(Text.mm("<white>" + Text.escape(leader.getName()) + "</white> <muted>邀请你加入小队 ")
                .append(Text.mm("<ok><b>[接受]</b>").clickEvent(ClickEvent.runCommand("/cv party accept"))));
        target.playSound(target.getLocation(), "craftov:ui.invite", 1f, 1f);
    }

    public void accept(Player player) {
        UUID leaderId = invites.remove(player.getUniqueId());
        Long expiry = inviteExpiry.remove(player.getUniqueId());
        if (leaderId == null || expiry == null || expiry < System.currentTimeMillis()) {
            Text.send(player, "<danger>没有有效的邀请。");
            return;
        }
        Party party = parties.get(leaderId);
        if (party == null) {
            Player leader = Bukkit.getPlayer(leaderId);
            if (leader == null) {
                Text.send(player, "<danger>邀请已失效。");
                return;
            }
            party = new Party(leaderId);
            parties.put(leaderId, party);
        }
        join(player, party);
    }

    public void join(Player player, Party party) {
        if (party.size() >= 3) {
            Text.send(player, "<danger>小队已满。");
            return;
        }
        leave(player, false);
        // A solo player has no Party, so leave() does not reach their own queue entry.
        cancelQueue(player.getUniqueId(), "加入了小队");
        cancelQueue(party.leader, "有新队员加入");
        party.members.add(player.getUniqueId());
        parties.put(player.getUniqueId(), party);
        broadcast(party, "<white>" + Text.escape(player.getName()) + "</white> <ok>加入了小队 (" + party.size() + "/3)");
    }

    public void leave(Player player, boolean announce) {
        leave(player.getUniqueId(), announce);
    }

    /** Removes a member, online or not (a teammate who dropped out of a raid stays a member until removed). */
    private void leave(UUID uuid, boolean announce) {
        Party party = parties.remove(uuid);
        if (party == null) {
            return;
        }
        cancelQueue(party.leader, "队员离队");
        party.members.remove(uuid);
        plugin.escorts().onPartyLeave(uuid);
        if (announce) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                Text.send(player, "<muted>你已离开小队。");
            }
            broadcast(party, "<white>" + Text.escape(nameOf(uuid)) + "</white> <muted>离开了小队");
        }
        if (party.members.isEmpty()) {
            return;
        }
        if (party.leader.equals(uuid)) {
            party.leader = party.members.get(0);
            broadcast(party, "<muted>新的队长：<white>" + nameOf(party.leader));
        }
        if (party.members.size() == 1) {
            parties.remove(party.members.get(0));
            plugin.escorts().onPartyLeave(party.members.get(0));
            Player last = Bukkit.getPlayer(party.members.get(0));
            if (last != null) {
                Text.send(last, "<muted>小队已解散。");
            }
        }
    }

    public void kick(Player leader, String name) {
        Party party = partyOf(leader.getUniqueId());
        if (party == null || !party.leader.equals(leader.getUniqueId())) {
            Text.send(leader, "<danger>你不是队长。");
            return;
        }
        // By name among the members, so an offline teammate can be kicked too.
        UUID target = party.members.stream().filter(uuid -> nameOf(uuid).equalsIgnoreCase(name)).findFirst().orElse(null);
        if (target == null) {
            Text.send(leader, "<danger>小队中没有这名玩家。");
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
        if (p != null) {
            return p.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? "?" : name;
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

    /** Players waiting in the pool of this map and difficulty. */
    public int queuedCount(String mapId, String tierId) {
        int count = 0;
        for (QueueEntry entry : queue) {
            if (entry.map().equals(mapId) && entry.tier().equals(tierId)) {
                count += entry.members().size();
            }
        }
        return count;
    }

    /** Squads one raid of this map takes: match.max-squads, but never more squads than spawn groups. */
    public int capacity(MapDef map) {
        long groups = map.spawns().stream().map(MapDef.SpawnDef::group).distinct().count();
        int cap = Math.max(1, plugin.getConfig().getInt("match.max-squads", 4));
        return (int) Math.max(1, Math.min(cap, groups));
    }

    /** Validates and enqueues the player's party. */
    public boolean enqueue(Player player, String mapId, String tierId, RaidMode mode, boolean ranked, boolean fill) {
        Party party = partyOf(player.getUniqueId());
        if (party != null && !party.leader.equals(player.getUniqueId())) {
            Text.send(player, "<danger>只有队长可以开始匹配。");
            return false;
        }
        List<UUID> members = group(player);
        if (mode == RaidMode.SOLO && members.size() > 1) {
            Text.send(player, "<danger>单人模式需要先离开小队。");
            return false;
        }
        MapDef map = plugin.maps().get(mapId);
        Tier tier = plugin.tier(tierId);
        if (map == null || tier == null) {
            Text.send(player, "<danger>地图或难度不存在。");
            return false;
        }
        if (map.training()) {
            Text.send(player, "<danger>训练场不能匹配，请从部署界面的「训练行动」进入。");
            return false;
        }
        if (!map.enabled()) {
            // A draft from the map editor: until it is published it may have no exit or spawns.
            Text.send(player, "<danger>" + Text.escape(map.name()) + " 还没有发布，不能匹配。");
            return false;
        }
        if (plugin.editor().underMaintenance(map)) {
            Text.send(player, "<danger>" + Text.escape(map.name()) + " 正在维护（地图编辑中），暂时不能匹配。");
            return false;
        }
        for (UUID uuid : members) {
            Player member = Bukkit.getPlayer(uuid);
            if (member == null) {
                Text.send(player, "<danger>小队成员 " + Text.escape(nameOf(uuid)) + " 不在线，可以用 /cv party kick "
                        + Text.escape(nameOf(uuid)) + " 移出小队。");
                return false;
            }
            if (!plugin.lobby().isLobby(member.getWorld()) || plugin.raids().raidOf(member) != null) {
                Text.send(player, "<danger>小队成员 " + Text.escape(member.getName()) + " 不在大厅。");
                return false;
            }
            // Every member, not just the leader: an admin leader must not take ordinary players in.
            if (!plugin.canPlay(member, map)) {
                Text.send(player, "<danger>" + Text.escape(map.name()) + "只在测试模式开放"
                        + (member == player ? "。" : "，小队成员 " + Text.escape(member.getName()) + " 不能进入。"));
                return false;
            }
            plugin.lobby().captureLoadout(member);
            long value = plugin.carry().gearValue(member);
            if (value < tier.minValue()) {
                Text.send(player, "<danger>" + Text.escape(member.getName()) + " 的战备值 ◈" + Text.money(value)
                        + " 不足 " + tier.name() + " 要求的 ◈" + Text.money(tier.minValue()));
                return false;
            }
            Profile profile = plugin.profiles().get(member);
            if (profile.money < tier.entryFee()) {
                Text.send(player, "<danger>" + Text.escape(member.getName()) + " 的块币不足以支付入场费。");
                return false;
            }
            if (ranked && profile.level < plugin.getConfig().getInt("rank.min-level", 3)) {
                Text.send(player, "<danger>排位赛需要账号等级 " + plugin.getConfig().getInt("rank.min-level", 3) + "。");
                return false;
            }
        }
        for (UUID uuid : members) {
            QueueEntry old = queued(uuid);
            if (old != null) {
                queue.remove(old);
            }
        }
        queue.add(new QueueEntry(members, mapId, tierId, mode, ranked, fill, System.currentTimeMillis()));
        int waiting = queuedCount(mapId, tierId);
        for (UUID uuid : members) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                Text.send(p, "<accent>开始匹配：<white>" + Text.escape(map.name()) + " " + tier.tag() + " <muted>· " + mode.displayName()
                        + (ranked ? " · <accent>排位" : "") + " <muted>· 本场匹配中 " + waiting + " 人");
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
                Text.send(p, "<muted>已取消匹配" + (reason.isEmpty() ? "" : "（" + reason + "）") + "。");
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

    /**
     * Matchmaking, once a second for each pool (map + difficulty). Everyone in a pool when it launches
     * plays the same raid: parties stay together, entries that allow it are filled into squads of up to
     * three, and the raid takes squads in queue order up to {@link #capacity}. A pool launches as soon as
     * it is full, or once its longest-waiting entry has waited match.wait-seconds. Squads that did not fit
     * keep their place and, having already waited, launch the next raid straight away. A raid's roster is
     * final: nobody joins it after it starts.
     */
    private void tick() {
        long now = System.currentTimeMillis();
        int wait = Math.max(0, plugin.getConfig().getInt("match.wait-seconds", 30));
        Map<String, List<QueueEntry>> pools = new LinkedHashMap<>();
        for (QueueEntry entry : queue) {
            pools.computeIfAbsent(entry.key(), k -> new ArrayList<>()).add(entry);
        }
        for (List<QueueEntry> pool : pools.values()) {
            pool.sort(Comparator.comparingLong(QueueEntry::since));
            QueueEntry first = pool.get(0);
            MapDef map = plugin.maps().get(first.map());
            Tier tier = plugin.tier(first.tier());
            if (map == null || tier == null || !map.enabled()) {
                for (QueueEntry entry : pool) {
                    cancelQueue(entry.members().get(0), "地图或难度已不存在");
                }
                continue;
            }
            if (plugin.editor().underMaintenance(map)) {
                // Its template is being edited in place: a raid would copy it half done.
                for (QueueEntry entry : pool) {
                    cancelQueue(entry.members().get(0), Text.escape(map.name()) + " 进入维护");
                }
                continue;
            }
            int capacity = capacity(map);
            List<Squad> squads = formSquads(pool);
            int waited = (int) ((now - first.since()) / 1000);
            if (squads.size() >= capacity || waited >= wait) {
                launch(map, tier, squads.subList(0, Math.min(capacity, squads.size())));
            } else {
                showProgress(pool, squads.size(), capacity, wait - waited, now);
            }
        }
    }

    private void showProgress(List<QueueEntry> pool, int squads, int capacity, int left, long now) {
        int players = pool.stream().mapToInt(entry -> entry.members().size()).sum();
        for (QueueEntry entry : pool) {
            for (UUID uuid : entry.members()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    Notify.status(p, "queue", "<accent>匹配中 <white>" + Text.duration((now - entry.since()) / 1000)
                            + " <muted>· 同场 " + players + " 名干员 · 小队 " + squads + "/" + capacity + " · " + left + "s 后出发");
                }
            }
        }
    }

    private List<Player> onlineIdle(List<UUID> squad) {
        List<Player> online = new ArrayList<>();
        for (UUID uuid : squad) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && plugin.raids().raidOf(p) == null) {
                online.add(p);
            }
        }
        return online;
    }

    /** A squad being put together: the queue entries it came from and their players. */
    private record Squad(List<QueueEntry> entries, List<UUID> members) {
        Squad(QueueEntry first) {
            this(new ArrayList<>(), new ArrayList<>());
            add(first);
        }

        void add(QueueEntry entry) {
            entries.add(entry);
            members.addAll(entry.members());
        }

        long since() {
            return entries.stream().mapToLong(QueueEntry::since).min().orElse(Long.MAX_VALUE);
        }
    }

    /**
     * Squads, longest-waiting first. Parties that keep to themselves stay as they are. Entries that allow
     * filling are packed largest first, so as few squads as possible are short: each duo takes the
     * earliest solo, and the remaining solos go in threes. Fewer squads means more players fit in one raid.
     */
    private static List<Squad> formSquads(List<QueueEntry> pool) {
        List<Squad> squads = new ArrayList<>();
        List<QueueEntry> solos = new ArrayList<>();
        List<QueueEntry> duos = new ArrayList<>();
        for (QueueEntry entry : pool) {
            if (!entry.fillable()) {
                squads.add(new Squad(entry));
            } else if (entry.members().size() == 2) {
                duos.add(entry);
            } else {
                solos.add(entry);
            }
        }
        for (QueueEntry duo : duos) {
            Squad squad = new Squad(duo);
            if (!solos.isEmpty()) {
                squad.add(solos.remove(0));
            }
            squads.add(squad);
        }
        for (int i = 0; i < solos.size(); i += 3) {
            Squad squad = new Squad(solos.get(i));
            for (int j = i + 1; j < Math.min(i + 3, solos.size()); j++) {
                squad.add(solos.get(j));
            }
            squads.add(squad);
        }
        squads.sort(Comparator.comparingLong(Squad::since));
        return squads;
    }

    private void launch(MapDef map, Tier tier, List<Squad> squads) {
        List<List<Player>> teams = new ArrayList<>();
        Set<UUID> ranked = new HashSet<>();
        for (Squad squad : squads) {
            List<Player> team = new ArrayList<>();
            for (QueueEntry entry : squad.entries()) {
                queue.remove(entry);
                List<Player> online = onlineIdle(entry.members());
                // The queue-time checks again: gear can be stashed, money spent and test mode closed while waiting.
                String problem = null;
                for (Player p : online) {
                    String why = unfit(p, map, tier);
                    if (why != null) {
                        problem = Text.escape(p.getName()) + " " + why;
                        break;
                    }
                }
                if (problem != null) {
                    for (Player p : online) {
                        Text.send(p, "<danger>未能出发：" + problem + "，已取消匹配。");
                    }
                    continue;
                }
                team.addAll(online);
                if (entry.ranked()) {
                    ranked.addAll(entry.members());
                }
            }
            if (!team.isEmpty()) {
                teams.add(team);
            }
        }
        if (!teams.isEmpty()) {
            plugin.raids().start(map, tier, teams, ranked);
        }
    }

    /** Why this player may not deploy now, or null. */
    private @Nullable String unfit(Player player, MapDef map, Tier tier) {
        if (!plugin.lobby().isLobby(player.getWorld())) {
            return "不在大厅";
        }
        if (!plugin.canPlay(player, map)) {
            // Test mode switched off (or admin rights taken away) while waiting.
            return "不能进入" + map.name() + "（只在测试模式开放）";
        }
        long value = plugin.carry().gearValue(player);
        if (value < tier.minValue()) {
            return "的战备值 ◈" + Text.money(value) + " 不足 " + tier.name() + " 要求的 ◈" + Text.money(tier.minValue());
        }
        if (plugin.profiles().get(player).money < tier.entryFee()) {
            return "的块币不足以支付入场费";
        }
        return null;
    }

    // ------------------------------------------------------------------ squad teams

    private Scoreboard board() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    private String teamName(Raid raid, int squad) {
        return ("cv" + raid.id + "s" + squad);
    }

    public void teamFor(Raid raid, RaidPlayer rp, Player player) {
        team(raid, rp.squad).addEntry(player.getName());
    }

    /**
     * Puts an entity standing in for a squad member (a disconnected player's stand-in) on the
     * squad's team, so its name tag shows to squadmates only, as the player's own did.
     */
    public void teamFor(Raid raid, RaidPlayer rp, Entity entity) {
        team(raid, rp.squad).addEntry(entity.getUniqueId().toString());
    }

    public void leaveTeam(Entity entity) {
        String entry = entity.getUniqueId().toString();
        Team team = board().getEntryTeam(entry);
        if (team != null && team.getName().startsWith("cv")) {
            team.removeEntry(entry);
        }
    }

    private Team team(Raid raid, int squad) {
        Scoreboard board = board();
        String name = teamName(raid, squad);
        Team team = board.getTeam(name);
        if (team == null) {
            team = board.registerNewTeam(name);
            team.setAllowFriendlyFire(false);
            team.setCanSeeFriendlyInvisibles(true);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.FOR_OWN_TEAM);
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.FOR_OWN_TEAM);
            team.color(SQUAD_COLORS[(squad - 1) % SQUAD_COLORS.length]);
        }
        return team;
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
