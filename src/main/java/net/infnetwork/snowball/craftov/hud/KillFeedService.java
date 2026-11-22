package net.infnetwork.snowball.craftov.hud;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.combat.DamageService;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The kill feed and kill streaks. Every kill in a raid goes into the feed of everyone in it, and a
 * soldier a player takes down goes into that player's own feed; the HUD shows the last five lines in
 * the top-right corner for a few seconds each (hud.feed.seconds) instead of the chat, where squad and
 * bounty messages stay readable. A player's kills within hud.feed.streak-seconds of each other build
 * a streak: each one plays the next of six rising kill chimes (craftov:kill.1 to kill.6) and shows a
 * badge above the crosshair. Without BetterHud the feed goes to the chat as before.
 */
public final class KillFeedService implements Listener {

    /** Lines kept per player: the HUD shows this many. */
    public static final int LINES = 5;
    /** Streak tiers there are chimes and badges for; kills past it keep the top one. */
    public static final int TIERS = 6;

    /**
     * One line of a feed. Names and squads are kept rather than the text so each viewer sees their own
     * name, their squad and enemies in their own colours.
     */
    private record Entry(long tick, @Nullable UUID killer, String killerName, int killerSquad, String weapon, boolean headshot,
                         @Nullable UUID victim, String victimName, int victimSquad, boolean soldier) {
    }

    private record Streak(int count, long tick) {
    }

    private final CraftovPlugin plugin;
    private final Map<UUID, Deque<Entry>> feeds = new HashMap<>();
    private final Map<UUID, Streak> streaks = new HashMap<>();

    public KillFeedService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ kills

    /**
     * A player died in a raid. {@code credited} is the player the kill counts for (an enemy still in
     * the raid), or null: then the line names whoever dealt the last hit, or just the dead player.
     */
    public void playerDied(Raid raid, DamageService.@Nullable LastHit hit, Player victim, @Nullable Player credited) {
        RaidPlayer dead = raid.player(victim.getUniqueId());
        int victimSquad = dead == null ? -1 : dead.squad;
        Entry entry;
        if (hit == null || hit.attacker() == null) {
            entry = new Entry(now(), null, "", -1, "", false, victim.getUniqueId(), victim.getName(), victimSquad, false);
        } else {
            RaidPlayer by = raid.player(hit.attacker());
            entry = new Entry(now(), hit.attacker(), hit.attackerName(), by == null ? -1 : by.squad, hit.weapon(), hit.headshot(),
                    victim.getUniqueId(), victim.getName(), victimSquad, false);
        }
        for (Player viewer : raid.online()) {
            add(viewer, entry, raid);
        }
        if (credited != null) {
            streak(credited);
        }
    }

    /** A player took down a soldier: the line goes to that player's own feed only. */
    public void soldierKilled(Raid raid, Player killer, String soldier, String weapon, boolean headshot) {
        RaidPlayer by = raid.player(killer.getUniqueId());
        add(killer, new Entry(now(), killer.getUniqueId(), killer.getName(), by == null ? -1 : by.squad, weapon, headshot,
                null, soldier, -1, true), raid);
        streak(killer);
    }

    private void add(Player viewer, Entry entry, Raid raid) {
        Deque<Entry> feed = feeds.computeIfAbsent(viewer.getUniqueId(), k -> new ArrayDeque<>());
        feed.addLast(entry);
        while (feed.size() > LINES) {
            feed.removeFirst();
        }
        if (!plugin.hud().betterHud()) {
            viewer.sendMessage(Text.mm(format(entry, viewer, raid)));
        }
    }

    /** One more kill for the player's streak: its chime, and the badge the HUD shows. */
    private void streak(Player killer) {
        long now = now();
        Streak last = streaks.get(killer.getUniqueId());
        int count = last != null && now - last.tick() <= streakTicks() ? last.count() + 1 : 1;
        streaks.put(killer.getUniqueId(), new Streak(count, now));
        killer.playSound(killer.getLocation(), "craftov:kill." + Math.min(TIERS, count), SoundCategory.PLAYERS, 1f, 1f);
    }

    /**
     * True if a kill was credited to this player on this tick: its streak chime has played, so the
     * hit that did it plays no kill sound of its own.
     */
    public boolean creditedNow(Player shooter) {
        Streak last = streaks.get(shooter.getUniqueId());
        return last != null && last.tick() == now();
    }

    // ------------------------------------------------------------------ reading

    /** The feed lines the player's HUD shows now, oldest first (MiniMessage), up to {@link #LINES}. */
    public List<String> lines(Player viewer) {
        Deque<Entry> feed = feeds.get(viewer.getUniqueId());
        List<String> out = new ArrayList<>();
        if (feed == null) {
            return out;
        }
        long since = now() - Math.round(plugin.getConfig().getDouble("hud.feed.seconds", 5.0) * 20);
        Raid raid = plugin.raids().raidOf(viewer);
        for (Entry entry : feed) {
            if (entry.tick() >= since) {
                out.add(format(entry, viewer, raid));
            }
        }
        return out;
    }

    /** Every line the player's feed still holds, however old (for /cv feed). */
    public List<String> allLines(Player viewer) {
        Deque<Entry> feed = feeds.get(viewer.getUniqueId());
        List<String> out = new ArrayList<>();
        if (feed != null) {
            Raid raid = plugin.raids().raidOf(viewer);
            for (Entry entry : feed) {
                out.add(format(entry, viewer, raid));
            }
        }
        return out;
    }

    /** The player's current streak, or 0 once it is over (no kill within the streak window). */
    public int streak(UUID player) {
        Streak last = streaks.get(player);
        return last == null || now() - last.tick() > streakTicks() ? 0 : last.count();
    }

    /** The badge above the crosshair for a fresh kill (MiniMessage), or "" once it has faded. */
    public String badge(Player player) {
        Streak last = streaks.get(player.getUniqueId());
        if (last == null || now() - last.tick() > Math.round(plugin.getConfig().getDouble("hud.feed.badge-seconds", 2.5) * 20)) {
            return "";
        }
        return switch (Math.min(TIERS, last.count())) {
            case 1 -> "<#F2C94C>击败";
            case 2 -> "<#F2C94C>双杀";
            case 3 -> "<#FFB74D>三杀";
            case 4 -> "<#FF9A3C>四杀";
            case 5 -> "<#FF6B4D>五杀";
            default -> "<#FF4D4D>" + last.count() + " 连杀 · 无人能挡";
        };
    }

    private String format(Entry entry, Player viewer, @Nullable Raid raid) {
        String victim = name(entry.victim(), entry.victimName(), entry.victimSquad(), viewer, raid, entry.soldier());
        if (entry.killer() == null) {
            return "<#8A8F98>☠ " + victim;
        }
        return name(entry.killer(), entry.killerName(), entry.killerSquad(), viewer, raid, false)
                + " <#8A8F98>[" + Text.escape(entry.weapon()) + "]" + (entry.headshot() ? " <#FF4D4D>◎" : "")
                + " <#8A8F98>→ " + victim;
    }

    /** A name in the viewer's colours: gold for themselves, green for their squad, red for enemies, grey for soldiers. */
    private static String name(@Nullable UUID id, String name, int squad, Player viewer, @Nullable Raid raid, boolean soldier) {
        String color;
        if (soldier) {
            color = "#C8CCD2";
        } else if (viewer.getUniqueId().equals(id)) {
            color = "#F2C94C";
        } else {
            RaidPlayer self = raid == null ? null : raid.player(viewer.getUniqueId());
            color = self != null && squad >= 0 && self.squad == squad ? "#5BD16B" : "#FF8A80";
        }
        return "<" + color + ">" + Text.escape(name);
    }

    private long streakTicks() {
        return Math.round(plugin.getConfig().getDouble("hud.feed.streak-seconds", 6.0) * 20);
    }

    private static long now() {
        return Bukkit.getCurrentTick();
    }

    /** A new raid starts with an empty feed and no streak. */
    public void clear(Player player) {
        feeds.remove(player.getUniqueId());
        streaks.remove(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }
}
