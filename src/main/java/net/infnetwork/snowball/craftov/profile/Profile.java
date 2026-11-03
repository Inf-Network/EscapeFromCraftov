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

    public Set<String> collected = new LinkedHashSet<>();
    public Set<Integer> collectionRewards = new LinkedHashSet<>();
    public Map<String, Long> stats = new HashMap<>();
    public List<Preset> presets = new ArrayList<>();
    public List<RaidReport> reports = new ArrayList<>();
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
