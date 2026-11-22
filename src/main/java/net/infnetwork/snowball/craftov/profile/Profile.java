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

    /** Raid id while the player is inside a raid; used to resolve crashes as MIA. */
    public @Nullable String activeRaid;

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
