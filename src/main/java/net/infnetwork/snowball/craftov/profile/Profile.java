package net.infnetwork.snowball.craftov.profile;

import net.infnetwork.snowball.craftov.item.ItemRecord;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Everything Craftov persists about a player. Gson-mapped, mutated on the main thread only. */
public final class Profile {
    public UUID uuid;
    public String name = "";
    public long money;
    public int level = 1;
    public long xp;
    public int rankPoints = 1000;
    public String operator = "vanguard";
    public int secureTier = 1;
    public int stashCapacity = 120;
    public boolean receivedStarter;

    /** Long-term storage. */
    public List<ItemRecord> stash = new ArrayList<>();
    /** Player inventory while in the lobby: slot index -> item. This is the next raid's loadout. */
    public Map<Integer, ItemRecord> loadout = new HashMap<>();
    /** Secure container contents; survive death. */
    public List<ItemRecord> secure = new ArrayList<>();
    /** Items returned by expired market listings or escort refunds, claimed from the stash menu. */
    public List<ItemRecord> mailbox = new ArrayList<>();
    /**
     * System mail (MailService): letters that say why they came, with money or items attached,
     * read and claimed in the stash mailbox. Oldest first.
     */
    public List<Mail> mail = new ArrayList<>();

    public Set<String> collected = new LinkedHashSet<>();
    public Set<Integer> collectionRewards = new LinkedHashSet<>();
    public Map<String, Long> stats = new HashMap<>();
    public List<Preset> presets = new ArrayList<>();
    public List<RaidReport> reports = new ArrayList<>();
    public List<TaskState> tasks = new ArrayList<>();
    public long taskDay = -1;
    /** Day (BountyService) the house bounty on this player was last paid out; it pays once a day. */
    public long houseBountyDay = -1;
    /** Insure guns, armour, rig and backpack on every deploy (InsuranceService). */
    public boolean autoInsure;
    /** Hideout facility levels, running crafts by facility, and when the intel centre was last emptied. */
    public Map<String, Integer> hideout = new HashMap<>();
    public Map<String, Craft> crafts = new HashMap<>();
    public long intelSince;
    /** Season pass progress (SeasonService): the season it belongs to, experience, claimed tiers. */
    public String seasonId = "";
    public long seasonXp;
    public Set<Integer> seasonClaimed = new LinkedHashSet<>();
    /** New-player guide progress (TutorialService). */
    public int tutorialStep;
    public boolean tutorialDone;
    /** Money other players have put on this player's head (BountyService). */
    public long bounty;
    /**
     * Reward taken from this player for their escort contract and not yet paid out or returned
     * (EscortService). Saved with the deduction itself; contracts live in memory only, so whatever
     * is still held when the player joins a freshly started server (after a crash) goes back to them.
     */
    public long escortEscrow;
    /**
     * Market gains from other players on {@link #marketDay} (MarketService daily cap): what they paid
     * for this player's listings after tax, plus how far below the reference price this player
     * bought theirs.
     */
    public long marketDay = -1;
    public long marketIncome;
    /** Last few login addresses, newest last (MarketService: same-address trades). */
    public List<String> ips = new ArrayList<>();
    public Set<String> unlockedOperators = new LinkedHashSet<>(List.of("vanguard", "medic", "recon", "engineer"));

    /** Raid id while the player is inside a raid; a crash leaves it set (see {@link #deployment}). */
    public @Nullable String activeRaid;
    /**
     * What the player took into the raid they are in, saved with the deploy (RaidManager.prepare)
     * and cleared together with {@link #activeRaid} whenever the raid is settled for them. A raid
     * that the server stops in, or crashes in, is void, and this is what it is undone to
     * (RaidManager.voidRaid).
     */
    public @Nullable Deployment deployment;

    public Profile() {
    }

    public Profile(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public long stat(String key) {
        return stats.getOrDefault(key, 0L);
    }

    public void addStat(String key, long delta) {
        stats.merge(key, delta, Long::sum);
    }

    /** The raid is settled for this player: out of it, with nothing left to undo. */
    public void leaveRaid() {
        activeRaid = null;
        deployment = null;
    }

    /**
     * Money the raid in progress has paid this player (a negative amount: taken), recorded so a
     * void raid can take it back. Nothing is recorded outside a raid.
     */
    public void earnedInRaid(long amount) {
        if (deployment != null) {
            deployment.earned += amount;
        }
    }

    /** A stat the raid in progress has counted, recorded so a void raid can take it back. */
    public void statInRaid(String key, long delta) {
        addStat(key, delta);
        if (deployment != null) {
            deployment.stats.merge(key, delta, Long::sum);
        }
    }

    public int stashUsed() {
        int used = 0;
        for (ItemRecord record : stash) {
            used += record.footprint();
        }
        return used;
    }

    public static final class Preset {
        public String name;
        public Map<Integer, String> slots = new HashMap<>();
        public Map<Integer, Integer> amounts = new HashMap<>();

        public Preset() {
        }

        public Preset(String name) {
            this.name = name;
        }
    }

    /**
     * What a player took into a raid and what the raid has given them since (see
     * {@link #deployment}). Saved with the profile, so a crash leaves it for the next login.
     */
    public static final class Deployment {
        /** Raid.key: unique across restarts, unlike the raid id. */
        public String raid = "";
        /** Map and difficulty names, for the letter that says the raid was void. */
        public String map = "";
        public String tier = "";
        /** When the player deployed (epoch millis). */
        public long time;
        /** The loadout and secure container as they went in, without insurance marks. */
        public Map<Integer, ItemRecord> loadout = new HashMap<>();
        public List<ItemRecord> secure = new ArrayList<>();
        public long entryFee;
        public long premium;
        /**
         * Money the raid has paid out so far less what it has cost on the way: daily contract
         * rewards and swaps, the level rewards they brought, bounties collected.
         */
        public long earned;
        /** Stats the raid has added so far: the raid itself, contracts done. */
        public Map<String, Long> stats = new HashMap<>();
        /** Daily contracts, account level and season pass as they stood at deploy. */
        public long taskDay = -1;
        public List<TaskState> tasks = new ArrayList<>();
        public int level = 1;
        public long xp;
        public String seasonId = "";
        public long seasonXp;
        /** Bounties collected in the raid: they go back on the heads they came off. */
        public List<BountyClaim> bounties = new ArrayList<>();
        /** Items the raid put in the item mailbox: they come out of it again. */
        public List<ItemRecord> mailed = new ArrayList<>();
        /**
         * Instance ids of gear in {@link #loadout} or {@link #secure} that has since left the raid
         * with another player: extracted or sent home with it, or kept in their secure container
         * through a death (RaidManager.leftWith). A void raid does not hand it back as well. Saved
         * as it happens, so a crash voids the raid the same way a stop does.
         */
        public Set<String> gone = new LinkedHashSet<>();
    }

    /** A bounty collected in a raid (BountyService.onKill). */
    public static final class BountyClaim {
        public UUID victim;
        /** What other players had put on the victim's head. */
        public long placed;
        /** The house bounty was part of it: houseBountyDay went from {@link #dayBefore} to {@link #day}. */
        public boolean house;
        public long dayBefore = -1;
        public long day = -1;
    }

    /** One system letter (MailService). */
    public static final class Mail {
        /** Short random id, unique among the player's letters. */
        public String id = "";
        /** When it was sent (epoch millis). */
        public long time;
        public String title = "";
        /** Plain text, not MiniMessage: shown escaped, one line per paragraph. */
        public String body = "";
        public long money;
        public List<ItemRecord> items = new ArrayList<>();
        /** Opened and, if anything was attached, paid out. */
        public boolean claimed;

        public boolean hasAttachments() {
            return money > 0 || items != null && !items.isEmpty();
        }
    }

    /** A hideout production: recipe id and when it is done (epoch millis). */
    public static final class Craft {
        public String recipe = "";
        public long endsAt;
    }

    /** One daily contract (see TaskService). */
    public static final class TaskState {
        public String kind = "AI_KILL";
        public @Nullable String map;
        public @Nullable String mapName;
        public long target;
        public long progress;
        public long reward;
        public long xp;
        public boolean done;

        public TaskState copy() {
            TaskState copy = new TaskState();
            copy.kind = kind;
            copy.map = map;
            copy.mapName = mapName;
            copy.target = target;
            copy.progress = progress;
            copy.reward = reward;
            copy.xp = xp;
            copy.done = done;
            return copy;
        }
    }

    public static final class RaidReport {
        public long time;
        public String map;
        public String tier;
        public String outcome;
        public long extractedValue;
        public long lostValue;
        public int kills;
        public int aiKills;
        public int seconds;
        public int rankDelta;
        public long xp;
    }
}
